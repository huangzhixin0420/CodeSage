package com.codesage.model.adapter.anthropic

import com.codesage.model.adapter.StreamEvent
import com.codesage.model.adapter.StreamEventNormalizer
import com.codesage.model.dto.FinishReason
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 2026-06: AnthropicStreamNormalizer 单元测试
 *
 * 覆盖:
 *   - content_block_delta.text_delta  → Content.Text
 *   - content_block_delta.thinking_delta  → Content.Reasoning
 *   - content_block_delta.input_json_delta 累积到 state,content_block_stop 一次性 emit
 *   - message_delta stop_reason  → Flow.Finished
 *   - message_stop  → Flow.Finished(STOP)
 *   - error 事件  → Flow.Error
 *   - tool_use id 校验
 */
class AnthropicStreamNormalizerTest {

    private fun newNorm() = AnthropicStreamNormalizer()
    private fun normState() = StreamEventNormalizer.StreamState()

    @Test
    fun `text_delta event produces Content_Text`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""",
            state
        )
        assertEquals(1, events.size)
        val evt = events[0] as StreamEvent.Content.Text
        assertEquals("hi", evt.delta)
    }

    @Test
    fun `thinking_delta event produces Content_Reasoning`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"deep thought"}}""",
            state
        )
        assertEquals(1, events.size)
        val evt = events[0] as StreamEvent.Content.Reasoning
        assertEquals("deep thought", evt.delta)
    }

    @Test
    fun `input_json_delta accumulates across calls and emits ToolCall_Delta on block_stop`() {
        val norm = newNorm()
        val state = normState()
        // content_block_start
        norm.normalize(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_abc123","name":"foo"}}""",
            state
        )
        // input_json_delta x2
        val d1 = norm.normalize(
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}""",
            state
        )
        assertTrue(d1.isEmpty(), "input_json_delta should not emit before stop")
        val d2 = norm.normalize(
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"1}"}}""",
            state
        )
        assertTrue(d2.isEmpty())
        // content_block_stop → emit ToolCall.Delta
        val stop = norm.normalize(
            """{"type":"content_block_stop","index":0}""",
            state
        )
        assertEquals(1, stop.size)
        val tc = stop[0] as StreamEvent.ToolCall.Delta
        assertEquals("toolu_abc123", tc.toolCallId)
        assertEquals("foo", tc.toolName)
        assertEquals("{\"a\":1}", tc.argumentsFragment)
    }

    @Test
    fun `message_delta with stop_reason produces Flow_Finished`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":5,"output_tokens":10}}""",
            state
        )
        assertEquals(1, events.size)
        val finished = events[0] as StreamEvent.Flow.Finished
        assertEquals(FinishReason.STOP, finished.finishReason)
        assertNotNull(finished.usage)
        assertEquals(5, finished.usage!!.promptTokens)
        assertEquals(10, finished.usage!!.completionTokens)
    }

    @Test
    fun `message_stop produces Flow_Finished STOP`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            """{"type":"message_stop"}""",
            state
        )
        assertEquals(1, events.size)
        val finished = events[0] as StreamEvent.Flow.Finished
        assertEquals(FinishReason.STOP, finished.finishReason)
    }

    @Test
    fun `tool_use with invalid id is skipped`() {
        val norm = newNorm()
        val state = normState()
        // 包含反斜杠的非法 id
        val events = norm.normalize(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"bad\\id","name":"foo"}}""",
            state
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `error event produces Flow_Error`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            """{"type":"error","error":{"message":"overloaded"}}""",
            state
        )
        assertEquals(1, events.size)
        val err = events[0] as StreamEvent.Flow.Error
        assertTrue(err.message.contains("overloaded"))
    }

    @Test
    fun `ping event is ignored`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize("""{"type":"ping"}""", state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `unparseable line is ignored gracefully`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize("not a valid json line", state)
        assertTrue(events.isEmpty())
    }
}
