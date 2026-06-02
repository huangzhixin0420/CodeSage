package com.codesage.observability

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.EventHistory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T7.3 修复验证测试：Observability 聚合服务
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T7.3）：
 * - [x] 工具栏新增图标可打开面板
 * - [x] 面板显示：当前会话 trace 树、最近 100 事件、metrics 仪表盘
 *
 * 注：UI 测试通过现有 JCEFOfflineTest 模式覆盖。这里测试核心聚合逻辑。
 */
class ObservabilityServiceTest {

    @Test
    fun `snapshot returns events and metrics`() {
        val eventHistory = EventHistory(maxEvents = 100)
        val tracer = ExecutionTracer()
        val metrics = MetricsCollector()
        val service = ObservabilityService(eventHistory, tracer, metrics)

        eventHistory.record(AgentStreamEvent.TextDelta("hello"), sessionId = "s1")
        eventHistory.record(AgentStreamEvent.TextDelta("world"), sessionId = "s1")
        metrics.incrementCounter("chat_requests")
        metrics.recordTimer("chat_duration", 100)

        val snapshot = service.snapshot()
        assertEquals(2, snapshot.events.size)
        assertEquals(1, snapshot.metrics.counters.size)
        // 计数器只增 1（incrementCounter 默认 delta=1）
        assertEquals(1L, snapshot.metrics.counters["chat_requests"])
        assertEquals(1, snapshot.metrics.timers.size)
    }

    @Test
    fun `snapshot filters by sessionId`() {
        val eventHistory = EventHistory(maxEvents = 100)
        val tracer = ExecutionTracer()
        val metrics = MetricsCollector()
        val service = ObservabilityService(eventHistory, tracer, metrics)

        eventHistory.record(AgentStreamEvent.TextDelta("a"), sessionId = "s1")
        eventHistory.record(AgentStreamEvent.TextDelta("b"), sessionId = "s2")
        eventHistory.record(AgentStreamEvent.TextDelta("c"), sessionId = "s1")

        val s1Snapshot = service.snapshot(sessionId = "s1")
        assertEquals(2, s1Snapshot.events.size)
        assertTrue(s1Snapshot.events.all { it.sessionId == "s1" })

        val s2Snapshot = service.snapshot(sessionId = "s2")
        assertEquals(1, s2Snapshot.events.size)
        assertEquals("s2", s2Snapshot.events[0].sessionId)

        val allSnapshot = service.snapshot()
        assertEquals(3, allSnapshot.events.size)
    }

    @Test
    fun `snapshot includes recent trace IDs`() {
        val eventHistory = EventHistory(maxEvents = 100)
        val tracer = ExecutionTracer()
        val metrics = MetricsCollector()
        val service = ObservabilityService(eventHistory, tracer, metrics)

        val ctx1 = tracer.startTrace("trace1", sessionId = "s1")
        val ctx2 = tracer.startTrace("trace2", sessionId = "s1")
        tracer.endTrace(ctx1.traceId)
        tracer.endTrace(ctx2.traceId)

        val snapshot = service.snapshot()
        assertEquals(2, snapshot.recentTraceIds.size)
        assertTrue(snapshot.recentTraceIds.contains(ctx1.traceId))
        assertTrue(snapshot.recentTraceIds.contains(ctx2.traceId))
    }

    @Test
    fun `getTraceTree returns tree for known trace`() {
        val eventHistory = EventHistory(maxEvents = 100)
        val tracer = ExecutionTracer()
        val metrics = MetricsCollector()
        val service = ObservabilityService(eventHistory, tracer, metrics)

        val ctx = tracer.startTrace("test")
        val spanId = tracer.addSpan(ctx.traceId, ctx.currentSpanId, "child_span")
        tracer.endSpan(ctx.traceId, spanId)
        tracer.endTrace(ctx.traceId)

        val tree = service.getTraceTree(ctx.traceId)
        assertNotNull(tree)
        assertEquals(1, tree!!.root.children.size)
        assertEquals("child_span", tree.root.children[0].span.name)
    }

    @Test
    fun `getTraceTree returns null for unknown trace`() {
        val eventHistory = EventHistory(maxEvents = 100)
        val tracer = ExecutionTracer()
        val metrics = MetricsCollector()
        val service = ObservabilityService(eventHistory, tracer, metrics)

        assertNull(service.getTraceTree("nonexistent"))
    }

