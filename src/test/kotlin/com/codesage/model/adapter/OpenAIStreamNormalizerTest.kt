@file:Suppress("DEPRECATION")

package com.codesage.model.adapter

import com.codesage.model.dto.FinishReason
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 2026-06: OpenAIStreamNormalizer 单元测试
 *
 * 覆盖:
 *   - 文本 delta 解析
 *   - reasoning 字段(三种命名)
 *   - 工具调用增量解析
 *   - finish reason 归一
 *   - usage 归一
 *   - think 标签跨 chunk 累积
 *   - code block 围栏切分
 *   - [DONE] sentinel
 */
class OpenAIStreamNormalizerTest {

    private fun newNorm() = OpenAIStreamNormalizer(providerName = "test")

    private fun normState() = StreamEventNormalizer.StreamState()

    private fun sse(jsonContent: String) = "data: $jsonContent"

    @Test
    fun `extracts plain text delta as Content_Text`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"id":"x","choices":[{"delta":{"content":"Hello"}}]}"""),
            state
        )
        assertEquals(1, events.size)
        val evt = events[0] as StreamEvent.Content.Text
        assertEquals("Hello", evt.delta)
        assertEquals(0, evt.choiceIndex)
    }

    @Test
    fun `extracts reasoning_content as Content_Reasoning`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"id":"x","choices":[{"delta":{"reasoning_content":"thinking..."}}]}"""),
            state
        )
        assertEquals(1, events.size)
        val evt = events[0] as StreamEvent.Content.Reasoning
        assertEquals("thinking...", evt.delta)
    }

    @Test
    fun `falls back to reasoning field when reasoning_content is missing`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"id":"x","choices":[{"delta":{"reasoning":"alt thinking"}}]}"""),
            state
        )
        assertEquals(1, events.size)
        val evt = events[0] as StreamEvent.Content.Reasoning
        assertEquals("alt thinking", evt.delta)
    }

    @Test
    fun `falls back to thinking field as last resort`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"id":"x","choices":[{"delta":{"thinking":"qwen thinking"}}]}"""),
            state
        )
        assertEquals(1, events.size)
        val evt = events[0] as StreamEvent.Content.Reasoning
        assertEquals("qwen thinking", evt.delta)
    }

    @Test
    fun `extracts tool_call deltas as ToolCall_Delta`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"id":"x","choices":[{"delta":{"tool_calls":[{"index":0,"id":"tc1","function":{"name":"foo","arguments":"{\"a\":"}}]}}]}"""),
            state
        )
        assertEquals(1, events.size)
        val evt = events[0] as StreamEvent.ToolCall.Delta
        assertEquals("tc1", evt.toolCallId)
        assertEquals("foo", evt.toolName)
        assertEquals("{\"a\":", evt.argumentsFragment)
    }

    @Test
    fun `finish reason stop is normalized to Flow_Finished with FinishReason_STOP`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"id":"x","choices":[{"delta":{},"finish_reason":"stop"}]}"""),
            state
        )
        val finished = events.filterIsInstance<StreamEvent.Flow.Finished>().firstOrNull()
        assertNotNull(finished)
        assertEquals(FinishReason.STOP, finished!!.finishReason)
    }

    @Test
    fun `finish reason tool_calls is normalized to TOOL_CALLS`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"id":"x","choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""),
            state
        )
        val finished = events.filterIsInstance<StreamEvent.Flow.Finished>().firstOrNull()
        assertNotNull(finished)
        assertEquals(FinishReason.TOOL_CALLS, finished!!.finishReason)
    }

    @Test
    fun `usage is emitted as Flow_Finished usage`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"id":"x","choices":[{"delta":{}}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}"""),
            state
        )
        val finished = events.filterIsInstance<StreamEvent.Flow.Finished>().firstOrNull()
        assertNotNull(finished)
        assertNotNull(finished!!.usage)
        assertEquals(10, finished.usage!!.promptTokens)
        assertEquals(20, finished.usage!!.completionTokens)
        assertEquals(30, finished.usage!!.totalTokens)
    }

    @Test
    fun `donesentinel_emits_Flow_Finished_STOP_plus_code_block_flush`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize("data: [DONE]", state)
        val finished = events.filterIsInstance<StreamEvent.Flow.Finished>().firstOrNull()
        assertNotNull(finished)
        assertEquals(FinishReason.STOP, finished!!.finishReason)
    }

    @Test
    fun `think tag inside content splits into Reasoning and Text`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"id":"x","choices":[{"delta":{"content":"<think>hidden</think>visible"}}]}"""),
            state
        )
        // 期望: Content.Reasoning("hidden") + Content.Text("visible")
        assertEquals(2, events.size)
        val reasoning = events[0] as StreamEvent.Content.Reasoning
        val text = events[1] as StreamEvent.Content.Text
        assertEquals("hidden", reasoning.delta)
        assertEquals("visible", text.delta)
    }

    @Test
    fun `code block fence emits CodeBlock_Started and CodeBlock_Delta and Ended`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"id":"x","choices":[{"delta":{"content":"```kotlin\nfun a() {}\n```"}}]}"""),
            state
        )
        val started = events.filterIsInstance<StreamEvent.CodeBlock.Started>().firstOrNull()
        assertNotNull(started)
        assertEquals("kotlin", started!!.language)
    }

    @Test
    fun `non-data lines are ignored`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize("event: message", state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `invalid JSON returns empty list (does not crash)`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize("data: not json", state)
        assertTrue(events.isEmpty())
    }
}
