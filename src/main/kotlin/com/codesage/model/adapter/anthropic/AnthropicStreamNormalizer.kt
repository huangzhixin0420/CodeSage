@file:Suppress("DEPRECATION")

package com.codesage.model.adapter.anthropic

import com.codesage.model.adapter.StreamEvent
import com.codesage.model.adapter.StreamEventNormalizer
import com.codesage.model.adapter.normalizeFinishReason
import com.codesage.model.adapter.normalizeUsage
import com.codesage.model.dto.FinishReason
import com.codesage.model.dto.StreamChunk
import com.codesage.model.dto.StreamToolCallDelta
import com.codesage.shared.utils.Logger
import kotlinx.serialization.json.Json

/**
 * 2026-06: Anthropic Messages API 的 [StreamEventNormalizer]。
 *
 * 直接消费 Anthropic 结构化 SSE 事件(`content_block_start/delta/stop`),
 * 归一为 [StreamEvent] 序列。利用了 Anthropic 协议层原生结构 — 不再走
 * 旧 [AnthropicStreamParser] 的"字符串解析 + StreamChunk 中转"路径。
 *
 * 设计要点(本版本相对旧 parser 的关键升级):
 *   1. content_block_delta.text_delta      → Content.Text
 *   2. content_block_delta.thinking_delta   → Content.Reasoning
 *   3. content_block_delta.input_json_delta → 累积到 state.pendingToolInputs
 *   4. content_block_stop                   → 一次性 emit ToolCall.Delta
 *   5. message_delta                        → Flow.Finished(finishReason, usage)
 *   6. message_stop                         → Flow.Finished(STOP)
 *   7. error                                → Flow.Error
 *
 * 设计依据: docs/refactor/StreamChunk中转层重构-2026-06-16-02.md §2.4
 */
class AnthropicStreamNormalizer : StreamEventNormalizer() {

    private val logger = Logger.getLogger<AnthropicStreamNormalizer>()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * 校验 tool id 是否是 Anthropic 接受的格式。
     * 规则: 1~64 字符, 仅 [A-Za-z0-9_-]。
     * 不匹配则 Anthropic API 返 "tool call id is invalid (2013)"。
     */
    private fun isValidToolId(id: String): Boolean {
        if (id.isEmpty() || id.length > 64) return false
        return id.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    override fun normalize(line: String, state: StreamState): List<StreamEvent> {
        val event = try {
            json.decodeFromString(AnthropicStreamEvent.serializer(), line)
        } catch (e: Exception) {
            return emptyList()
        }

        return when (event.type) {
            "message_start" -> {
                val messageId = event.message?.id ?: "msg_${System.currentTimeMillis()}"
                // 复用 state 持有 messageId(本版本只用于日志, 未来可扩展)
                // 此处 emit Flow.Started 表明流开启
                listOf<StreamEvent>(StreamEvent.Flow.Started())
                    .also { /* messageId 一栏存到 state 在协议层够用时再启用 */ }
            }

            "content_block_start" -> {
                val block = event.content_block
                val index = event.index ?: 0
                if (block?.type == "tool_use") {
                    val id = block.id
                    val name = block.name
                    val idValid = id != null && isValidToolId(id)
                    val nameValid = !name.isNullOrBlank()
                    if (!idValid || !nameValid) {
                        state.toolMetas.remove(index)
                        state.pendingToolInputs.remove(index)
                        logger.warn(
                            "[AnthropicStreamNormalizer] Skipping tool_use with invalid id/name " +
                                    "(index=$index, id=${id?.take(20) ?: "null"}, name=${name?.take(20) ?: "null"})"
                        )
                        emptyList()
                    } else {
                        state.toolMetas[index] = id!! to name!!
                        state.pendingToolInputs[index] = StringBuilder()
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            "content_block_delta" -> {
                val delta = event.delta
                val index = event.index ?: 0
                when (delta?.type) {
                    "text_delta" -> {
                        val text = delta.text ?: ""
                        if (text.isEmpty()) emptyList()
                        else listOf<StreamEvent>(StreamEvent.Content.Text(delta = text))
                    }
                    "thinking_delta" -> {
                        val thinking = delta.thinking ?: ""
                        if (thinking.isEmpty()) emptyList()
                        else listOf<StreamEvent>(StreamEvent.Content.Reasoning(delta = thinking))
                    }
                    "input_json_delta" -> {
                        // 累积到 state.pendingToolInputs,等 content_block_stop 一次性 emit
                        state.pendingToolInputs
                            .getOrPut(index) { StringBuilder() }
                            .append(delta.partialJson ?: "")
                        emptyList()
                    }
                    else -> emptyList()
                }
            }

            "content_block_stop" -> {
                val index = event.index ?: 0
                val meta = state.toolMetas.remove(index)
                val inputBuilder = state.pendingToolInputs.remove(index)
                if (meta != null && inputBuilder != null) {
                    val (toolId, toolName) = meta
                    listOf<StreamEvent>(StreamEvent.ToolCall.Delta(
                        toolCallId = toolId,
                        toolName = toolName,
                        argumentsFragment = inputBuilder.toString(),
                    ))
                } else {
                    emptyList()
                }
            }

            "message_delta" -> {
                val finishReason = normalizeFinishReason(event.delta?.stopReason)
                val usage = event.usage?.let { u ->
                    normalizeUsage(u.inputTokens, u.outputTokens, null)
                }
                listOf<StreamEvent>(StreamEvent.Flow.Finished(
                    finishReason = finishReason,
                    usage = usage,
                ))
            }

            "message_stop" -> {
                listOf<StreamEvent>(StreamEvent.Flow.Finished(finishReason = FinishReason.STOP))
            }

            "ping" -> emptyList()
            "error" -> {
                listOf<StreamEvent>(StreamEvent.Flow.Error(
                    message = "Anthropic stream error: $line",
                ))
            }

            else -> emptyList()
        }
    }

    override fun onStreamEnd(state: StreamState): List<StreamEvent> {
        // Anthropic 流结束时由 message_stop 触发 Flow.Finished(STOP),本方法无需额外兜底
        return emptyList()
    }

    /**
     * 重置状态(新会话 / 新流时由 ModelGateway 调一次)。
     */
    fun reset() {
        // StreamState 由调用方持有,本方法保留为 API 兼容性而存在
    }
}
