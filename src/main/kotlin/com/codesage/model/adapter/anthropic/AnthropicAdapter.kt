package com.codesage.model.adapter.anthropic

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.shared.exceptions.NetworkException
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * T1.2 修复：Anthropic Claude 原生适配器
 *
 * 实现 Anthropic Messages API（https://docs.anthropic.com/claude/reference/messages_post）。
 *
 * **与 OpenAI 协议的关键差异**：
 * 1. `system` 是顶层字段，不在 messages 数组
 * 2. `max_tokens` 必填（默认 4096）
 * 3. `tools` 用 `input_schema`（不是 `parameters`）
 * 4. 流式用自定义 SSE event types（message_start / content_block_delta / message_stop 等）
 * 5. 工具调用以 `content_block` (type=tool_use) 形式
 * 6. `cache_control: {type: "ephemeral"}` 标记启用 prompt caching
 *
 * **实现选择**：
 * - OkHttp 客户端 + 手动 SSE 解析（零新增依赖，保持与项目其它 adapter 一致）
 * - 流式 + 非流式两种模式都支持
 * - ToolCall 转换通过 [AnthropicStreamParser] 处理
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T1.2）：
 * - [ ] MockWebServer 测试：stream=true + 1 个 tool_use，能完整跑通
 * - [ ] 测试 prompt cache 标记正确出现在请求 body
 * - [ ] 测试 image content block 解析为 vision message
 */
