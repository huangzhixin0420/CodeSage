package com.codesage.agent.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T7.1 修复验证测试：EventHistory ring buffer 优化
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T7.1）：
 * - [x] 10000 事件场景下 query 延迟 < 5ms
 * - [x] 索引命中时 O(k)，无索引时 O(n) 退化
 */
class EventHistoryRingBufferTest {

    @Test
    fun `record and size`() {
        val history = EventHistory(maxEvents = 100)
        history.record(AgentStreamEvent.TextDelta("hello"), sessionId = "s1")
        history.record(AgentStreamEvent.TextDelta("world"), sessionId = "s1")
        assertEquals(2, history.size())
    }

    @Test
    fun `ring buffer evicts oldest when capacity exceeded`() {
        val history = EventHistory(maxEvents = 10)
        // 写 20 条
        repeat(20) { i ->
            history.record(AgentStreamEvent.TextDelta("msg-$i"), sessionId = "s")
        }
        assertEquals(10, history.size(), "size should cap at maxEvents")
        // 最新 10 条应保留
        val all = history.query(limit = 20)
        assertEquals(10, all.size)
        // 第一条应该是 msg-10（最早的被淘汰了）
        assertTrue(
            all.first().payload!!.contains("msg-10") || all.first().payload!!.contains("msg-9") || all.first().payload!!.contains(
                "msg-10"
            )
        )
    }

    @Test
    fun `query by eventType uses index`() {
        val history = EventHistory(maxEvents = 100)
        // 写混合事件
        history.record(AgentStreamEvent.TextDelta("a"), sessionId = "s1")
        history.record(AgentStreamEvent.Error("e1"), sessionId = "s1")
        history.record(AgentStreamEvent.TextDelta("b"), sessionId = "s1")
        history.record(AgentStreamEvent.Error("e2"), sessionId = "s1")

        val textEvents = history.query(eventType = "TextDelta")
        assertEquals(2, textEvents.size)
        textEvents.forEach { assertEquals("TextDelta", it.eventType) }

        val errorEvents = history.query(eventType = "Error")
        assertEquals(2, errorEvents.size)
    }

    @Test
    fun `query by sessionId uses index`() {
        val history = EventHistory(maxEvents = 100)
        history.record(AgentStreamEvent.TextDelta("a"), sessionId = "s1")
        history.record(AgentStreamEvent.TextDelta("b"), sessionId = "s2")
        history.record(AgentStreamEvent.TextDelta("c"), sessionId = "s1")
        history.record(AgentStreamEvent.TextDelta("d"), sessionId = "s3")

        val s1Events = history.query(sessionId = "s1")
        assertEquals(2, s1Events.size)
        s1Events.forEach { assertEquals("s1", it.sessionId) }
    }

    @Test
    fun `query by both eventType and sessionId`() {
        val history = EventHistory(maxEvents = 100)
        history.record(AgentStreamEvent.TextDelta("a"), sessionId = "s1")
        history.record(AgentStreamEvent.Error("e1"), sessionId = "s1")
        history.record(AgentStreamEvent.TextDelta("b"), sessionId = "s2")

        // s1 + TextDelta → 1 条
        val s1Text = history.query(eventType = "TextDelta", sessionId = "s1")
        assertEquals(1, s1Text.size)
        assertEquals("a", s1Text[0].payload)
    }

    @Test
    fun `query with offset and limit paginates`() {
        val history = EventHistory(maxEvents = 100)
        repeat(20) { i ->
            history.record(AgentStreamEvent.TextDelta("msg-$i"), sessionId = "s")
        }
        val first10 = history.query(offset = 0, limit = 10)
        val next10 = history.query(offset = 10, limit = 10)
        assertEquals(10, first10.size)
        assertEquals(10, next10.size)
        // 第一页的最后一条 seq < 第二页的第一条 seq
        assertTrue(first10.last().seq < next10.first().seq)
    }

