package com.codesage.model.adapter.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * T1.2 修复：Anthropic Messages API 的请求/响应 DTO
 *
 * 参考：https://docs.anthropic.com/claude/reference/messages_post
 *
 * 与 OpenAI 协议的关键差异：
 * 1. `system` 单独字段，不在 `messages` 数组中
 * 2. `max_tokens` 必填
 * 3. `tools` 数组用 `input_schema` 而不是 `parameters`
 * 4. 流式事件用自定义 event types
 * 5. 工具调用以 `content_block` (type=tool_use) 形式出现
 * 6. `cache_control: {type: "ephemeral"}` 标记用于 prompt caching
 */

// region === 请求 ===

@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val system: String? = null,
    val tools: List<AnthropicTool>? = null,
    val temperature: Double? = null,
    val stream: Boolean = false
)

@Serializable
data class AnthropicMessage(
    val role: String,  // "user" | "assistant"（Anthropic 不允许 system 消息在 messages 中）
    val content: JsonElement  // String (text) 或 List<ContentBlock>
)

@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema")
    val inputSchema: AnthropicInputSchema
)

@Serializable
data class AnthropicInputSchema(
    val type: String = "object",
    val properties: JsonObject = JsonObject(emptyMap()),
    val required: List<String> = emptyList()
)

// region === 响应（非流式）===

@Serializable
data class AnthropicResponse(
    val id: String? = null,  // message_start 事件中可能不提供 id
    val type: String = "message",
    val role: String = "assistant",
    val content: List<AnthropicContentBlock>? = null,  // 流式事件中可能不提供 content
    val model: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
data class AnthropicContentBlock(
    val type: String,  // "text" | "tool_use"
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null
)

@Serializable
data class AnthropicUsage(
    @SerialName("input_tokens")
    val inputTokens: Int = 0,
    @SerialName("output_tokens")
    val outputTokens: Int = 0
)

// region === 流式事件（SSE）===

@Serializable
data class AnthropicStreamEvent(
    val type: String,  // message_start / content_block_start / content_block_delta / content_block_stop / message_delta / message_stop / ping / error
    val message: AnthropicResponse? = null,
    val index: Int? = null,
    val content_block: AnthropicContentBlock? = null,
    val delta: AnthropicDelta? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
data class AnthropicDelta(
    val type: String? = null,  // text_delta / input_json_delta / thinking_delta
    val text: String? = null,
    val thinking: String? = null,
    @SerialName("partial_json")
    val partialJson: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null
)
