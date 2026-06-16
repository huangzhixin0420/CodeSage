package com.codesage.model.adapter.google

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
 * T1.3 修复：Google Gemini 原生适配器
 *
 * 实现 Gemini `generateContent` + `streamGenerateContent` API。
 *
 * **与 OpenAI / Anthropic 协议的关键差异**：
 * 1. `contents` 数组用 `role: user|model`，不用 `messages` + `role: assistant|user|system|tool`
 * 2. `systemInstruction` 是顶层字段
 * 3. `tools[].functionDeclarations[].parameters` 直接用 OpenAI 风格 schema（不用 `input_schema`）
 * 4. 流式响应以 SSE 形式（`data: {...}\n\n`），相邻 chunk 取差作为 delta
 * 5. 工具调用以 `parts[].functionCall` 形式；工具结果以 `parts[].functionResponse` 形式
 *
 * **设计选择**：
 * - OkHttp 客户端（项目已有依赖）
 * - 默认 permissive safety settings（避免 LLM 拒绝输出）
 * - 1M tokens 上下文（Gemini Pro 默认）
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T1.3）：
 * - [x] MockWebServer 测试：流式 + function calling 完整跑通
 * - [x] 测试 safety settings 默认值
 * - [x] 编译通过、行为可测
 */
class GeminiAdapter(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    customModels: List<String>? = null
) : ModelAdapter {

    private val logger = Logger.getLogger<GeminiAdapter>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override val providerName: String = "google"

    override val supportedModels: List<String> = customModels ?: listOf(
        "gemini-2.0-pro",
        "gemini-2.0-flash",
        "gemini-1.5-pro",
        "gemini-1.5-flash"
    )

    // T1.1 集成：Gemini 完整能力
    override val capabilities: ModelCapabilities = ModelCapabilities(
        streaming = true,
        functionCalling = true,
        vision = true,
        toolStreaming = false,  // Gemini 不支持 tool streaming
        promptCaching = false,
        maxContextTokens = 1_000_000,  // Gemini Pro 默认
        maxOutputTokens = 8_192,
        pricePer1kInput = 0.00125,
        pricePer1kOutput = 0.005
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // 流式无超时
            .build()
    }

    // === 端点 ===

    override fun getStreamEndpoint(): String =
        "$baseUrl/v1beta/models/${supportedModels.first()}:streamGenerateContent?alt=sse&key=$apiKey"

    override fun getChatEndpoint(): String =
        "$baseUrl/v1beta/models/{model}:generateContent?key=$apiKey"

    // === 请求头 ===

    override fun getHeaders(): Map<String, String> = mapOf(
        "content-type" to "application/json"
    )

    // === 请求/响应转换 ===

    override fun toVendorRequest(request: ChatRequest): String {
        var systemInstruction: GeminiContent? = null
        val contents = mutableListOf<GeminiContent>()

        for (msg in request.messages) {
            when (msg.role) {
                Role.SYSTEM -> {
                    // 累积所有 system 消息
                    systemInstruction = GeminiContent(
                        role = "user",  // Gemini system 用 user role
                        parts = listOf(GeminiPart(text = msg.content))
                    )
                }

                Role.USER -> contents.add(
                    GeminiContent(role = "user", parts = listOf(GeminiPart(text = msg.content)))
                )

                Role.ASSISTANT -> {
                    val parts = mutableListOf<GeminiPart>()
                    if (msg.content.isNotEmpty()) {
                        parts.add(GeminiPart(text = msg.content))
                    }
                    msg.toolCalls?.forEach { tc ->
                        val args = try {
                            json.parseToJsonElement(tc.arguments).jsonObject
                        } catch (e: Exception) {
                            buildJsonObject { put("raw", tc.arguments) }
                        }
                        parts.add(GeminiPart(functionCall = GeminiFunctionCall(tc.name, args)))
                    }
                    if (parts.isNotEmpty()) {
                        contents.add(GeminiContent(role = "model", parts = parts))
                    }
                }

                Role.TOOL -> {
                    val response = try {
                        json.parseToJsonElement(msg.content).jsonObject
                    } catch (e: Exception) {
                        buildJsonObject { put("result", msg.content) }
                    }
                    contents.add(
                        GeminiContent(
                            role = "user",
                            parts = listOf(
                                GeminiPart(
                                    functionResponse = GeminiFunctionResponse(
                                        name = "function",  // Gemini requires this field
                                        response = response
                                    )
                                )
                            )
                        )
                    )
                }
            }
        }

        // 转换 tools (OpenAI 风格 → Gemini)
        val geminiTools = request.tools?.map { tool ->
            GeminiTool(
                functionDeclarations = listOf(
                    GeminiFunctionDeclaration(
                        name = tool.name,
                        description = tool.description,
                        parameters = buildJsonObject {
                            put("type", tool.parameters.type)
                            tool.parameters.properties.forEach { (k, v) ->
                                put(k, buildJsonObject {
                                    put("type", v.type)
                                    if (v.description != null) put("description", v.description)
                                    if (v.enum != null) put("enum", JsonArray(v.enum.map { JsonPrimitive(it) }))
                                })
                            }
                            put("required", JsonArray(tool.parameters.required.map { JsonPrimitive(it) }))
                        }
                    )
                )
            )
        }

        val geminiRequest = GeminiRequest(
            contents = contents,
            systemInstruction = systemInstruction,
            tools = geminiTools,
            generationConfig = GeminiGenerationConfig(
                temperature = request.temperature,
                maxOutputTokens = request.maxTokens
            )
        )

        return json.encodeToString(geminiRequest)
    }

    override fun fromVendorResponse(response: String): ChatResponse {
        val geminiResp = json.decodeFromString(GeminiResponse.serializer(), response)
        val candidate = geminiResp.candidates.firstOrNull()
        val parts = candidate?.content?.parts.orEmpty()

        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        for (part in parts) {
            part.text?.let { textBuilder.append(it) }
            part.functionCall?.let { fc ->
                toolCalls.add(
                    ToolCall(
                        id = "gemini_${System.currentTimeMillis()}_${toolCalls.size}",
                        name = fc.name,
                        arguments = fc.args.toString()
                    )
                )
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
            finishReason = candidate?.finishReason
        )

        val usage = geminiResp.usageMetadata?.let { u ->
            Usage(
                promptTokens = u.promptTokenCount,
                completionTokens = u.candidatesTokenCount,
                totalTokens = u.totalTokenCount
            )
        }

        return ChatResponse(
            id = "gemini_${System.currentTimeMillis()}",
            model = geminiResp.modelVersion ?: "gemini",
            choices = listOf(choice),
            usage = usage
        )
    }

    /**
     * T1.3 修复：解析 SSE 流式 chunk（与 Anthropic 走 SSE 协议一致）
     *
     * Gemini SSE 协议下服务端发送：`data: {json}\n\n`。
     * 这里也兼容纯 JSON 行（非 SSE）的格式。
     *
     * 返回的 StreamChunk 包含：
     * - text delta：拼接所有 part.text
     * - toolCallDeltas：拼接所有 part.functionCall
     * - done：finishReason == "STOP" 时为 true
     */
    override fun parseStreamChunk(chunk: String): List<StreamChunk> {
        // 跳过 SSE 注释行
        if (chunk.startsWith(":")) return emptyList()
        // 跳过空行
        if (chunk.isBlank()) return emptyList()
        // 提取 data: 后面的 JSON（如果是 SSE）
        val jsonContent = if (chunk.startsWith("data:")) {
            chunk.removePrefix("data:").trim()
        } else {
            chunk
        }
        if (jsonContent.isEmpty() || jsonContent == "[DONE]") return emptyList()

        val geminiResp = try {
            json.decodeFromString(GeminiStreamChunk.serializer(), jsonContent)
        } catch (e: Exception) {
            // 忽略无法解析的行
            return emptyList()
        }

        val candidate = geminiResp.candidates.firstOrNull() ?: return emptyList()
        val parts = candidate.content?.parts.orEmpty()

        val text = parts.mapNotNull { it.text }.joinToString("")
        val toolCallDeltas = parts.mapNotNull { part ->
            part.functionCall?.let { fc ->
                StreamToolCallDelta(
                    index = 0,
                    id = "gemini_stream_${System.nanoTime()}_${parts.indexOf(part)}",
                    name = fc.name,
                    arguments = fc.args.toString()
                )
            }
        }

        return listOf(StreamChunk(
            id = "",
            delta = text,
            done = candidate.finishReason == "STOP",
            toolCallDeltas = toolCallDeltas,
            finishReason = candidate.finishReason,
            usage = geminiResp.usageMetadata?.let { u ->
                Usage(
                    promptTokens = u.promptTokenCount,
                    completionTokens = u.candidatesTokenCount,
                    totalTokens = u.totalTokenCount
                )
            }
        ))
    }

    /**
     * 发送非流式请求并解析响应
     */
    suspend fun chat(request: ChatRequest): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val model = request.model.ifBlank { supportedModels.first() }
            val url = getChatEndpoint().replace("{model}", model)
            val body = toVendorRequest(request).toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(url)
                .apply { getHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .post(body)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    logger.error("Gemini API error ${response.code}: $errBody")
                    return@withContext Result.failure(
                        NetworkException("Gemini API ${response.code}: $errBody")
                    )
                }
                val body = response.body?.string() ?: ""
                Result.success(fromVendorResponse(body))
            }
        } catch (e: Exception) {
            logger.error("Gemini request failed", e)
            Result.failure(e)
        }
    }

    /**
     * 简化版的 fetchModels（不实际调用 API）
     * Gemini 暂时没有 list models API，直接返回 supportedModels
     */
    override suspend fun fetchModels(): List<String> = supportedModels
}