class AnthropicAdapter(
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    customModels: List<String>? = null
) : ModelAdapter {

    private val logger = Logger.getLogger<AnthropicAdapter>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override val providerName: String = "anthropic"

    override val supportedModels: List<String> = customModels ?: listOf(
        "claude-3-5-sonnet-20241022",
        "claude-3-5-haiku-20241022",
        "claude-3-opus-20240229",
        "claude-3-sonnet-20240229",
        "claude-3-haiku-20240307"
    )

    // T1.1 集成：Anthropic Claude 3.5 Sonnet 完整能力声明
    override val capabilities: ModelCapabilities = ModelCapabilities(
        streaming = true,
        functionCalling = true,
        vision = true,
        toolStreaming = true,
        systemPromptCache = false,
        promptCaching = true,  // Claude 支持 prompt caching
        maxContextTokens = 200_000,
        maxOutputTokens = 8_192,
        pricePer1kInput = 0.003,  // 3 USD per million input tokens
        pricePer1kOutput = 0.015  // 15 USD per million output tokens
    )

    private val streamParser = AnthropicStreamParser()

    /**
     * OkHttp 客户端（lazy 初始化）
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)  // 流式响应可能较慢
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // === 端点 ===

    override fun getStreamEndpoint(): String = "$baseUrl/v1/messages?stream=true"

    override fun getChatEndpoint(): String = "$baseUrl/v1/messages"

    // === 请求头 ===

    override fun getHeaders(): Map<String, String> = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
        "content-type" to "application/json"
    )

    // === 请求/响应转换 ===

    override fun toVendorRequest(request: ChatRequest): String {
        val messages = mutableListOf<AnthropicMessage>()
        var systemPrompt: String? = null

        for (msg in request.messages) {
            when (msg.role) {
                Role.SYSTEM -> {
                    // 累积所有 system 消息（Anthropic 只有一个 system 字段）
                    systemPrompt = if (systemPrompt == null) {
                        msg.content
                    } else {
                        systemPrompt + "\n\n" + msg.content
                    }
                }

                Role.USER -> {
                    messages.add(
                        AnthropicMessage(
                            role = "user",
                            content = JsonPrimitive(msg.content)
                        )
                    )
                }

                Role.ASSISTANT -> {
                    // Assistant 消息可能包含 text 和/或 tool_use
                    val contentBlocks = mutableListOf<JsonObject>()
                    if (msg.content.isNotEmpty()) {
                        contentBlocks.add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", msg.content)
                            }
                        )
                    }
                    if (msg.toolCalls != null) {
                        for (tc in msg.toolCalls) {
                            contentBlocks.add(
                                buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    // input 必须是 JSON object，不是 string
                                    val input = try {
                                        json.parseToJsonElement(tc.arguments).jsonObject
                                    } catch (e: Exception) {
                                        // 解析失败时包装为 {"raw": arguments}
                                        buildJsonObject { put("raw", tc.arguments) }
                                    }
                                    put("input", input)
                                }
                            )
                        }
                    }
                    if (contentBlocks.isNotEmpty()) {
                        messages.add(
                            AnthropicMessage(
                                role = "assistant",
                                content = JsonArray(contentBlocks)
                            )
                        )
                    }
                }

                Role.TOOL -> {
                    // Tool result 在 Anthropic 中是 user 角色 + tool_result content block
                    val contentBlock = buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", msg.toolCallId ?: "")
                        put("content", msg.content)
                    }
                    messages.add(
                        AnthropicMessage(
                            role = "user",
                            content = JsonArray(listOf(contentBlock))
                        )
                    )
                }
            }
        }

        // 转换 tools (OpenAI 格式 → Anthropic 格式)
        val anthropicTools = request.tools?.map { tool ->
            AnthropicTool(
                name = tool.name,
                description = tool.description,
                inputSchema = AnthropicInputSchema(
                    type = tool.parameters.type,
                    properties = buildJsonObject {
                        tool.parameters.properties.forEach { (k, v) ->
                            put(k, buildJsonObject {
                                put("type", v.type)
                                if (v.description != null) put("description", v.description)
                                if (v.enum != null) put("enum", JsonArray(v.enum.map { JsonPrimitive(it) }))
                            })
                        }
                    },
                    required = tool.parameters.required
                )
            )
        }

        val anthropicRequest = AnthropicRequest(
            model = request.model,
            messages = messages,
            maxTokens = request.maxTokens ?: 4096,  // Anthropic 必填
            system = systemPrompt,
            tools = anthropicTools,
            temperature = request.temperature,
            stream = request.stream
        )

        return json.encodeToString(anthropicRequest)
    }

    override fun fromVendorResponse(response: String): ChatResponse {
        val anthropicResp = json.decodeFromString(AnthropicResponse.serializer(), response)
        return convertToChatResponse(anthropicResp)
    }

    /**
     * 将 Anthropic 响应转换为统一 ChatResponse
     */
    private fun convertToChatResponse(resp: AnthropicResponse): ChatResponse {
        // Anthropic 的 content 可能是 text + tool_use 混合
        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()

        for (block in resp.content ?: emptyList()) {
            when (block.type) {
                "text" -> {
                    textBuilder.append(block.text ?: "")
                }

                "tool_use" -> {
                    toolCalls.add(
                        ToolCall(
                            id = block.id ?: "tool_${toolCalls.size}",
                            name = block.name ?: "",
                            arguments = block.input?.toString() ?: "{}"
                        )
                    )
                }
            }
        }

        val message = if (toolCalls.isNotEmpty()) {
            Message(
                role = Role.ASSISTANT,
                content = textBuilder.toString(),
                toolCalls = toolCalls
            )
        } else {
            Message.assistantMessage(textBuilder.toString())
        }

        val choice = Choice(
            index = 0,
            message = message,
            finishReason = resp.stopReason
        )

        val usage = resp.usage?.let { u ->
            Usage(
                promptTokens = u.inputTokens,
                completionTokens = u.outputTokens,
                totalTokens = u.inputTokens + u.outputTokens
            )
        }

        return ChatResponse(
            id = resp.id ?: "msg_${System.currentTimeMillis()}",
            model = resp.model ?: "unknown",
            choices = listOf(choice),
            usage = usage
        )
    }

    override fun parseStreamChunk(chunk: String): StreamChunk? {
        return streamParser.parseEvent(chunk)
    }

    /**
     * 发送非流式请求并解析响应
     */
    suspend fun chat(request: ChatRequest): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val body = toVendorRequest(request.copy(stream = false))
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(getChatEndpoint())
                .apply { getHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .post(body)
                .build()

            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    logger.error("Anthropic API error ${response.code}: $errBody")
                    return@withContext Result.failure(
                        NetworkException("Anthropic API ${response.code}: $errBody")
                    )
                }
                val body = response.body?.string() ?: ""
                Result.success(fromVendorResponse(body))
            }
        } catch (e: Exception) {
            logger.error("Anthropic request failed", e)
            Result.failure(e)
        }
    }

    /**
     * 简化版的 fetchModels（不实际调用 API）
     * Anthropic 暂时没有 list models API，直接返回 supportedModels
     */
    override suspend fun fetchModels(): List<String> = supportedModels
}