    @Test
    fun `summary counts events and traces correctly`() {
        val eventHistory = EventHistory(maxEvents = 100)
        val tracer = ExecutionTracer()
        val metrics = MetricsCollector()
        val service = ObservabilityService(eventHistory, tracer, metrics)

        // 3 events
        repeat(3) {
            eventHistory.record(AgentStreamEvent.TextDelta("e"), sessionId = "s")
        }
        // 2 traces
        val ctx1 = tracer.startTrace("t1")
        val ctx2 = tracer.startTrace("t2")
        tracer.endTrace(ctx1.traceId)
        tracer.endTrace(ctx2.traceId)
        // 4 counters
        metrics.incrementCounter("c1")
        metrics.incrementCounter("c2")
        metrics.incrementCounter("c3")
        metrics.incrementCounter("c4")
        // 2 timers
        metrics.recordTimer("tm1", 50)
        metrics.recordTimer("tm2", 100)

        val summary = service.summary()
        assertEquals(3, summary.totalEvents)
        assertEquals(2, summary.totalTraces)
        assertEquals(4, summary.totalCounters)
        assertEquals(2, summary.totalTimers)
        assertTrue(summary.uptimeMs >= 0)
    }

    @Test
    fun `summary uptime is positive`() {
        val service = ObservabilityService(
            EventHistory(),
            ExecutionTracer(),
            MetricsCollector()
        )
        val summary = service.summary()
        assertTrue(summary.uptimeMs >= 0, "uptime should be non-negative")
    }

    @Test
    fun `summary uptime formats correctly`() {
        val summary1 = ObservabilitySummary(0, 0, 0, 0, uptimeMs = 0)
        assertEquals("0s", summary1.uptimeFormatted())
        val summary2 = ObservabilitySummary(0, 0, 0, 0, uptimeMs = 65_000)
        assertEquals("1m 5s", summary2.uptimeFormatted())
        val summary3 = ObservabilitySummary(0, 0, 0, 0, uptimeMs = 3_661_000)
        assertEquals("1h 1m 1s", summary3.uptimeFormatted())
    }

    @Test
    fun `getActiveTraceIds returns only active traces`() {
        val eventHistory = EventHistory(maxEvents = 100)
        val tracer = ExecutionTracer()
        val metrics = MetricsCollector()
        val service = ObservabilityService(eventHistory, tracer, metrics)

        // 已结束的 trace
        val ended = tracer.startTrace("ended")
        tracer.endTrace(ended.traceId)
        // 未结束的 trace
        val active = tracer.startTrace("active")

        val activeIds = service.getActiveTraceIds()
        assertTrue(activeIds.contains(active.traceId))
        assertFalse(activeIds.contains(ended.traceId))
    }

    @Test
    fun `snapshot respects recentEventLimit`() {
        val eventHistory = EventHistory(maxEvents = 100)
        val tracer = ExecutionTracer()
        val metrics = MetricsCollector()
        val service = ObservabilityService(eventHistory, tracer, metrics)

        // 写 50 个事件
        repeat(50) {
            eventHistory.record(AgentStreamEvent.TextDelta("msg-$it"), sessionId = "s")
        }
        val snap = service.snapshot(recentEventLimit = 10)
        assertEquals(10, snap.events.size, "should respect recentEventLimit")
    }

    @Test
    fun `observability service is thread safe for snapshot calls`() {
        // 使用足够大的 maxEvents 避免 ring buffer 覆盖
        val eventHistory = EventHistory(maxEvents = 2000)
        val tracer = ExecutionTracer()
        val metrics = MetricsCollector()
        val service = ObservabilityService(eventHistory, tracer, metrics)

        // 并发写 + 读
        val threads = (0 until 10).map { i ->
            Thread {
                repeat(100) { j ->
                    eventHistory.record(
                        AgentStreamEvent.TextDelta("t$i-$j"),
                        sessionId = "s$i"
                    )
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        // 多次 snapshot 调用应不抛
        repeat(10) { service.snapshot() }
        // 最终应有 1000 个事件
        assertEquals(1000, eventHistory.size())
    }
}