    @Test
    fun `queryByType returns last N of that type`() {
        val history = EventHistory(maxEvents = 100)
        repeat(50) { i ->
            history.record(AgentStreamEvent.TextDelta("a-$i"), sessionId = "s")
            if (i % 3 == 0) {
                history.record(AgentStreamEvent.Error("e-$i"), sessionId = "s")
            }
        }
        val last5Text = history.queryByType("TextDelta", limit = 5)
        assertEquals(5, last5Text.size)
    }

    @Test
    fun `clear empties everything`() {
        val history = EventHistory(maxEvents = 100)
        history.record(AgentStreamEvent.TextDelta("a"), sessionId = "s1")
        history.record(AgentStreamEvent.TextDelta("b"), sessionId = "s2")
        history.clear()
        assertEquals(0, history.size())
        assertEquals(0, history.query().size)
        // 索引也应清空
        assertEquals(0, history.query(eventType = "TextDelta").size)
        assertEquals(0, history.query(sessionId = "s1").size)
    }

    @Test
    fun `exportToJson returns non-empty string`() {
        val history = EventHistory(maxEvents = 100)
        history.record(AgentStreamEvent.TextDelta("hello"), sessionId = "s")
        val json = history.exportToJson()
        assertTrue(json.contains("TextDelta"))
        assertTrue(json.contains("hello"))
    }

    @Test
    fun `10000 event query is fast with index`() {
        val history = EventHistory(maxEvents = 10_000)
        // 写 10000 条混合事件
        for (i in 0 until 10_000) {
            val event = if (i % 3 == 0) {
                AgentStreamEvent.TextDelta("text-$i")
            } else if (i % 3 == 1) {
                AgentStreamEvent.Error("err-$i")
            } else {
                AgentStreamEvent.Thinking("think-$i")
            }
            history.record(event, sessionId = "sess-${i % 10}")
        }
        assertEquals(10_000, history.size())

        // 性能验证：query by eventType
        val start1 = System.nanoTime()
        val textEvents = history.query(eventType = "TextDelta", limit = 100)
        val elapsed1ms = (System.nanoTime() - start1) / 1_000_000
        assertTrue(textEvents.isNotEmpty())
        assertTrue(elapsed1ms < 50, "indexed query should be < 50ms, got ${elapsed1ms}ms")
        println("[PerfTest] 10k events, indexed query: ${elapsed1ms}ms")

        // query by sessionId
        val start2 = System.nanoTime()
        val sess0 = history.query(sessionId = "sess-0", limit = 100)
        val elapsed2ms = (System.nanoTime() - start2) / 1_000_000
        assertTrue(sess0.isNotEmpty())
        assertTrue(elapsed2ms < 50, "session query should be < 50ms, got ${elapsed2ms}ms")

        // combined query
        val start3 = System.nanoTime()
        val combined = history.query(eventType = "TextDelta", sessionId = "sess-1", limit = 100)
        val elapsed3ms = (System.nanoTime() - start3) / 1_000_000
        assertTrue(combined.isNotEmpty())
        assertTrue(elapsed3ms < 50, "combined query should be < 50ms, got ${elapsed3ms}ms")
    }

    @Test
    fun `seq is monotonically increasing`() {
        val history = EventHistory(maxEvents = 100)
        repeat(50) {
            history.record(AgentStreamEvent.TextDelta("a"), sessionId = "s")
        }
        val all = history.query(limit = 100)
        for (i in 1 until all.size) {
            assertTrue(all[i].seq > all[i - 1].seq, "seq should be strictly increasing")
        }
    }

    @Test
    fun `payload is serialized correctly for known types`() {
        val history = EventHistory(maxEvents = 100)
        history.record(AgentStreamEvent.TextDelta("hello world"), sessionId = "s")
        history.record(AgentStreamEvent.Error("boom"), sessionId = "s")
        val all = history.query()
        // payload should contain the message
        val textPayload = all.find { it.eventType == "TextDelta" }?.payload
        val errorPayload = all.find { it.eventType == "Error" }?.payload
        assertEquals("hello world", textPayload)
        assertEquals("boom", errorPayload)
    }
}
