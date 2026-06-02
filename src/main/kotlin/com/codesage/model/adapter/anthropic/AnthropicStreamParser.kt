package com.codesage.model.adapter.anthropic

import com.codesage.model.dto.StreamChunk
import com.codesage.model.dto.StreamToolCallDelta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T1.2 修复：Anthropic SSE 事件解析器
 *
 * Anthropic 流式响应使用自定义 event types，每个事件是单独的一行 JSON。
 * 关键事件：
 * - `message_start`：消息开始，包含 message 元数据
 * - `content_block_start`：新的 content block 开始（text 或 tool_use）
 * - `content_block_delta`：text 或 input_json 增量
 * - `content_block_stop`：content block 结束
 * - `message_delta`：消息级别更新（如 stop_reason）
 * - `message_stop`：消息结束
 * - `error`：错误
 */
class AnthropicStreamParser {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // 流式累积的 tool_use input JSON
    private val toolInputs = mutableMapOf<Int, StringBuilder>()

    // 流式累积的 tool_use 元数据 (id, name)
    private val toolMetas = mutableMapOf<Int, Pair<String, String>>()

    /**
     * 解析一个 SSE event (data 行内容)
     * @return StreamChunk 增量，null 表示应跳过该事件
     */
    fun parseEvent(data: String): StreamChunk? {
        val event = try {
            json.decodeFromString(AnthropicStreamEvent.serializer(), data)
        } catch (e: Exception) {
            // 忽略无法解析的事件
            return null
        }

        return when (event.type) {
            "message_start" -> {
                // 消息开始，返回空 delta（携带 model id）
                val modelId = event.message?.model ?: "unknown"
                val messageId = event.message?.id ?: "msg_${System.currentTimeMillis()}"
                StreamChunk(id = messageId, delta = "", done = false)
            }

            "content_block_start" -> {
                val block = event.content_block
                val index = event.index ?: 0
                if (block?.type == "tool_use") {
                    // 工具调用开始，记录元数据
                    val id = block.id ?: "tool_${index}"
                    val name = block.name ?: ""
                    toolMetas[index] = id to name
                    toolInputs[index] = StringBuilder()
                }
                null
            }

            "content_block_delta" -> {
                val delta = event.delta
                val index = event.index ?: 0
                when (delta?.type) {
                    "text_delta" -> {
                        StreamChunk(
                            id = "",
                            delta = delta.text ?: "",
                            done = false
                        )
                    }

                    "input_json_delta" -> {
                        // 累积工具参数 JSON 片段
                        toolInputs.getOrPut(index) { StringBuilder() }.append(delta.partialJson ?: "")
                        // 暂不返回 stream chunk，等 content_block_stop 时一次性返回完整 tool call
                        null
                    }

                    else -> null
                }
            }

            "content_block_stop" -> {
                val index = event.index ?: 0
                val meta = toolMetas.remove(index)
                val inputBuilder = toolInputs.remove(index)
                if (meta != null && inputBuilder != null) {
                    val (toolId, toolName) = meta
                    StreamChunk(
                        id = "",
                        delta = "",
                        done = false,
                        toolCallDeltas = listOf(
                            StreamToolCallDelta(
                                index = 0,
                                id = toolId,
                                name = toolName,
                                arguments = inputBuilder.toString()
                            )
                        )
                    )
                } else {
                    null
                }
            }

            "message_delta" -> {
                // 包含 stop_reason 和 usage
                StreamChunk(
                    id = "",
                    delta = "",
                    done = false,
                    finishReason = event.delta?.stopReason
                )
            }

            "message_stop" -> {
                StreamChunk(id = "", delta = "", done = true)
            }

            "ping" -> null
            "error" -> {
                // 错误事件，让上层捕获
                throw RuntimeException("Anthropic stream error: $data")
            }

            else -> null
        }
    }

    /**
     * 重置累积器，用于新会话
     */
    fun reset() {
        toolInputs.clear()
        toolMetas.clear()
    }
}
