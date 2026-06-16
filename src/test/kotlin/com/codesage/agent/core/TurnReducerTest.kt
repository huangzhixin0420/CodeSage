package com.codesage.agent.core

import com.codesage.model.adapter.StreamEvent
import com.codesage.model.dto.FinishReason
import com.codesage.model.dto.Usage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 2026-06: TurnReducer 纯函数 table-driven 测试。
 *
 * 每个 case:
 *   - 给定 initial state
 *   - 喂一个 StreamEvent
 *   - 断言 (newState, effects)
 */
class TurnReducerTest {

    private val reducer = TurnReducer()

    @Test
    fun `Content_Text appends to assistantText and emits TextDelta`() {
        val state = TurnState()
        val (newState, effects) = reducer.reduce(state, StreamEvent.Content.Text(delta = "Hello"))
        assertEquals("Hello", newState.assistantTextString())
        assertEquals(1, effects.size)
        assertEquals(AgentStreamEvent.TextDelta("Hello"), effects[0])
    }

    @Test
    fun `Content_Reasoning first delta emits ModelReasoningRoundStart then ModelReasoning`() {
        val state = TurnState()
        val (_, effects) = reducer.reduce(state, StreamEvent.Content.Reasoning(delta = "thinking..."))
        assertEquals(2, effects.size)
        assertTrue(effects[0] is AgentStreamEvent.ModelReasoningRoundStart)
        assertEquals(AgentStreamEvent.ModelReasoning("thinking..."), effects[1])
    }

    @Test
    fun `Content_Reasoning subsequent deltas do not re-emit RoundStart`() {
        val state = TurnState(roundReasoningStarted = true)
        val (_, effects) = reducer.reduce(state, StreamEvent.Content.Reasoning(delta = "more"))
        assertEquals(1, effects.size)
        assertEquals(AgentStreamEvent.ModelReasoning("more"), effects[0])
    }

    @Test
    fun `ToolCall_Delta with id and name emits ToolCallStart`() {
        val state = TurnState()
        val (_, effects) = reducer.reduce(
            state,
            StreamEvent.ToolCall.Delta(
                toolCallId = "tc1",
                toolName = "foo",
                argumentsFragment = "{\"a\":",
            )
        )
        assertTrue(effects.any { it is AgentStreamEvent.ToolCallStart })
        assertTrue(effects.any { it is AgentStreamEvent.ToolCallDelta })
    }

    @Test
    fun `ToolCall_Delta accumulates arguments across multiple deltas`() {
        val state = TurnState()
        val r1 = reducer.reduce(state, StreamEvent.ToolCall.Delta(
            toolCallId = "tc1", toolName = "foo", argumentsFragment = "{\"a\":"))
        val r2 = reducer.reduce(r1.first, StreamEvent.ToolCall.Delta(
            toolCallId = "tc1", toolName = null, argumentsFragment = "1}"))
        assertEquals(1, r2.first.toolCalls.size)
        assertEquals("{\"a\":1}", r2.first.toolCalls["tc1"]!!.arguments.toString())
    }

    @Test
    fun `CodeBlock_Started emits CodeBlockStart and registers in state`() {
        val state = TurnState()
        val (newState, effects) = reducer.reduce(
            state, StreamEvent.CodeBlock.Started(codeBlockId = "cb-1", language = "kotlin")
        )
        assertEquals(1, effects.size)
        assertEquals(AgentStreamEvent.CodeBlockStart("cb-1", "kotlin"), effects[0])
        assertTrue(newState.codeBlocks.containsKey("cb-1"))
    }

    @Test
    fun `CodeBlock_Delta accumulates code into builder`() {
        val state = TurnState(codeBlocks = mutableMapOf("cb-1" to CodeBlockBuilder("cb-1", "kotlin")))
        val (newState, effects) = reducer.reduce(
            state, StreamEvent.CodeBlock.Delta(codeBlockId = "cb-1", delta = "fun a() {}\n")
        )
        assertEquals(1, effects.size)
        assertEquals(AgentStreamEvent.CodeBlockDelta("cb-1", "fun a() {}\n"), effects[0])
        assertEquals("fun a() {}\n", newState.codeBlocks["cb-1"]!!.text())
    }

