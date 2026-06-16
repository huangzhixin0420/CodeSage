package com.codesage.agent.core

import com.codesage.model.dto.ToolCall
import com.codesage.model.adapter.StreamEvent
import com.codesage.model.dto.FinishReason
import com.codesage.shared.utils.Logger

/**
 * 2026-06: 状态机 — 输入 [TurnState] + [StreamEvent] → (newState, sideEffects)。
 *
 * 纯函数风格:
 *   - 不持有可变状态(所有累积都在 [state] 上)
 *   - 副作用以 [AgentStreamEvent] 列表返回,EnhancedAgentLoop 负责 emit
 *   - 易于 table-driven 测试:`assertEquals(expectedEffects, reduce(state, event).second)`
 *
 * 关键派生逻辑(兜底):
 *   - Flow.Finished 到达时,如果 reasoning round 还在 started,emit RoundEnd
 *   - Flow.Finished 到达时,如果仍有 open code block,emit CodeBlockEnd
 *
 * 设计依据: docs/refactor/StreamChunk中转层重构-2026-06-16-02.md §2.5
 */
class TurnReducer {

    private val logger = Logger.getLogger<TurnReducer>()

    /**
     * Reduce 一个 event, 返回 (newState, effects)。
     *
     * 注意: state 自身累积修改 (StringBuilder.append),不返回新对象。
     * 业务效果通过 [effects] 列表返回,EnhancedAgentLoop 负责 emit 给 UI。
     */
    fun reduce(
        state: TurnState,
        event: StreamEvent,
    ): Pair<TurnState, List<AgentStreamEvent>> = when (event) {
        is StreamEvent.Content.Text -> {
            state.assistantText.append(event.delta)
            state to listOf<AgentStreamEvent>(AgentStreamEvent.TextDelta(event.delta))
        }

        is StreamEvent.Content.Reasoning -> {
            val effects = mutableListOf<AgentStreamEvent>()
            if (!state.roundReasoningStarted) {
                state.roundReasoningStarted = true
                effects += AgentStreamEvent.ModelReasoningRoundStart(0)
            }
            effects += AgentStreamEvent.ModelReasoning(event.delta)
            state to effects
        }

        is StreamEvent.Content.PlanStep -> {
            // 协议层占位 — PlanStep 本版本 Normalizer 不实现,reducer 收到时只累积 builder。
            // 累积 builder 给未来 PlanStep 跨协议适配时使用(见 future-tasks/PlanStep-跨协议适配.md)。
            val stepIndex = event.stepIndex ?: state.planSteps.size
            val builder = state.planSteps.getOrNull(stepIndex)
                ?: PlanStepBuilder(stepIndex).also { state.planSteps.add(it) }
            builder.append(event.delta)
            state to emptyList<AgentStreamEvent>()
        }

        is StreamEvent.ToolCall.Delta -> {
            val builder = state.toolCalls.getOrPut(event.toolCallId) {
                ToolCallBuilder(event.toolCallId, event.toolName.orEmpty())
            }
            event.toolName?.let { builder.name = it }
            builder.arguments.append(event.argumentsFragment)

            val effects = mutableListOf<AgentStreamEvent>()
            if (event.toolName != null && builder.id.isNotEmpty()) {
                effects += AgentStreamEvent.ToolCallStart(
                    ToolCall(builder.id, builder.name, builder.arguments.toString())
                )
            }
            if (event.argumentsFragment.isNotEmpty() && builder.id.isNotEmpty() && builder.name.isNotEmpty()) {
                effects += AgentStreamEvent.ToolCallDelta(
                    builder.id, builder.name, event.argumentsFragment
                )
            }
            state to effects
        }

        is StreamEvent.CodeBlock.Started -> {
            state.codeBlocks[event.codeBlockId] = CodeBlockBuilder(event.codeBlockId, event.language)
            state to listOf<AgentStreamEvent>(
                AgentStreamEvent.CodeBlockStart(event.codeBlockId, event.language)
            )
        }

        is StreamEvent.CodeBlock.Delta -> {
            state.codeBlocks[event.codeBlockId]?.append(event.delta)
            state to listOf<AgentStreamEvent>(
                AgentStreamEvent.CodeBlockDelta(event.codeBlockId, event.delta)
            )
        }

        is StreamEvent.CodeBlock.Ended -> {
            state.codeBlocks.remove(event.codeBlockId)?.close()
            state to listOf<AgentStreamEvent>(AgentStreamEvent.CodeBlockEnd(event.codeBlockId))
        }

        is StreamEvent.Flow.Started -> state to emptyList()

        is StreamEvent.Flow.Finished -> {
            val effects = mutableListOf<AgentStreamEvent>()
            // 兜底 1: reasoning round 关闭
            if (state.roundReasoningStarted) {
                state.roundReasoningStarted = false
                effects += AgentStreamEvent.ModelReasoningRoundEnd(0)
            }
            // 兜底 2: open code block 关闭(流中断但未 Ended)
            val openBlocks = state.codeBlocks.values.filter { it.isOpen }
            for (openBlock in openBlocks) {
                openBlock.close()
                effects += AgentStreamEvent.CodeBlockEnd(openBlock.codeBlockId)  // CodeBlockEnd 需要 id 字段
            }
            effects += AgentStreamEvent.Done
            state.copy(
                finishedReason = event.finishReason,
                usage = event.usage ?: state.usage,
            ) to effects
        }

        is StreamEvent.Flow.Cancelled -> {
            state to listOf<AgentStreamEvent>(AgentStreamEvent.Done)
        }

        is StreamEvent.Flow.Error -> {
            state to listOf<AgentStreamEvent>(AgentStreamEvent.Error(event.message))
        }

        // 未来扩展位 — 本版本 reducer 不实现,占位
        is StreamEvent.Citation.Delta -> state to emptyList()
        is StreamEvent.Media.ImageFragment -> state to emptyList()
        is StreamEvent.Media.AudioFragment -> state to emptyList()
    }
}
