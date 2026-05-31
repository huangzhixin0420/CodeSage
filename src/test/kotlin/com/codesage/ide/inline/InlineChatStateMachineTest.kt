package com.codesage.ide.inline

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.ide.inline.diff.DiffLine
import com.codesage.ide.inline.diff.DiffResult
import com.codesage.ide.inline.diff.DiffType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class InlineChatStateMachineTest {

    private fun createStateMachine(): InlineChatStateMachine {
        return InlineChatStateMachine(
            sessionId = "test_session",
            context = InlineChatContext(
                selectedText = "val x = 1",
                startLine = 0,
                endLine = 0,
                mode = InlineChatMode.CHAT
            )
        )
    }

    // ===== 初始化测试 =====

    @Test
    fun `should start in Idle state`() {
        val sm = createStateMachine()
        assertEquals(InlineChatStateMachine.State.Idle, sm.currentState())
        assertTrue(sm.isActive())
    }

    @Test
    fun `should have empty messages initially`() {
        val sm = createStateMachine()
        assertTrue(sm.messages.isEmpty())
    }

    @Test
    fun `should have empty diff initially`() {
        val sm = createStateMachine()
        assertEquals(DiffResult.EMPTY, sm.diffResult.value)
    }

    // ===== 状态流转：Idle → Requesting =====

    @Test
    fun `should transition from Idle to Requesting on sendRequest`() {
        val sm = createStateMachine()
        val states = mutableListOf<InlineChatStateMachine.State>()
        sm.onStateChanged = { _, new -> states.add(new) }

        val result = sm.sendRequest("Explain this code")

        assertTrue(result)
        assertEquals(InlineChatStateMachine.State.Requesting, sm.currentState())
        assertEquals(1, states.size)
        assertEquals(InlineChatStateMachine.State.Requesting, states[0])
    }

    @Test
    fun `should record user message on sendRequest`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain this code")

        assertEquals(1, sm.messages.size)
        val msg = sm.messages[0]
        assertTrue(msg is InlineChatStateMachine.ChatMessage.User)
        assertEquals("Explain this code", msg.content)
    }

    @Test
    fun `should not send request when not in Idle or Reviewing`() {
        val sm = createStateMachine()
        sm.sendRequest("First request")
        assertEquals(InlineChatStateMachine.State.Requesting, sm.currentState())

        // Should fail because already in Requesting state
        val result = sm.sendRequest("Second request")
        assertFalse(result)
        assertEquals(1, sm.messages.size)
    }

    @Test
    fun `should not send request when inactive`() {
        val sm = createStateMachine()
        sm.close()

        val result = sm.sendRequest("Explain this code")
        assertFalse(result)
    }

    // ===== 状态流转：Requesting → Streaming =====

    @Test
    fun `should transition from Requesting to Streaming on TextDelta`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")
        assertEquals(InlineChatStateMachine.State.Requesting, sm.currentState())

        val states = mutableListOf<InlineChatStateMachine.State>()
        sm.onStateChanged = { _, new -> states.add(new) }

        sm.onStreamEvent(AgentStreamEvent.TextDelta("Hello"))

        assertEquals(InlineChatStateMachine.State.Streaming, sm.currentState())
        assertTrue(states.contains(InlineChatStateMachine.State.Streaming))
    }

    @Test
    fun `should emit text delta in Streaming state`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")

        val deltas = mutableListOf<String>()
        sm.onTextDelta = { deltas.add(it) }

        sm.onStreamEvent(AgentStreamEvent.TextDelta("Hello"))
        sm.onStreamEvent(AgentStreamEvent.TextDelta(" World"))

        assertEquals(listOf("Hello", " World"), deltas)
    }

    // ===== 状态流转：Streaming → Reviewing =====

    @Test
    fun `should transition from Streaming to Reviewing on Done`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")
        sm.onStreamEvent(AgentStreamEvent.TextDelta("result"))
        assertEquals(InlineChatStateMachine.State.Streaming, sm.currentState())

        val states = mutableListOf<InlineChatStateMachine.State>()
        sm.onStateChanged = { _, new -> states.add(new) }

        sm.onStreamEvent(AgentStreamEvent.Done)

        assertEquals(InlineChatStateMachine.State.Reviewing, sm.currentState())
        assertTrue(states.contains(InlineChatStateMachine.State.Reviewing))
    }

    @Test
    fun `should transition from Requesting to Reviewing on Done`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")
        assertEquals(InlineChatStateMachine.State.Requesting, sm.currentState())

        sm.onStreamEvent(AgentStreamEvent.Done)

        assertEquals(InlineChatStateMachine.State.Reviewing, sm.currentState())
    }

    // ===== 状态流转：Reviewing → Accept =====

    @Test
    fun `should transition from Reviewing to Closed on acceptAllChanges`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")
        sm.onStreamEvent(AgentStreamEvent.Done)
        assertEquals(InlineChatStateMachine.State.Reviewing, sm.currentState())

        val states = mutableListOf<InlineChatStateMachine.State>()
        sm.onStateChanged = { _, new -> states.add(new) }

        val result = sm.acceptAllChanges()

        assertTrue(result)
        assertEquals(InlineChatStateMachine.State.Closed, sm.currentState())
        assertFalse(sm.isActive())
        assertTrue(states.contains(InlineChatStateMachine.State.Applying))
        assertTrue(states.contains(InlineChatStateMachine.State.Closed))
    }

    @Test
    fun `should not accept when not in Reviewing state`() {
        val sm = createStateMachine()
        val result = sm.acceptAllChanges()
        assertFalse(result)
    }

    // ===== 状态流转：Reviewing → Reject =====

    @Test
    fun `should transition to Closed on rejectAllChanges`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")
        sm.onStreamEvent(AgentStreamEvent.Done)

        val result = sm.rejectAllChanges()

        assertTrue(result)
        assertEquals(InlineChatStateMachine.State.Closed, sm.currentState())
        assertFalse(sm.isActive())
    }

    // ===== 错误处理 =====

    @Test
    fun `should transition to Error on stream error`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")

        val errors = mutableListOf<String>()
        sm.onError = { errors.add(it) }

        sm.onStreamEvent(AgentStreamEvent.Error("Network timeout"))

        val state = sm.currentState()
        assertTrue(state is InlineChatStateMachine.State.Error)
        assertEquals("Network timeout", (state as InlineChatStateMachine.State.Error).message)
        assertEquals(listOf("Network timeout"), errors)
    }

    @Test
    fun `should not process events after error`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")
        sm.onStreamEvent(AgentStreamEvent.Error("Network timeout"))

        assertTrue(sm.currentState() is InlineChatStateMachine.State.Error)

        // Further events should not change state
        sm.onStreamEvent(AgentStreamEvent.TextDelta("more"))
        assertTrue(sm.currentState() is InlineChatStateMachine.State.Error)
    }

    // ===== 取消请求 =====

    @Test
    fun `should cancel from Requesting state`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")
        assertEquals(InlineChatStateMachine.State.Requesting, sm.currentState())

        val result = sm.cancelRequest()

        assertTrue(result)
        assertEquals(InlineChatStateMachine.State.Idle, sm.currentState())
    }

    @Test
    fun `should cancel from Streaming state`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")
        sm.onStreamEvent(AgentStreamEvent.TextDelta("Hello"))
        assertEquals(InlineChatStateMachine.State.Streaming, sm.currentState())

        val result = sm.cancelRequest()

        assertTrue(result)
        assertEquals(InlineChatStateMachine.State.Idle, sm.currentState())
    }

    @Test
    fun `should not cancel from Idle state`() {
        val sm = createStateMachine()
        val result = sm.cancelRequest()
        assertFalse(result)
    }

    // ===== 重新生成 =====

    @Test
    fun `should retry from Reviewing state`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")
        sm.onStreamEvent(AgentStreamEvent.Done)
        assertEquals(InlineChatStateMachine.State.Reviewing, sm.currentState())

        val result = sm.retryRequest()

        assertTrue(result)
        assertEquals(InlineChatStateMachine.State.Requesting, sm.currentState())
    }

    @Test
    fun `should clear diff on retry`() {
        val sm = createStateMachine()
        sm.sendRequest("Explain")
        sm.onStreamEvent(AgentStreamEvent.Done)
        sm.updateDiffResult(DiffResult(lines = listOf(DiffLine(DiffType.ADDED, 0, "new"))))
        assertTrue(sm.diffResult.value.hasChanges)

        sm.retryRequest()

        assertFalse(sm.diffResult.value.hasChanges)
    }

    @Test
    fun `should not retry when no user message exists`() {
        val sm = createStateMachine()
        val result = sm.retryRequest()
        assertFalse(result)
    }

    // ===== Diff 管理 =====

    @Test
    fun `should update diff result`() {
        val sm = createStateMachine()
        val diffs = mutableListOf<DiffResult>()
        sm.onDiffUpdated = { diffs.add(it) }

        val result = DiffResult(
            lines = listOf(
                DiffLine(DiffType.REMOVED, 0, "old"),
                DiffLine(DiffType.ADDED, 0, "new")
            )
        )

        sm.updateDiffResult(result)

        assertEquals(result, sm.diffResult.value)
        assertEquals(1, diffs.size)
        assertEquals(result, diffs[0])
    }

    @Test
    fun `should not update diff when inactive`() {
        val sm = createStateMachine()
        sm.close()

        sm.updateDiffResult(DiffResult(lines = listOf(DiffLine(DiffType.ADDED, 0, "new"))))
        assertEquals(DiffResult.EMPTY, sm.diffResult.value)
    }

    // ===== 状态查询 =====

    @Test
    fun `canSendRequest should return true only in Idle or Reviewing`() {
        val sm = createStateMachine()
        assertTrue(sm.canSendRequest()) // Idle

        sm.sendRequest("Explain")
        assertFalse(sm.canSendRequest()) // Requesting

        sm.onStreamEvent(AgentStreamEvent.TextDelta("x"))
        assertFalse(sm.canSendRequest()) // Streaming

        sm.onStreamEvent(AgentStreamEvent.Done)
        assertTrue(sm.canSendRequest()) // Reviewing
    }

    @Test
    fun `canReview should return true only in Reviewing`() {
        val sm = createStateMachine()
        assertFalse(sm.canReview()) // Idle

        sm.sendRequest("Explain")
        assertFalse(sm.canReview()) // Requesting

        sm.onStreamEvent(AgentStreamEvent.Done)
        assertTrue(sm.canReview()) // Reviewing
    }

    @Test
    fun `canCancel should return true only in Requesting or Streaming`() {
        val sm = createStateMachine()
        assertFalse(sm.canCancel()) // Idle

        sm.sendRequest("Explain")
        assertTrue(sm.canCancel()) // Requesting

        sm.onStreamEvent(AgentStreamEvent.TextDelta("x"))
        assertTrue(sm.canCancel()) // Streaming

        sm.onStreamEvent(AgentStreamEvent.Done)
        assertFalse(sm.canCancel()) // Reviewing
    }

    // ===== 关闭 =====

    @Test
    fun `should become inactive after close`() {
        val sm = createStateMachine()
        assertTrue(sm.isActive())

        sm.close()

        assertFalse(sm.isActive())
        assertEquals(InlineChatStateMachine.State.Closed, sm.currentState())
    }

    @Test
    fun `should ignore events after close`() {
        val sm = createStateMachine()
        sm.close()

        assertFalse(sm.onStreamEvent(AgentStreamEvent.TextDelta("x")))
        assertFalse(sm.sendRequest("Explain"))
    }

    @Test
    fun `close should be idempotent`() {
        val sm = createStateMachine()
        sm.close()
        sm.close() // Should not throw
        assertEquals(InlineChatStateMachine.State.Closed, sm.currentState())
    }

    // ===== 多轮对话 =====

    @Test
    fun `should support multiple rounds in Reviewing state`() {
        val sm = createStateMachine()

        // Round 1
        sm.sendRequest("First question")
        sm.onStreamEvent(AgentStreamEvent.Done)
        assertEquals(InlineChatStateMachine.State.Reviewing, sm.currentState())

        // Round 2: can send another request from Reviewing
        val result = sm.sendRequest("Follow up")
        assertTrue(result)
        assertEquals(InlineChatStateMachine.State.Requesting, sm.currentState())
        assertEquals(2, sm.messages.size)
    }

    @Test
    fun `should track state change history`() {
        val sm = createStateMachine()
        val history = mutableListOf<Pair<InlineChatStateMachine.State, InlineChatStateMachine.State>>()
        sm.onStateChanged = { old, new -> history.add(Pair(old, new)) }

        sm.sendRequest("Explain")
        sm.onStreamEvent(AgentStreamEvent.TextDelta("x"))
        sm.onStreamEvent(AgentStreamEvent.Done)
        sm.acceptAllChanges()

        assertEquals(5, history.size)
        assertEquals(InlineChatStateMachine.State.Idle to InlineChatStateMachine.State.Requesting, history[0])
        assertEquals(InlineChatStateMachine.State.Requesting to InlineChatStateMachine.State.Streaming, history[1])
        assertEquals(InlineChatStateMachine.State.Streaming to InlineChatStateMachine.State.Reviewing, history[2])
        assertEquals(InlineChatStateMachine.State.Reviewing to InlineChatStateMachine.State.Applying, history[3])
        assertEquals(InlineChatStateMachine.State.Applying to InlineChatStateMachine.State.Closed, history[4])
    }
}
