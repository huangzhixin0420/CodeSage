package com.codesage.model.adapter

import com.codesage.model.dto.FinishReason
import com.codesage.model.dto.Usage

/**
 * 2026-06: 协议归一器 — 把"上游 SSE 一行"归一为 0..N 个 [StreamEvent]。
 *
 * 目标:
 *   1. 利用 Anthropic 协议的结构化(content_block_* 直接透传)
 *   2. 把 OpenAI 的"字段名不统一"问题收敛到一处(reasoning_content / reasoning / thinking)
 *   3. 累积状态显式化(Anthropic tool input 跨行累积 → [StreamState])
 *
 * 设计依据: docs/refactor/StreamChunk中转层重构-2026-06-16-02.md §2.4
 *
 * 协议层 n=1 约定: 本版本 Normalizer 一律强制 `choiceIndex = 0`。
 * `Map<choiceIndex, TurnState>` 永远单元素, 不存在"全部完成判断"风险。
 * 未来任务: docs/refactor/future-tasks/多候选响应-n1协议层暴露.md
 */
abstract class StreamEventNormalizer {

    /**
     * 协议层累积状态(Anthropic tool input 跨多行累积 等场景)。
     * 与业务状态 [com.codesage.agent.core.TurnState] 解耦, Normalizer 不感知业务。
     *
     * 一个 normalizer 实例可被同一个 SSE 流的所有行共用(流式处理期间)。
     * 流转时由 [com.codesage.model.gateway.ModelGateway] 持有 / 清理。
     */
    data class StreamState(
        val messageId: String? = null,
        /** content_block 索引 → tool input JSON 累积(Anthropic) */
        val pendingToolInputs: MutableMap<Int, StringBuilder> = mutableMapOf(),
        /** content_block 索引 → (toolId, toolName) */
        val toolMetas: MutableMap<Int, Pair<String, String>> = mutableMapOf(),
        /** 当前在 code block 内的 cb id 集合(OpenAI 围栏状态机持有) */
        val openCodeBlocks: MutableSet<String> = mutableSetOf(),
        /** 是否已收到流首 chunk(用于首块 dump 日志) */
        val firstChunkSeen: Boolean = false,
    )

    /**
     * 把一行 SSE 数据归一化为 0..N 个 [StreamEvent]。
     *
     * 入参 [line] 是 SSE 协议层一行,OpenAI 形态 `data: {...}`,Anthropic 形态是
     * 整段 JSON(Anthropic 的 event 类型在 JSON 内部 `type` 字段)。
     *
     * 返回的事件已携带正确的 `choiceIndex` (本版本强制 0)。
     */
    abstract fun normalize(line: String, state: StreamState): List<StreamEvent>

    /**
     * 流关闭时调用,产出兜底事件。
     * 典型: 流中断时未闭合的 code block, splitter.flush() 兜底 emit Delta + Ended。
     */
    open fun onStreamEnd(state: StreamState): List<StreamEvent> = emptyList()
}

/**
 * 工具: 协议层 raw usage → 统一 [Usage] 归一(目前三个 provider 都返回相同结构)。
 */
internal fun normalizeUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int?): Usage {
    return Usage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens ?: (promptTokens + completionTokens)
    )
}

/**
 * 工具: 协议层 raw finishReason 字符串 → [FinishReason] 归一。
 */
internal fun normalizeFinishReason(raw: String?): FinishReason = FinishReason.from(raw)
