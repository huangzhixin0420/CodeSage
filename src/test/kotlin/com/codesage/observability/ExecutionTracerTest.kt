package com.codesage.observability

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ExecutionTracerTest {

    @Test
    fun `start and end trace`() {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("test_trace", "session_1")

        assertNotNull(tracer.getActiveTrace(ctx.traceId))
        assertEquals("test_trace", tracer.getActiveTrace(ctx.traceId)?.name)

        ctx.end()
        assertNull(tracer.getActiveTrace(ctx.traceId))
        assertEquals(1, tracer.getTraceHistory().size)
    }

    @Test
    fun `add spans to trace`() {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("parent", "session_1")

        val childSpanId = ctx.childSpan("child_operation")
        assertTrue(childSpanId.isNotBlank())

        ctx.endChildSpan(childSpanId)
        ctx.end()

        val history = tracer.getTraceHistory()
        assertEquals(1, history.size)
        assertEquals(1, history[0].spans.size)
    }

    @Test
    fun `add events to span`() {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("test", "session_1")

        ctx.event("tool_called", mapOf("tool" to "read_file"))
        ctx.end()

        val trace = tracer.getTraceHistory()[0]
        assertEquals(1, trace.rootSpan.events.size)
        assertEquals("tool_called", trace.rootSpan.events[0].name)
    }

    @Test
    fun `trace tree is built correctly`() {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("root")

        val span1 = ctx.childSpan("span1")
        val span2 = ctx.childSpan("span2")
        ctx.endChildSpan(span1)
        ctx.endChildSpan(span2)
        ctx.end()

        val tree = tracer.getTraceTree(ctx.traceId)
        assertNotNull(tree)
        assertEquals(2, tree?.root?.children?.size)
    }

    @Test
    fun `trace history respects limit`() {
        val tracer = ExecutionTracer()
        repeat(110) { i ->
            val ctx = tracer.startTrace("trace_$i")
            ctx.end()
        }

        val history = tracer.getTraceHistory()
        assertTrue(history.size <= 100)
    }
}
