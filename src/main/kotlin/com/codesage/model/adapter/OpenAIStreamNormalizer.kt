@file:Suppress("DEPRECATION")

package com.codesage.model.adapter

import com.codesage.model.dto.CodeBlockEvent
import com.codesage.model.dto.FinishReason
import com.codesage.model.dto.Usage
import com.codesage.shared.utils.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 2026-06: OpenAI 兼容协议的 [StreamEventNormalizer]。
 *
 * 把 OpenAI 兼容的 SSE JSON 行 (`data: {json}`) 归一为 [StreamEvent] 序列。
 * 内部维护两个跨 chunk 状态机:
 *   - [FencedCodeSplitter] 识别 ``` / ~~~ 围栏
 *   - [splitThinkTag] 处理 MiniMax-M3 / Qwen / DeepSeek 的 <think> 标签
 *
 * 替代旧 [OpenAICompatibleAdapter.parseStreamChunk] 中直接 emit `List<StreamChunk>` 的实现。
 *
 * 关键差异(协议层):
 *   - reasoning 字段名不统一: OpenAI 兼容 provider 之间命名差异,本 normalizer
 *     按 `reasoning_content > reasoning > thinking` 优先级取第一个非空
 *   - tool_calls 增量累积(原始协议就是流式,无需等 stop)
 *   - code block 用 FencedCodeSplitter 状态机(协议层无原生, 靠 围栏字符识别)
 *
 * 设计依据: docs/refactor/StreamChunk中转层重构-2026-06-16-02.md §2.4
 */
class OpenAIStreamNormalizer(
    private val providerName: String = "openai-compatible",
) : StreamEventNormalizer() {

    private val logger = Logger.getLogger<OpenAIStreamNormalizer>()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // === 跨 chunk 状态(对外可观察) ===
    /** <think> 块状态机: 当前是否在 think 块内 */
    private var inThinkBlock: Boolean = false
    /** 跨 chunk 累积 think 文本(模型可能拆 chunk 推) */
    private val pendingThink: StringBuilder = StringBuilder()
    /** fenced code block 状态机 */
    private val fencedCodeSplitter = FencedCodeSplitter()
    /** 首块 dump 标志 */
    private var firstChunkDumped: Boolean = false

    /**
     * 重置所有跨 chunk 状态机(每轮流开始时由 ModelGateway 调一次)。
     */
    fun reset() {
        inThinkBlock = false
        pendingThink.setLength(0)
        firstChunkDumped = false
        fencedCodeSplitter.reset()
    }

    /**
     * 把一行 SSE 数据归一为 0..N 个 [StreamEvent]。
     *
     * 入参 [line]: 整行,OpenAI 形态为 `data: {...}` 或 `data: [DONE]`。
     * 状态 [state]: 跨行累积, 流式期间同一个 normalizer 实例 + state 复用。
     */
    override fun normalize(line: String, state: StreamState): List<StreamEvent> {
        if (!line.startsWith("data:")) return emptyList()
        val jsonStr = line.removePrefix("data:").trim()
        if (jsonStr.isEmpty()) return emptyList()
        if (jsonStr == "[DONE]") {
            val finalEvents = mutableListOf<StreamEvent>()
            for (cbEvent in fencedCodeSplitter.flush()) {
                finalEvents += mapCodeBlockEvent(cbEvent)
            }
            finalEvents += StreamEvent.Flow.Finished(finishReason = FinishReason.STOP)
            return finalEvents
        }

        val streamData = try {
            json.decodeFromString<NormStreamData>(jsonStr)
        } catch (e: Exception) {
            return emptyList()
        }
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

        // === reasoning 提取 ===
        val dedicatedReasoning: String? = choice?.delta?.let { d ->
            listOf(d.reasoningContent, d.reasoning, d.thinking).firstOrNull { !it.isNullOrBlank() }
        }
        val (reasoningDelta, reasoningParsedDelta) = if (dedicatedReasoning != null) {
            Pair(dedicatedReasoning, rawDelta)
        } else if (rawDelta.contains("<") && (inThinkBlock || rawDelta.contains("<think>") || rawDelta.contains("</think>"))) {
            val (r, c) = splitThinkTag(rawDelta)
            Pair(r, c)
        } else if (inThinkBlock) {
            pendingThink.append(rawDelta)
            Pair(rawDelta, "")
        } else {
            Pair(null, rawDelta)
        }

        // === 围栏状态机 ===
        val (textDelta, codeBlockEvents) = if (reasoningDelta != null) {
            Pair(reasoningParsedDelta, emptyList<CodeBlockEvent>())
        } else {
            val result = fencedCodeSplitter.feed(reasoningParsedDelta)
            Pair(result.text, result.events)
        }

        val events = mutableListOf<StreamEvent>()

        if (reasoningDelta != null && reasoningDelta.isNotEmpty()) {
            events += StreamEvent.Content.Reasoning(delta = reasoningDelta)
        }
        if (codeBlockEvents.isNotEmpty()) {
            for (cb in codeBlockEvents) {
                events += mapCodeBlockEvent(cb)
            }
        } else if (textDelta.isNotEmpty()) {
            events += StreamEvent.Content.Text(delta = textDelta)
        }

        // tool calls 增量
        val toolCallDeltas = choice?.delta?.toolCalls ?: emptyList()
        for (tcDelta in toolCallDeltas) {
            events += StreamEvent.ToolCall.Delta(
                toolCallId = tcDelta.id ?: "",
                toolName = tcDelta.function?.name,
                argumentsFragment = tcDelta.function?.arguments ?: "",
            )
        }

        // finish reason
        val finishReason = choice?.finishReason
        val usage = streamData.usage?.let { u: NormUsage ->
            Usage(
                promptTokens = u.promptTokens,
                completionTokens = u.completionTokens,
                totalTokens = u.totalTokens
            )
        }
        if (finishReason != null) {
            events += StreamEvent.Flow.Finished(
                finishReason = FinishReason.from(finishReason),
                usage = usage,
            )
        } else if (usage != null) {
            events += StreamEvent.Flow.Finished(
                finishReason = FinishReason.UNKNOWN,
                usage = usage,
            )
        }

        return events
    }

    /**
     * 流结束兜底 — flush 围栏状态机可能残留的 events。
     */
    override fun onStreamEnd(state: StreamState): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        for (cb in fencedCodeSplitter.flush()) {
            events += mapCodeBlockEvent(cb)
        }
        return events
    }

    /**
     * 把 [CodeBlockEvent] (DTO 阶段, 仍被 [FencedCodeSplitter] 复用) 映射为
     * [StreamEvent.CodeBlock]。
     */
    private fun mapCodeBlockEvent(cb: CodeBlockEvent): StreamEvent.CodeBlock = when (cb) {
        is CodeBlockEvent.Start -> StreamEvent.CodeBlock.Started(
            codeBlockId = cb.codeBlockId,
            language = cb.language,
        )
        is CodeBlockEvent.Delta -> StreamEvent.CodeBlock.Delta(
            codeBlockId = cb.codeBlockId,
            delta = cb.delta,
        )
        is CodeBlockEvent.End -> StreamEvent.CodeBlock.Ended(
            codeBlockId = cb.codeBlockId,
        )
    }

    /**
     * splitThinkTag: 跨 chunk 累积 <think> 块内容。
     * 同旧 [OpenAICompatibleAdapter.splitThinkTag] 算法。
     */
    private fun splitThinkTag(rawDelta: String): Pair<String?, String> {
        if (rawDelta.isEmpty()) return Pair(null, rawDelta)
        val reasoning = StringBuilder()
        val content = StringBuilder()
        var i = 0
        val s = rawDelta
        while (i < s.length) {
            if (!inThinkBlock) {
                if (s.startsWith("<think>", i)) {
                    inThinkBlock = true
                    i += "<think>".length
                } else {
                    content.append(s[i])
                    i++
                }
            } else {
                if (s.startsWith("</think>", i)) {
                    inThinkBlock = false
                    i += "</think>".length
                } else {
                    reasoning.append(s[i])
                    i++
                }
            }
        }
        if (inThinkBlock) {
            pendingThink.append(reasoning)
            return Pair(null, content.toString())
        } else {
            val combined = pendingThink.toString() + reasoning.toString()
            pendingThink.setLength(0)
            return Pair(combined.takeIf { it.isNotEmpty() }, content.toString())
        }
    }
}

// === OpenAI 兼容协议 DTOs (本 commit 复刻, 因 [VendorStreamData] 在 OpenAICompatibleAdapter
//     内部为 private, 这里独立定义以保持 normalizer 自包含) ===

@Serializable
private data class NormStreamData(
    val id: String,
    val choices: List<NormStreamChoice>,
    val usage: NormUsage? = null,
)

@Serializable
private data class NormStreamChoice(
    val delta: NormDelta,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
private data class NormDelta(
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    val reasoning: String? = null,
    val thinking: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<NormToolCallDelta>? = null,
)

@Serializable
private data class NormToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    @SerialName("function")
    val function: NormFunctionCallDelta? = null,
)

@Serializable
private data class NormFunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
private data class NormUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
)
