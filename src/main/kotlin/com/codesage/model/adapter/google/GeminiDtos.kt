package com.codesage.model.adapter.google

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * T1.3 修复：Google Gemini API DTO
 *
 * 参考：https://ai.google.dev/api/generate-content
 *
 * 与 OpenAI / Anthropic 协议的关键差异：
 * 1. `contents` 数组（不是 `messages`）
 * 2. `systemInstruction` 单独字段
 * 3. `tools[].functionDeclarations`（不是 `tools[].function`）
 * 4. 流式响应使用 SSE + 嵌套的 `parts` 数组
 * 5. 工具调用以 `functionCall` part 形式
 * 6. `safetySettings` 数组（默认 permissive）
 */
@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerialName("systemInstruction")
    val systemInstruction: GeminiContent? = null,
    val tools: List<GeminiTool>? = null,
    @SerialName("generationConfig")
    val generationConfig: GeminiGenerationConfig = GeminiGenerationConfig(),
    @SerialName("safetySettings")
    val safetySettings: List<GeminiSafetySetting> = DEFAULT_SAFETY_SETTINGS
) {
    companion object {
        /**
         * 默认 permissive 安全设置
         * (Gemini 默认会 block 一些内容，permissive 让 LLM 自由输出)
         */
        val DEFAULT_SAFETY_SETTINGS = listOf(
            GeminiSafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_NONE"),
            GeminiSafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_NONE"),
            GeminiSafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_NONE"),
            GeminiSafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_NONE")
        )
    }
}

@Serializable
data class GeminiContent(
    val role: String,  // "user" | "model"
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    @SerialName("functionCall")
    val functionCall: GeminiFunctionCall? = null,
    @SerialName("functionResponse")
    val functionResponse: GeminiFunctionResponse? = null
)

@Serializable
data class GeminiFunctionCall(
    val name: String,
    val args: JsonObject
)

@Serializable
data class GeminiFunctionResponse(
    val name: String,
    val response: JsonObject
)

@Serializable
data class GeminiTool(
    @SerialName("functionDeclarations")
    val functionDeclarations: List<GeminiFunctionDeclaration>
)

@Serializable
data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject  // OpenAI 风格 schema
)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double? = null,
    @SerialName("maxOutputTokens")
    val maxOutputTokens: Int? = null,
    @SerialName("topP")
    val topP: Double? = null,
    @SerialName("topK")
    val topK: Int? = null
)

@Serializable
data class GeminiSafetySetting(
    val category: String,
    val threshold: String
)

// region === 响应 ===

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    @SerialName("usageMetadata")
    val usageMetadata: GeminiUsage? = null,
    val modelVersion: String? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    @SerialName("finishReason")
    val finishReason: String? = null,
    val index: Int = 0
)

@Serializable
data class GeminiUsage(
    @SerialName("promptTokenCount")
    val promptTokenCount: Int = 0,
    @SerialName("candidatesTokenCount")
    val candidatesTokenCount: Int = 0,
    @SerialName("totalTokenCount")
    val totalTokenCount: Int = 0
)

// region === 流式 ===

/**
 * 流式响应以多行 JSON 形式返回，每行一个 GeminiResponse。
 * 实际"delta"语义由客户端计算：相邻两个 response 的 content.parts 取差。
 */
@Serializable
data class GeminiStreamChunk(
    val candidates: List<GeminiCandidate> = emptyList(),
    @SerialName("usageMetadata")
    val usageMetadata: GeminiUsage? = null
)
