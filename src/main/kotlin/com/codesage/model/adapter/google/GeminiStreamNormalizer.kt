@file:Suppress("DEPRECATION")

package com.codesage.model.adapter.google

import com.codesage.model.adapter.StreamEvent
import com.codesage.model.adapter.StreamEventNormalizer
import com.codesage.model.adapter.normalizeFinishReason
import com.codesage.model.adapter.normalizeUsage
import com.codesage.model.dto.FinishReason
import kotlinx.serialization.json.Json

/**
 * 2026-06: Google Gemini API 的 [StreamEventNormalizer]。
 *
 * 消费 Gemini SSE 形态(`data: {json}`)的流式 chunk,归一为 [StreamEvent] 序列。
 * 每个 chunk 包含 candidates[0].content.parts[],按 part 类型分发:
 *   - part.text                → Content.Text
 *   - part.functionCall        → ToolCall.Delta
 *   - part.text + finishReason → Flow.Finished
 *
 * 设计依据: docs/refactor/StreamChunk中转层重构-2026-06-16-02.md §2.4
 */
class GeminiStreamNormalizer : StreamEventNormalizer() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun normalize(line: String, state: StreamState): List<StreamEvent> {
        // SSE 注释行跳过
        if (line.startsWith(":")) return emptyList()
        if (line.isBlank()) return emptyList()

        val jsonContent = if (line.startsWith("data:")) {
            line.removePrefix("data:").trim()
        } else {
            line
        }
        if (jsonContent.isEmpty() || jsonContent == "[DONE]") return emptyList()

        val geminiResp = try {
            json.decodeFromString(GeminiStreamChunk.serializer(), jsonContent)
        } catch (e: Exception) {
            return emptyList()
        }

        val candidate = geminiResp.candidates.firstOrNull() ?: return emptyList()
        val parts = candidate.content?.parts.orEmpty()

        val events = mutableListOf<StreamEvent>()

        // text delta
        val text = parts.mapNotNull { it.text }.joinToString("")
        if (text.isNotEmpty()) {
            events += StreamEvent.Content.Text(delta = text)
        }

        // tool calls (function call)
        for (part in parts) {
            val fc = part.functionCall ?: continue
            events += StreamEvent.ToolCall.Delta(
                toolCallId = "gemini_stream_${System.nanoTime()}_${parts.indexOf(part)}",
                toolName = fc.name,
                argumentsFragment = fc.args.toString(),
            )
        }

        // finish reason / usage
        val finishReason = normalizeFinishReason(candidate.finishReason)
        val usage = geminiResp.usageMetadata?.let { u ->
            normalizeUsage(u.promptTokenCount, u.candidatesTokenCount, u.totalTokenCount)
        }
        if (candidate.finishReason != null || usage != null) {
            events += StreamEvent.Flow.Finished(
                finishReason = finishReason,
                usage = usage,
            )
        }

        return events
    }

    override fun onStreamEnd(state: StreamState): List<StreamEvent> = emptyList()
}
