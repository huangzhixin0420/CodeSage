package com.codesage.model.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 聊天请求
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false
)

/**
 * 聊天响应
 */
@Serializable
data class ChatResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

/**
 * 选择项
 */
@Serializable
data class Choice(
    val index: Int,
    val message: Message,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

/**
 * Token使用统计
 */
@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

/**
 * 流式工具调用增量片段
 */
data class StreamToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null
)

/**
 * 流式响应片段
 */
data class StreamChunk(
    val id: String,
    val delta: String,
    val reasoningDelta: String? = null,
    val done: Boolean = false,
    val toolCallDeltas: List<StreamToolCallDelta> = emptyList(),
    val finishReason: String? = null,
    val usage: Usage? = null
)

/**
 * 模型信息
 */
data class ModelInfo(
    val id: String,
    val provider: String,
    val displayName: String,
    val supportsStreaming: Boolean,
    val supportsFunctionCalling: Boolean,
    val supportsVision: Boolean
)
