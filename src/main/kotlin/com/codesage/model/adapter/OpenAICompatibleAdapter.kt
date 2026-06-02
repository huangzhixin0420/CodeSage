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

    protected val logger = com.codesage.shared.utils.Logger.getLogger(this::class.java)

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

    override fun toVendorRequest(request: ChatRequest): String {
        val vendorRequest = VendorChatRequest(
            model = request.model,
            messages = request.messages.map { convertMessage(it) },
            tools = if (supportsFunctionCalling()) request.tools?.map { it.toVendorTool() } else null,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            stream = request.stream
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

    override fun parseStreamChunk(chunk: String): StreamChunk? {
        if (!chunk.startsWith("data:")) return null

        val jsonStr = chunk.removePrefix("data:").trim()
        if (jsonStr == "[DONE]") {
            return StreamChunk(id = "", delta = "", done = true)
        }

        return try {
            val streamData = json.decodeFromString<VendorStreamData>(jsonStr)
            val choice = streamData.choices.firstOrNull()
            val delta = choice?.delta?.content ?: ""
            val finishReason = choice?.finishReason

            // 解析流式工具调用增量
            val toolCallDeltas = choice?.delta?.toolCalls?.map { tcDelta ->
                StreamToolCallDelta(
                    index = tcDelta.index,
                    id = tcDelta.id,
                    name = tcDelta.function?.name,
                    arguments = tcDelta.function?.arguments
                )
            } ?: emptyList()

            StreamChunk(
                id = streamData.id,
                delta = delta,
                done = false,
                toolCallDeltas = toolCallDeltas,
                finishReason = finishReason
            )
        } catch (e: Exception) {
            null
        }
    }

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
    val stream: Boolean = false
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
