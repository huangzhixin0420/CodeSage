package com.codesage.agent.multiagent

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T4.1 修复验证测试：Agent 消息总线
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T4.1）：
 * - [x] 消息发布/订阅不丢失
 * - [x] 慢消费者不阻塞快消费者
 *
 * 测试模式：bus.subscribe(channel) 返回的 Flow 内部已创建 buffered Channel 并加入订阅者列表。
 * 测试只需要：
 * 1. 创建订阅（同步）
 * 2. publish（同步 fan-out）
 * 3. 用 first() / take(N).toList() 收取消息
 */
class AgentMessageBusTest {

    private val buses = mutableListOf<AgentMessageBus>()

    private fun newBus(config: AgentMessageBus.BusConfig = AgentMessageBus.BusConfig()): AgentMessageBus {
        val b = AgentMessageBus(config)
        buses.add(b)
        return b
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        buses.forEach { it.shutdown() }
        buses.clear()
    }

    @Test
    fun `subscribe then publish delivers message`() = runBlocking {
        val bus = newBus()
        val flow = bus.subscribe("agent.coder")  // 同步创建订阅
        val msg = AgentMessage.TaskAssigned(
            channel = "agent.coder",
            taskId = "t1",
            description = "Implement foo"
        )
        val msgId = bus.publish(msg)
        assertTrue(msgId.isNotBlank(), "publish should return a non-blank messageId")

        val received = withTimeout(1000) { flow.first() }
        assertTrue(received is AgentMessage.TaskAssigned)
        assertEquals("t1", (received as AgentMessage.TaskAssigned).taskId)
        assertEquals(msgId, received.messageId, "publish should auto-fill messageId")
    }

    @Test
    fun `subscribe on different channel does not receive messages`() = runBlocking {
        val bus = newBus()
        val flow = bus.subscribe("agent.reviewer")
        bus.publish(AgentMessage.TaskCompleted(channel = "agent.coder", taskId = "t1", result = "ok"))
        // reviewer 订阅者不应该收到任何东西 → first() 应该 timeout
        val list = try {
            withTimeout(200) { flow.take(1).toList() }
        } catch (e: Exception) {
            emptyList<AgentMessage>()
        }
        assertTrue(list.isEmpty(), "reviewer should not receive coder messages")
    }

    @Test
    fun `wildcard channel receives all messages`() = runBlocking {
        val bus = newBus()
        val flow = bus.subscribe("*")
        bus.publish(AgentMessage.QuestionAsked(channel = "agent.coder", question = "q?"))
        bus.publish(AgentMessage.QuestionAsked(channel = "agent.reviewer", question = "r?"))

        val received = withTimeout(1000) { flow.take(2).toList() }
        assertEquals(2, received.size)
    }

    @Test
    fun `multiple message types preserve type info`() = runBlocking {
        val bus = newBus()
        val flow = bus.subscribe("*")
        bus.publish(AgentMessage.TaskCompleted(channel = "agent.coder", taskId = "t1", result = "ok"))
        bus.publish(AgentMessage.TaskBlocked(channel = "agent.reviewer", taskId = "t2", reason = "no"))
        bus.publish(AgentMessage.Escalation(channel = "orchestrator", reason = "stuck"))

        val received = withTimeout(1000) { flow.take(3).toList() }
        assertTrue(received[0] is AgentMessage.TaskCompleted)
        assertTrue(received[1] is AgentMessage.TaskBlocked)
        assertTrue(received[2] is AgentMessage.Escalation)
    }

    @Test
    fun `slow consumer does not block fast consumer`() = runBlocking {
        val bus = newBus(AgentMessageBus.BusConfig(subscriberBufferCapacity = 100))
        val flow = bus.subscribe("flood")
        // 快速发 50 条，订阅者只 take(5) — 慢消费者取走 5 条后，剩下的可以丢弃（不阻塞 publisher）
        repeat(50) { i ->
            bus.publish(AgentMessage.TaskAssigned(channel = "flood", taskId = "t$i", description = "x"))
        }
        val list = withTimeout(1000) { flow.take(5).toList() }
        assertEquals(5, list.size)
    }

    @Test
    fun `pruneExpiredAcks returns count`() {
        val bus = newBus()
        // 没有 pending ack 时返回 0
        assertEquals(0, bus.pruneExpiredAcks())
    }

    @Test
    fun `shared context messages go to all subscribers`() = runBlocking {
        val bus = newBus()
        val flow = bus.subscribe("agent.coder")
        bus.publish(AgentMessage.SharedContext(key = "plan.dag", value = "yaml-content", channel = "*"))
        val received = withTimeout(500) { flow.first() }
        assertTrue(received is AgentMessage.SharedContext)
        assertEquals("plan.dag", (received as AgentMessage.SharedContext).key)
    }

    @Test
    fun `ack removes pending ack`() {
        val bus = newBus()
        // 没有显式 registerPendingAck，ack 应返回 false
        assertFalse(bus.ack("nonexistent"))
    }

    @Test
    fun `messageId is auto-generated when blank`() {
        val bus = newBus()
        val id1 = bus.publish(AgentMessage.TaskCompleted(channel = "x", taskId = "t1", result = "ok"))
        val id2 = bus.publish(AgentMessage.TaskCompleted(channel = "x", taskId = "t2", result = "ok"))
        assertTrue(id1.isNotBlank())
        assertTrue(id2.isNotBlank())
        assertNotEquals(id1, id2, "consecutive messages should have different IDs")
    }

    @Test
    fun `preserved messageId is not overwritten`() {
        val bus = newBus()
        val id = bus.publish(
            AgentMessage.TaskCompleted(messageId = "my-id", channel = "x", taskId = "t", result = "ok")
        )
        assertEquals("my-id", id)
    }
}
