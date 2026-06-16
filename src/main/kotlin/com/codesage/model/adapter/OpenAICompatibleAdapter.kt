package com.codesage.model.adapter

import com.codesage.model.dto.*
import com.codesage.shared.exceptions.NetworkException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * OpenAI 兼容 API 格式的模型适配器抽象基类
 * 适用于 MiniMax、Kimi、OpenAI 等遵循相同协议格式的提供商
 */
abstract class OpenAICompatibleAdapter(
    protected val apiKey: String,
    protected val baseUrl: String
) : ModelAdapter {

    protected val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // === MiniMax-M3 / Qwen2.5 / DeepSeek-R1 (Qwen-distill) 等:
    // 把 reasoning 跟正文一起塞进 delta.content,靠 <think>...</think> XML 标签区分。
    // adapter 实例维护跨 chunk 状态机:
    //   - inThinkBlock: 当前 chunk 是否在 <think> 块内
    //   - pendingThink:  跨 chunk 累积的 <think> 内容(模型可能拆成多个 chunk 推)
    // 状态机每次推进消费一段 delta,产出 reasoning 跟 content 拆分。
    private var inThinkBlock: Boolean = false
    private var pendingThink: StringBuilder = StringBuilder()
    // 2026-06: fenced code block 状态机 — 跨 chunk 识别 ```/~~~ 围栏
    private val fencedCodeSplitter = FencedCodeSplitter()
    // 关键日志:首块 dump 标志。turn 间由 resetStreamState() 重置。
    private var firstChunkDumped: Boolean = false

    protected val logger = com.codesage.shared.utils.Logger.getLogger(this::class.java)

    /**
     * 2026-06: 该 adapter 用的协议层 normalizer(per-adapter 单例,跨 chunk 累积状态)。
     */
    private val openAINormalizer: OpenAIStreamNormalizer by lazy {
        OpenAIStreamNormalizer(providerName = providerName)
    }

    override fun streamNormalizer(): StreamEventNormalizer = openAINormalizer

    /**
     * 2026-06: 重置 normalizer 跨 chunk 状态机(每轮流开始时由 ModelGateway 调)。
     */
    fun resetStreamNormalizer() {
        openAINormalizer.reset()
    }

    abstract override val providerName: String
    abstract override val supportedModels: List<String>
    abstract val chatEndpointPath: String

    // T1.1 修复：默认能力。子类可覆盖 capabilities 提供更精确的描述。
    override val capabilities: ModelCapabilities = ModelCapabilities(
        streaming = true,
        functionCalling = true,
        vision = false,
        toolStreaming = true,
        maxContextTokens = 128_000,
        maxOutputTokens = 4_096
    )

    // 旧方法保留为默认实现，从 capabilities 派生
    override fun supportsStreaming(): Boolean = capabilities.streaming
    override fun supportsFunctionCalling(): Boolean = capabilities.functionCalling
    override fun supportsVision(): Boolean = capabilities.vision

    protected open fun convertMessage(message: Message): VendorMessage {
        return message.toVendorMessage()
    }

    /**
     * 重置流式状态机(在 think 标签模式下用)。
     *
     * 关键时序: EnhancedAgentLoop 同一 adapter 实例被多次流复用;
     * 上一轮若因网络中断 / 用户停止 / 错误导致流未正常结束(未
     * 收到 </think> 或 [DONE]),inThinkBlock 可能仍为 true,
     * 下一轮首个不含 < 的 chunk 会被当 thinking。
     * 上游应在每轮流开始时调一次,避免状态污染。
     */
    fun resetStreamState() {
        inThinkBlock = false
        pendingThink.setLength(0)
        firstChunkDumped = false
        // 2026-06: 重置围栏状态机(同 adapter 实例多 turn 复用)
        fencedCodeSplitter.reset()
    }

    override fun toVendorRequest(request: ChatRequest): String {
        val vendorRequest = VendorChatRequest(
            model = request.model,
            messages = request.messages.map { convertMessage(it) },
            tools = if (supportsFunctionCalling()) request.tools?.map { it.toVendorTool() } else null,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            stream = request.stream,
            parallelToolCalls = request.parallelToolCalls
        )
        return json.encodeToString(vendorRequest)
    }

    override fun fromVendorResponse(response: String): ChatResponse {
        val jsonElement = json.parseToJsonElement(response)

        // 处理标准 OpenAI 格式错误（部分代理可能返回 HTTP 200 但 body 含 error）
        if (jsonElement is JsonObject) {
            val error = jsonElement["error"]
            if (error != null) {
                val errorMsg = when (error) {
                    is JsonObject -> error["message"]?.jsonPrimitive?.content ?: error.toString()
                    else -> error.toString()
                }
                throw NetworkException("API error: $errorMsg")
            }
        }

        val vendorResp = json.decodeFromJsonElement<VendorChatResponse>(jsonElement)

        // 处理 API 业务错误（如 MiniMax base_resp）
        vendorResp.baseResp?.let { base ->
            if (base.statusCode != 0 && base.statusCode != 200) {
                throw NetworkException("API error ${base.statusCode}: ${base.statusMsg}")
            }
        }

        val choices = vendorResp.choices ?: emptyList()
        if (choices.isEmpty()) {
            throw NetworkException("API returned empty choices (no message content)")
        }

        return ChatResponse(
            id = vendorResp.id,
            model = vendorResp.model,
            choices = choices.mapIndexed { index, choice ->
                Choice(
                    index = index,
                    message = choice.message.toUnifiedMessage(),
                    finishReason = choice.finishReason
                )
            },
            usage = vendorResp.usage?.toUnifiedUsage()
        )
    }

    /**
     * 2026-06: parseStreamChunk 改为返回 List<StreamChunk>。
     *
     * 原因:围栏状态机(FencedCodeSplitter)在一帧 SSE 数据内可能产生多个事件
     * (典型:Delta + End 同时出现)。保留单 StreamChunk 返回意味着只能
     * emit 1 个事件,会丢 Delta 的代码内容。
     *
     * ModelGateway 会用 for 循环消费整个列表,语义无变化。
     *
     * 其他 adapter (AnthropicStreamParser / GeminiAdapter) 保留 StreamChunk? 旧契约,
     * ModelGateway 已做兼容:若 parseStreamChunk 返回 null 当成空 list。
     */
        override fun parseStreamChunk(chunk: String): List<StreamEvent> {
        return openAINormalizer.normalize(chunk, StreamEventNormalizer.StreamState())
    }

    fun parseStreamChunkLegacy(chunk: String): List<StreamChunk> {
        if (!chunk.startsWith("data:")) return emptyList()

        val jsonStr = chunk.removePrefix("data:").trim()
        if (jsonStr == "[DONE]") {
            logger.info("[$providerName] DONE SENTINEL received")
            return listOf(StreamChunk(id = "", delta = "", done = true))
        }

        return try {
            val streamData = json.decodeFromString<VendorStreamData>(jsonStr)
            val choice = streamData.choices.firstOrNull()
            val rawDelta = choice?.delta?.content ?: ""
            // 关键日志:每个 provider 实例只 dump 一次首个非空 chunk
            if (rawDelta.isNotEmpty() && !firstChunkDumped) {
                firstChunkDumped = true
                val d = choice?.delta
                logger.info(
                    "[$providerName] FIRST CHUNK raw: " +
                        "content.len=${rawDelta.length} " +
                        "reasoningContent.len=${d?.reasoningContent?.length ?: 0} " +
                        "reasoning.len=${d?.reasoning?.length ?: 0} " +
                        "thinking.len=${d?.thinking?.length ?: 0} " +
                        "finishReason=${choice?.finishReason} " +
                        "content.head=${rawDelta.take(200).replace("\n", "\\n")} " +
                        "rawJson.head=${jsonStr.take(500)}"
                )
            }
            // === reasoning 提取(同原逻辑) ===
            val dedicatedReasoning: String? = choice?.delta?.let { d ->
                listOf(d.reasoningContent, d.reasoning, d.thinking)
                    .firstOrNull { !it.isNullOrBlank() }
            }
            val (reasoningDelta, reasoningParsedDelta) = if (dedicatedReasoning != null) {
                Pair(dedicatedReasoning, rawDelta)
            } else if (rawDelta.contains("<") && (inThinkBlock || rawDelta.contains("<think>") || rawDelta.contains("</think>"))) {
                splitThinkTag(rawDelta)
            } else if (inThinkBlock) {
                pendingThink.append(rawDelta)
                Pair(rawDelta, "")
            } else {
                Pair(null, rawDelta)
            }
            // reasoningParsedDelta 是去 think 后的纯文本(可能含代码围栏)

            // === 围栏状态机(2026-06 新增) ===
            // 优先级:reasoning > codeBlock > text
            // - 若 reasoningDelta 非空,这一帧是 reasoning 段,代码围栏不会出现(模型思考时不写代码)
            // - 否则跑 fencedCodeSplitter,把代码块字符切走,text 里只留非代码文本
            val (textDelta, codeBlockEvents) = if (reasoningDelta != null) {
                // reasoning 帧:围栏忽略(不应该出现,但兜底)
                Pair(reasoningParsedDelta, emptyList<CodeBlockEvent>())
            } else {
                val result = fencedCodeSplitter.feed(reasoningParsedDelta)
                Pair(result.text, result.events)
            }

            val finishReason = choice?.finishReason
            val toolCallDeltas = choice?.delta?.toolCalls?.map { tcDelta ->
                StreamToolCallDelta(
                    index = tcDelta.index,
                    id = tcDelta.id,
                    name = tcDelta.function?.name,
                    arguments = tcDelta.function?.arguments
                )
            } ?: emptyList()

            // 构造 StreamChunk 列表
            // - 若有多个 codeBlockEvents(典型 Delta+End),需要多个 StreamChunk
            // - 否则返回 1 个(把 codeBlockEvent 填进去)
            val mainChunk = StreamChunk(
                id = streamData.id,
                delta = textDelta,
                reasoningDelta = reasoningDelta,
                done = false,
                toolCallDeltas = toolCallDeltas,
                finishReason = finishReason,
                codeBlock = codeBlockEvents.firstOrNull(),
            )
            if (codeBlockEvents.size <= 1) {
                listOf(mainChunk)
            } else {
                // 多事件:第 1 个装到 mainChunk,剩余作为独立 chunk(只带 codeBlock)
                listOf(mainChunk) + codeBlockEvents.drop(1).map { cbEvent ->
                    StreamChunk(
                        id = streamData.id,
                        delta = "",
                        reasoningDelta = null,
                        done = false,
                        toolCallDeltas = emptyList(),
                        finishReason = null,
                        codeBlock = cbEvent,
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 2026-06: 流结束兜底,flush 围栏状态机可能残留的 events(典型:流中断时
     * 代码块未闭合,splitter.flush() 会在 done=true 之前 emit Delta + End)。
     *
     * ModelGateway 在 SSE 循环结束后调一次;无残留时返回空 list。
     */
    fun flushCodeBlockEvents(): List<CodeBlockEvent> = fencedCodeSplitter.flush()

    protected open val userAgent: String = "CodeSage/1.0"

    override fun getHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
        "User-Agent" to userAgent
    )

    override fun getChatEndpoint(): String = "$baseUrl$chatEndpointPath"
    override fun getStreamEndpoint(): String = getChatEndpoint()

    override fun getModelsEndpoint(): String = "$baseUrl/v1/models"

    /**
     * 判断模型是否为聊天模型（可覆盖以适配不同提供商的命名规则）
     */
    protected open fun isChatModel(modelId: String): Boolean {
        val lower = modelId.lowercase()
        // 排除已知的非聊天模型
        val nonChatKeywords = listOf(
            "embed", "tts", "whisper", "dall", "moderation",
            "babbage", "davinci", "ada", "curie",
            "image", "audio", "realtime"
        )
        return nonChatKeywords.none { lower.contains(it) }
    }

    // Reusable OkHttpClient for model fetching
    private val modelsClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    override suspend fun fetchModels(): List<String> {
        return try {
            val request = okhttp3.Request.Builder()
                .url(getModelsEndpoint())
                .headers(okhttp3.Headers.Builder().apply {
                    getHeaders().forEach { (k, v) -> add(k, v) }
                }.build())
                .get()
                .build()

            modelsClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val data = jsonObj["data"]?.jsonArray ?: return emptyList()

                data.mapNotNull { element ->
                    try {
                        val obj = element.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content
                        id?.takeIf { isChatModel(it) }
                    } catch (e: Exception) {
                        null
                    }
                }.sorted()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 状态机: 把 rawDelta 按 <think>...</think> 切分成 (reasoning, content)。
     *
     * 跨 chunk 状态由 inThinkBlock + pendingThink 维护。模型可能把
     *  <think> 拆成多个 chunk 推(比如 chunk1="<think>", chunk2="用户要求",
     *   chunk3="...</think>\n\n正文..."),任何切分点都不能丢字符。
     *
     * 边界情况:
     *   - rawDelta 没有任何 <think> 标签:不修改 inThinkBlock,
     *     把整段当 thinking(若已在块内)或 content(若不在)。
     *   - 模型根本不用 <think>:这条路径不会被触发(上游 rawDelta.contains("<")
     *     守卫已过滤)。
     *   - <think> 跨 chunk:每帧已 consume 的部分先作 reasoning 发出去,
     *     等 </think> 出现再切回 content 区。
     *
     * @return Pair(reasoning, content) — reasoning 为空时返回 null,
     *         调用方据此决定是否 emit ModelReasoning 事件(避免空 chip)。
     */
    private fun splitThinkTag(rawDelta: String): Pair<String?, String> {
        var reasoning = ""
        var content = ""
        var i = 0
        val s = rawDelta

        while (i < s.length) {
            if (!inThinkBlock) {
                val idx = s.indexOf("<think>", i)
                if (idx < 0) {
                    content += s.substring(i)
                    i = s.length
                } else {
                    content += s.substring(i, idx)
                    inThinkBlock = true
                    pendingThink.setLength(0)
                    i = idx + "<think>".length
                }
            } else {
                val idx = s.indexOf("</think>", i)
                if (idx < 0) {
                    pendingThink.append(s.substring(i))
                    reasoning += s.substring(i)
                    i = s.length
                } else {
                    pendingThink.append(s.substring(i, idx))
                    reasoning += s.substring(i, idx)
                    inThinkBlock = false
                    pendingThink.setLength(0)
                    i = idx + "</think>".length
                }
            }
        }

        return Pair(
            if (reasoning.isEmpty()) null else reasoning,
            content,
        )
    }
}

// === 共享的 Vendor DTOs ===

@Serializable
data class VendorChatRequest(
    val model: String,
    val messages: List<VendorMessage>,
    val tools: List<VendorTool>? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean? = null
)

@Serializable
data class VendorMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<VendorToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
)

@Serializable
data class VendorTool(
    @EncodeDefault
    val type: String = "function",
    @SerialName("function")
    val function: VendorFunction
)

@Serializable
data class VendorFunction(
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

@Serializable
data class VendorChatResponse(
    val id: String = "",
    val model: String = "",
    val choices: List<VendorChoice>? = null,
    val usage: VendorUsage? = null,
    @SerialName("base_resp")
    val baseResp: VendorBaseResp? = null
)

@Serializable
data class VendorBaseResp(
    @SerialName("status_code")
    val statusCode: Int = 0,
    @SerialName("status_msg")
    val statusMsg: String = ""
)

@Serializable
data class VendorChoice(
    val index: Int,
    val message: VendorMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class VendorUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

@Serializable
data class VendorStreamData(
    val id: String,
    val choices: List<VendorStreamChoice>
)

@Serializable
data class VendorStreamChoice(
    val delta: VendorDelta,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class VendorDelta(
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    // 字段名在不同 OpenAI 兼容 provider 之间命名不统一:
    //   - DeepSeek-R1 / Doubao:        "reasoning_content"
    //   - MiniMax-M2.x 旧版:          "reasoning"   (无 _content 后缀)
    //   - 部分 OpenAI 兼容中转:        "thinking"   (转 Anthropic 时常见)
    // 同时收三个,parser 取第一个非空,避免互踩。
    val reasoning: String? = null,
    val thinking: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<VendorToolCallDelta>? = null
)

@Serializable
data class VendorToolCall(
    val id: String,
    val type: String = "function",
    @SerialName("function")
    val function: VendorFunctionCall
)

@Serializable
data class VendorFunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class VendorToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    @SerialName("function")
    val function: VendorFunctionCallDelta? = null
)

@Serializable
data class VendorFunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null
)

// === 转换函数 ===

private fun Message.toVendorMessage() = VendorMessage(
    role = when (this.role) {
        Role.SYSTEM -> "system"
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.TOOL -> "tool"
    },
    content = this.content,
    name = this.name,
    toolCalls = this.toolCalls?.map { tc ->
        VendorToolCall(
            id = tc.id,
            type = "function",
            function = VendorFunctionCall(
                name = tc.name,
                arguments = tc.arguments
            )
        )
    },
    toolCallId = this.toolCallId
)

internal fun VendorMessage.toUnifiedMessage() = Message(
    role = when (this.role) {
        "system" -> Role.SYSTEM
        "user" -> Role.USER
        "assistant" -> Role.ASSISTANT
        "tool" -> Role.TOOL
        else -> Role.USER
    },
    content = this.content ?: "",
    name = this.name,
    toolCalls = this.toolCalls?.map { tc ->
        ToolCall(
            id = tc.id,
            name = tc.function.name,
            arguments = tc.function.arguments
        )
    },
    toolCallId = this.toolCallId
)

private fun Tool.toVendorTool() = VendorTool(
    function = VendorFunction(
        name = this.name,
        description = this.description,
        parameters = this.parameters
    )
)

internal fun VendorUsage.toUnifiedUsage() = Usage(
    promptTokens = this.promptTokens,
    completionTokens = this.completionTokens,
    totalTokens = this.totalTokens
)
