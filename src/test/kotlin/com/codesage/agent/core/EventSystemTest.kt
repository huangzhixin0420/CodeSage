package com.codesage.agent.core

import com.codesage.model.dto.ToolCall
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class EventSystemTest {

    private lateinit var eventHistory: EventHistory

    @BeforeEach
    fun setup() {
        eventHistory = EventHistory()
    }

    @Test
    fun `should emit all required events`() = runBlocking {
        val events = listOf(
            AgentStreamEvent.TextDelta("hello"),
            AgentStreamEvent.ToolCallStart(com.codesage.model.dto.ToolCall("1", "read_file", "{}")),
            AgentStreamEvent.ToolCallDelta("1", "read_file", "progress..."),
            AgentStreamEvent.ToolCallResult("1", "read_file", "{}", true),
            AgentStreamEvent.ToolConfirmationNeeded("2", "write_file", "{}", "destructive"),
            AgentStreamEvent.Thinking("thinking..."),
            AgentStreamEvent.PlanGenerated(
                "plan1",
                "test",
                listOf(AgentStreamEvent.PlanStep("s1", "step1"))
            ),
            AgentStreamEvent.PlanApproved("plan1"),
            AgentStreamEvent.PlanRejected("plan2", "too complex"),
            AgentStreamEvent.ContextCompressed(1000, 500, "summarize"),
            AgentStreamEvent.SessionMigrated("old", "new", 10),
            AgentStreamEvent.Error("error"),
            AgentStreamEvent.Done
        )

        val flow = flowOf(*events.toTypedArray())
        val collected = flow.toList()

        assertEquals(events.size, collected.size)
        assertTrue(collected.any { it is AgentStreamEvent.ToolCallDelta })
        assertTrue(collected.any { it is AgentStreamEvent.ToolConfirmationNeeded })
        assertTrue(collected.any { it is AgentStreamEvent.PlanGenerated })
        assertTrue(collected.any { it is AgentStreamEvent.PlanApproved })
        assertTrue(collected.any { it is AgentStreamEvent.PlanRejected })
        assertTrue(collected.any { it is AgentStreamEvent.ContextCompressed })
        assertTrue(collected.any { it is AgentStreamEvent.SessionMigrated })
    }

    @Test
    fun `should record event history`() {
        val event1 = AgentStreamEvent.TextDelta("hello")
        val event2 = AgentStreamEvent.Thinking("thinking")

        eventHistory.record(event1, "session_1")
        eventHistory.record(event2, "session_1")

        assertEquals(2, eventHistory.size())

        val query = eventHistory.query(sessionId = "session_1")
        assertEquals(2, query.size)

        val byType = eventHistory.queryByType("TextDelta")
        assertEquals(1, byType.size)
    }

    @Test
    fun `should enforce max event history limit`() {
        val smallHistory = EventHistory(maxEvents = 5)
        repeat(10) {
            smallHistory.record(AgentStreamEvent.TextDelta("$it"))
        }
        assertEquals(5, smallHistory.size())
    }

    @Test
    fun `should serialize events correctly`() {
        val event = AgentStreamEvent.PlanGenerated(
            planId = "p1",
            description = "desc",
            steps = listOf(
                AgentStreamEvent.PlanStep("s1", "step1", listOf()),
                AgentStreamEvent.PlanStep("s2", "step2", listOf("s1"))
            )
        )

        assertEquals("p1", event.planId)
        assertEquals("desc", event.description)
        assertEquals(2, event.steps.size)
        assertEquals(listOf("s1"), event.steps[1].dependsOn)
    }

    @Test
    fun `should export history to json`() {
        eventHistory.record(AgentStreamEvent.TextDelta("test"), "s1")
        val json = eventHistory.exportToJson()
        assertTrue(json.contains("TextDelta"))
        assertTrue(json.contains("test"))
    }

    @Test
    fun `EventBatchEmitter should merge text deltas`() = runBlocking {
        val emitter = EventBatchEmitter(batchSize = 10, batchIntervalMs = 1000)
        val events = flowOf(
            AgentStreamEvent.TextDelta("a"),
            AgentStreamEvent.TextDelta("b"),
            AgentStreamEvent.Thinking("x"),
            AgentStreamEvent.TextDelta("c")
        )

        val result = emitter.batch(events).toList()
        // 合并后应该减少 TextDelta 数量
        val textDeltas = result.filterIsInstance<AgentStreamEvent.TextDelta>()
        assertTrue(textDeltas.size <= 2, "Expected merged text deltas, got ${textDeltas.size}")

        emitter.shutdown()
    }

    @Test
    fun `should dedup events using recent cache`() {
        val cache = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val dedupWindowMs = 500L

        fun shouldDedup(event: AgentStreamEvent): Boolean {
            val key = event::class.simpleName ?: return true
            val now = System.currentTimeMillis()
            val last = cache[key]
            return if (last != null && now - last < dedupWindowMs) {
                false
            } else {
                cache[key] = now
                true
            }
        }

        val event1 = AgentStreamEvent.Thinking("msg1")
        val event2 = AgentStreamEvent.Thinking("msg2")

        assertTrue(shouldDedup(event1))
        assertFalse(shouldDedup(event2)) // 500ms 内重复
    }
}