    @Test
    fun `CodeBlock_Ended emits CodeBlockEnd and removes from state`() {
        val state = TurnState(codeBlocks = mutableMapOf("cb-1" to CodeBlockBuilder("cb-1", "kotlin")))
        val (newState, effects) = reducer.reduce(
            state, StreamEvent.CodeBlock.Ended(codeBlockId = "cb-1")
        )
        assertEquals(1, effects.size)
        assertEquals(AgentStreamEvent.CodeBlockEnd("cb-1"), effects[0])
        assertFalse(newState.codeBlocks.containsKey("cb-1"))
    }

    @Test
    fun `Flow_Finished emits Done and sets finishedReason`() {
        val state = TurnState(assistantText = StringBuilder("Hello"))
        val (newState, effects) = reducer.reduce(
            state,
            StreamEvent.Flow.Finished(
                finishReason = FinishReason.STOP,
                usage = Usage(promptTokens = 5, completionTokens = 10, totalTokens = 15)
            )
        )
        assertTrue(effects.any { it is AgentStreamEvent.Done })
        assertEquals(FinishReason.STOP, newState.finishedReason)
        assertNotNull(newState.usage)
        assertEquals(15, newState.usage!!.totalTokens)
    }

    @Test
    fun `Flow_Finished emits ModelReasoningRoundEnd if round was started but not closed`() {
        val state = TurnState(roundReasoningStarted = true)
        val (_, effects) = reducer.reduce(
            state, StreamEvent.Flow.Finished(finishReason = FinishReason.STOP)
        )
        assertTrue(effects.any { it is AgentStreamEvent.ModelReasoningRoundEnd })
        assertTrue(effects.any { it is AgentStreamEvent.Done })
    }

    @Test
    fun `Flow_Finished emits CodeBlockEnd for any open code blocks`() {
        val state = TurnState(
            codeBlocks = mutableMapOf("cb-1" to CodeBlockBuilder("cb-1", "kotlin").apply { /* open by default */ })
        )
        val (_, effects) = reducer.reduce(
            state, StreamEvent.Flow.Finished(finishReason = FinishReason.STOP)
        )
        assertTrue(effects.any { it is AgentStreamEvent.CodeBlockEnd })
    }

    @Test
    fun `Flow_Cancelled emits Done`() {
        val state = TurnState()
        val (_, effects) = reducer.reduce(state, StreamEvent.Flow.Cancelled())
        assertEquals(1, effects.size)
        assertEquals(AgentStreamEvent.Done, effects[0])
    }

    @Test
    fun `Flow_Error emits Error event`() {
        val state = TurnState()
        val (_, effects) = reducer.reduce(
            state, StreamEvent.Flow.Error(message = "rate limit")
        )
        assertEquals(1, effects.size)
        assertEquals(AgentStreamEvent.Error("rate limit"), effects[0])
    }

    @Test
    fun `Content_PlanStep accumulates to builder (no AgentStreamEvent emit yet)`() {
        val state = TurnState()
        val (newState, effects) = reducer.reduce(
            state, StreamEvent.Content.PlanStep(delta = "Step 1: foo", stepIndex = 0)
        )
        // PlanStep 是顶层 data class,本版本 reducer 收到时只累积, 不 emit AgentStreamEvent
        // 未来 PlanStep 跨协议适配时再 emit (见 future-tasks/PlanStep-跨协议适配.md)
        assertEquals(0, effects.size)
        assertEquals(1, newState.planSteps.size)
        assertEquals("Step 1: foo", newState.planSteps[0].text())
    }

    @Test
    fun `Citation_Delta is ignored (placeholder for future)`() {
        val state = TurnState()
        val (_, effects) = reducer.reduce(
            state,
            StreamEvent.Citation.Delta(sourceId = "src1", snippetFragment = "snippet")
        )
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `Media_ImageFragment is ignored (placeholder for future)`() {
        val state = TurnState()
        val (_, effects) = reducer.reduce(
            state,
            StreamEvent.Media.ImageFragment(mimeType = "image/png", data = byteArrayOf(1, 2, 3))
        )
        assertTrue(effects.isEmpty())
    }
}
