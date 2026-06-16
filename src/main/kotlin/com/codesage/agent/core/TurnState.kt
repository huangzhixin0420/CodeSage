package com.codesage.agent.core

import com.codesage.model.dto.FinishReason
import com.codesage.model.dto.Usage

/**
 * 2026-06: 当前 turn 的累积状态(纯数据,无逻辑)。
 *
 * 由 [TurnReducer] 持有,每次 reduce 返回新 state(state machine 数据部分)。
 * 全部字段都是 mutable — 这是 reducer 风格的 state 累积,性能上避免每次分配新对象。
 * 真正的"immutable"由 [TurnReducer.reduce] 函数保证(state 进出闭环)。
 *
 * 多候选 n>1 决策(路线 A): 不在本类里引入 Map<choiceIndex, TurnState>。
 * 协议层 n=1 强制 choiceIndex=0,本类保持单 state,避免 1 个 Map 的开销。
 * 未来任务: docs/refactor/future-tasks/多候选响应-n1协议层暴露.md
 *
 * 设计依据: docs/refactor/StreamChunk中转层重构-2026-06-16-02.md §2.5
 */
data class TurnState(
    val assistantText: StringBuilder = StringBuilder(),
    val toolCalls: MutableMap<String, ToolCallBuilder> = mutableMapOf(),
    val codeBlocks: MutableMap<String, CodeBlockBuilder> = mutableMapOf(),
    val planSteps: MutableList<PlanStepBuilder> = mutableListOf(),
    var roundReasoningStarted: Boolean = false,
    val finishedReason: FinishReason? = null,
    val usage: Usage? = null,
    // 未来扩展位(本版本不实现, 树里留位置)
    // val citations: MutableMap<String, CitationBuilder> = mutableMapOf(),
) {
    /**
     * 当前 assistant 文本(供下游读取)。
     */
    fun assistantTextString(): String = assistantText.toString()
}
