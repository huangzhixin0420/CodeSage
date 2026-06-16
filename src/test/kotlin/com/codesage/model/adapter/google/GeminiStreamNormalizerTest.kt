package com.codesage.model.adapter.google

import com.codesage.model.adapter.StreamEvent
import com.codesage.model.adapter.StreamEventNormalizer
import com.codesage.model.dto.FinishReason
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 2026-06: GeminiStreamNormalizer 单元测试
 */
class GeminiStreamNormalizerTest {

    private fun newNorm() = GeminiStreamNormalizer()
    private fun normState() = StreamEventNormalizer.StreamState()

    private fun sse(jsonContent: String) = "data: $jsonContent"

    @Test
    fun `text part produces Content_Text`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"candidates":[{"content":{"role":"model","parts":[{"text":"Hello"}]}}]}"""),
            state
        )
        assertEquals(1, events.size)
        val evt = events[0] as StreamEvent.Content.Text
        assertEquals("Hello", evt.delta)
    }

    @Test
    fun `functionCall part produces ToolCall_Delta`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_weather","args":{"city":"SF"}}}]}}]}"""),
            state
        )
        val tc = events.filterIsInstance<StreamEvent.ToolCall.Delta>().firstOrNull()
        assertNotNull(tc)
        assertEquals("get_weather", tc!!.toolName)
        assertTrue(tc.argumentsFragment.contains("SF"))
    }

    @Test
    fun `finishReason STOP produces Flow_Finished STOP`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"candidates":[{"content":{"role":"model","parts":[{"text":"done"}]},"finishReason":"STOP"}]}"""),
            state
        )
        val finished = events.filterIsInstance<StreamEvent.Flow.Finished>().firstOrNull()
        assertNotNull(finished)
        assertEquals(FinishReason.STOP, finished!!.finishReason)
    }

    @Test
    fun `usage metadata produces Flow_Finished with usage`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(
            sse("""{"candidates":[{"content":{"role":"model","parts":[]}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":10,"totalTokenCount":15}}"""),
            state
        )
        val finished = events.filterIsInstance<StreamEvent.Flow.Finished>().firstOrNull()
        assertNotNull(finished)
        assertEquals(5, finished!!.usage!!.promptTokens)
        assertEquals(10, finished.usage!!.completionTokens)
        assertEquals(15, finished.usage!!.totalTokens)
    }

    @Test
    fun `SSE comment line is ignored`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize(": this is a comment", state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `DONE sentinel returns empty list`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize("data: [DONE]", state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `invalid JSON returns empty list`() {
        val norm = newNorm()
        val state = normState()
        val events = norm.normalize("not json", state)
        assertTrue(events.isEmpty())
    }
}
