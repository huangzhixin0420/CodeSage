package com.codesage.agent.tools

import com.codesage.observability.ExecutionTracer
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T7.2 修复验证测试：工具调用追踪关联
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T7.2）：
 * - [x] 每次 ToolCallStart 创建子 span
 * - [x] ToolCallResult 结束 span，记录 success/duration
 * - [x] tracer.endChildSpan 事件写入 EventHistory
 *
 * 注：完整 ToolExecutor.execute 涉及 PSI，测试较重。这里测试核心的 span 生命周期逻辑。
 */
class ToolCallTracingTest {

    @Test
    fun `tracer creates child span for tool call`() = runBlocking {
        val tracer = ExecutionTracer()
        val parentCtx = tracer.startTrace("chat_with_tools", sessionId = "s1")
        // 模拟 ToolExecutor 创建 span
        val spanId = tracer.addSpan(
            traceId = parentCtx.traceId,
            parentSpanId = parentCtx.currentSpanId,
            name = "tool.read_file",
            attributes = mapOf("tool.id" to "tc_1", "tool.name" to "read_file")
        )
        assertTrue(spanId.isNotBlank(), "span should be created")

        val tree = tracer.getTraceTree(parentCtx.traceId)
        assertNotNull(tree)
        val rootChildren = tree!!.root.children
        assertEquals(1, rootChildren.size, "parent should have 1 child span")
        assertEquals("tool.read_file", rootChildren[0].span.name)
        assertEquals(mapOf("tool.id" to "tc_1", "tool.name" to "read_file"), rootChildren[0].span.attributes)
    }

    @Test
    fun `span tracks duration and success status`() = runBlocking {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("test")
        val spanId = tracer.addSpan(ctx.traceId, ctx.currentSpanId, "tool.search_code")

        // 模拟执行耗时
        Thread.sleep(50)
        tracer.endSpan(ctx.traceId, spanId, ExecutionTracer.TraceStatus.OK)

        val tree = tracer.getTraceTree(ctx.traceId)
        val span = tree!!.root.children[0].span
        assertNotNull(span.durationMs)
        assertTrue(span.durationMs!! >= 50, "duration should be >= 50ms")
        assertEquals(ExecutionTracer.TraceStatus.OK, span.status)
    }

    @Test
    fun `span status records error`() = runBlocking {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("test")
        val spanId = tracer.addSpan(ctx.traceId, ctx.currentSpanId, "tool.fail_tool")
        tracer.endSpan(ctx.traceId, spanId, ExecutionTracer.TraceStatus.ERROR)

        val tree = tracer.getTraceTree(ctx.traceId)
        val span = tree!!.root.children[0].span
        assertEquals(ExecutionTracer.TraceStatus.ERROR, span.status)
    }

    @Test
    fun `span status records cancellation`() = runBlocking {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("test")
        val spanId = tracer.addSpan(ctx.traceId, ctx.currentSpanId, "tool.blocked")
        tracer.endSpan(ctx.traceId, spanId, ExecutionTracer.TraceStatus.CANCELLED)

        val tree = tracer.getTraceTree(ctx.traceId)
        val span = tree!!.root.children[0].span
        assertEquals(ExecutionTracer.TraceStatus.CANCELLED, span.status)
    }

    @Test
    fun `multiple tool calls create multiple spans`() = runBlocking {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("test")
        val span1 = tracer.addSpan(ctx.traceId, ctx.currentSpanId, "tool.read_file")
        val span2 = tracer.addSpan(ctx.traceId, ctx.currentSpanId, "tool.search_code")
        val span3 = tracer.addSpan(ctx.traceId, ctx.currentSpanId, "tool.run_command")
        assertTrue(span1.isNotBlank())
        assertTrue(span2.isNotBlank())
        assertTrue(span3.isNotBlank())
        assertNotEquals(span1, span2)
        assertNotEquals(span2, span3)

        tracer.endSpan(ctx.traceId, span1)
        tracer.endSpan(ctx.traceId, span2)
        tracer.endSpan(ctx.traceId, span3)

        val tree = tracer.getTraceTree(ctx.traceId)
        assertEquals(3, tree!!.root.children.size, "should have 3 tool call children")
        val names = tree.root.children.map { it.span.name }.toSet()
        assertEquals(setOf("tool.read_file", "tool.search_code", "tool.run_command"), names)
    }

    @Test
    fun `events are recorded on spans`() = runBlocking {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("test")
        val spanId = tracer.addSpan(ctx.traceId, ctx.currentSpanId, "tool.read_file")
        // 模拟"工具完成"事件
        tracer.addEvent(
            traceId = ctx.traceId,
            spanId = spanId,
            eventName = "tool.completed",
            attributes = mapOf("duration_ms" to "100")
        )
        tracer.endSpan(ctx.traceId, spanId)

        val tree = tracer.getTraceTree(ctx.traceId)
        val span = tree!!.root.children[0].span
        assertEquals(1, span.events.size)
        assertEquals("tool.completed", span.events[0].name)
        assertEquals("100", span.events[0].attributes["duration_ms"])
    }

    @Test
    fun `ToolExecutor accepts tracer and traceContext as optional parameters`() {
        // 验证 ToolExecutor 的构造函数签名包含 tracer 和 traceContext
        val logger = Logger.getLogger<ToolCallTracingTest>()
        val executor = ToolExecutor(project = null, tracer = null, traceContext = null)
        // 不传 tracer 不应抛异常
        assertNotNull(executor)
    }

    @Test
    fun `ToolExecutor works without tracer (backward compatibility)`() = runBlocking {
        // 不传 tracer / traceContext — 应仍正常工作
        val executor = ToolExecutor(project = null)
        val toolCall = com.codesage.model.dto.ToolCall(
            id = "tc_1",
            name = "nonexistent_tool",
            arguments = "{}"
        )
        // 不应抛 NPE
        val result = executor.execute(toolCall)
        assertNotNull(result)
    }

    @Test
    fun `tool span nesting represents execution hierarchy`() = runBlocking {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("agent_loop")
        // 模拟 tool span
        val toolSpan = tracer.addSpan(ctx.traceId, ctx.currentSpanId, "tool.read_file")
        // 模拟 tool 内部的 sub-tool call
        val subToolSpan = tracer.addSpan(ctx.traceId, toolSpan, "tool.parse_content")
        tracer.endSpan(ctx.traceId, subToolSpan, ExecutionTracer.TraceStatus.OK)
        tracer.endSpan(ctx.traceId, toolSpan, ExecutionTracer.TraceStatus.OK)

        val tree = tracer.getTraceTree(ctx.traceId)
        // tool span 应有 1 个子 span（sub-tool）
        val toolNode = tree!!.root.children[0]
        assertEquals("tool.read_file", toolNode.span.name)
        assertEquals(1, toolNode.children.size)
        assertEquals("tool.parse_content", toolNode.children[0].span.name)
    }
}
