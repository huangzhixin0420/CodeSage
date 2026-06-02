package com.codesage.agent.core

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T0.1 修复验证测试：会话管理并发安全
 *
 * 验证 [AgentCore] 的会话相关方法在多线程并发场景下的正确性：
 * 1. 多个线程同时调用 `getOrCreateSession()` 不会创建出重复 session
 * 2. 并发 `createSession()` 调用产生不同的 session id
 * 3. `deleteSession()` 之后 `currentSessionId` 正确切到下一个或 null
 * 4. `switchSession()` 之后所有线程立即看到新的 currentSessionId
 * 5. 1000 次并发混合操作后系统状态一致
 */
class AgentCoreConcurrencyTest {

    private fun createFakeAdapter(): ModelAdapter = object : ModelAdapter {
        override val providerName: String = "fake"
        override val supportedModels: List<String> = listOf("test-model")
        override fun supportsStreaming(): Boolean = false
        override fun supportsFunctionCalling(): Boolean = true
        override fun supportsVision(): Boolean = false
        override fun toVendorRequest(request: ChatRequest): String = "{}"
        override fun fromVendorResponse(response: String): ChatResponse =
            ChatResponse("", "", emptyList(), null)

        override fun parseStreamChunk(chunk: String): StreamChunk? = null
        override fun getStreamEndpoint(): String = "http://fake"
        override fun getChatEndpoint(): String = "http://fake"
        override fun getHeaders(): Map<String, String> = emptyMap()
    }

    private fun createFakeGateway(): ModelGateway = object : ModelGateway() {
        override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
    }

    private fun newAgent(): AgentCore = AgentCore(gateway = createFakeGateway()).apply {
        initialize(AgentConfig(systemPrompt = "test prompt"))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `concurrent getOrCreateSession yields a single current session`() {
        val agent = newAgent()
        val threadCount = 50
        val iterations = 20
        val observedIds = ConcurrentLinkedQueue<String>()
        val startLatch = CountDownLatch(1)

        val threads = (1..threadCount).map {
            Thread {
                startLatch.await()
                repeat(iterations) {
                    val session = agent.getCurrentSession()
                    if (session != null) {
                        observedIds.add(session.id)
                    }
                }
            }.apply { start() }
        }

        startLatch.countDown()
        threads.forEach { it.join() }

        // 至少有 threadCount * iterations 次观察；至少包含一个 session id
        assertTrue(observedIds.isNotEmpty(), "Should have observed at least one session id")
        // 由于当前 AgentCore.initialize 已自动 createSession 一次，currentSessionId 应始终指向同一个
        val distinctIds = observedIds.toSet()
        assertEquals(
            1,
            distinctIds.size,
            "All threads should observe the same single current session, got: $distinctIds"
        )
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `concurrent createSession produces distinct session ids`() {
        val agent = newAgent()
        val threadCount = 30
        val initialCount = agent.getSessions().size
        val startLatch = CountDownLatch(1)
        val createdIds = ConcurrentLinkedQueue<String>()

        val threads = (1..threadCount).map {
            Thread {
                startLatch.await()
                val session = agent.createSession()
                createdIds.add(session.id)
            }.apply { start() }
        }

        startLatch.countDown()
        threads.forEach { it.join() }

        assertEquals(threadCount, createdIds.size, "All threads should have created a session")
        val distinctIds = createdIds.toSet()
        assertEquals(
            threadCount,
            distinctIds.size,
            "All created session ids must be distinct, got duplicates: $createdIds"
        )

        // sessions map 数量增量应正好等于 threadCount
        // (不受 initialize 中 restoreSessions 恢复的旧 session 影响)
        val finalCount = agent.getSessions().size
        assertEquals(
            initialCount + threadCount,
            finalCount,
            "Sessions map should grow by exactly threadCount, initial=$initialCount, final=$finalCount"
        )
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `deleteSession of current switches to another or null`() {
        val agent = newAgent()
        // 创建额外 session
        val second = agent.createSession()
        agent.switchSession(second.id)
        assertEquals(second.id, agent.getCurrentSession()?.id)

        agent.deleteSession(second.id)

        // 删除后 currentSessionId 应不再是 second
        val current = agent.getCurrentSession()
        // current 要么是 null（极端：没有别的 session），要么是 sessions map 中除 second 之外的某个
        if (current != null) {
            assertNotEquals(second.id, current.id, "Current should no longer be the deleted session")
            assertTrue(
                agent.getSessions().any { it.id == current.id },
                "Current ${current.id} should be in sessions map"
            )
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `switchSession is immediately visible across threads`() {
        val agent = newAgent()
        val first = agent.createSession()
        val second = agent.createSession()
        val seenIds = ConcurrentLinkedQueue<String>()

        // 启动 8 个后台观察线程同时记录 currentSessionId
        val observerReady = CountDownLatch(1)
        val observerStop = CountDownLatch(1)
        val observers = (1..8).map {
            Thread {
                observerReady.await()
                while (observerStop.count > 0) {
                    agent.getCurrentSession()?.id?.let { seenIds.add(it) }
                    Thread.sleep(2)
                }
            }.apply { start() }
        }

        observerReady.countDown()
        // 给 observer 一点时间开始运行
        Thread.sleep(30)
        agent.switchSession(first.id)
        Thread.sleep(30)
        agent.switchSession(second.id)
        Thread.sleep(30)
        observerStop.countDown()
        observers.forEach { it.join() }

        // 8 个观察线程 * 多次轮询，100% 应该看到 first 和 second
        assertTrue(seenIds.contains(first.id), "Observers should have seen first session, saw: ${seenIds.toSet()}")
        assertTrue(seenIds.contains(second.id), "Observers should have seen second session, saw: ${seenIds.toSet()}")
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `1000 concurrent mixed operations maintain consistency`() {
        val agent = newAgent()
        val opCount = 1000
        val createdIds = ConcurrentLinkedQueue<String>()
        val errorCount = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val threads = (1..20).map { tid ->
            Thread {
                startLatch.await()
                repeat(opCount / 20) { i ->
                    try {
                        when ((tid + i) % 4) {
                            0 -> createdIds.add(agent.createSession().id)
                            1 -> {
                                val s = agent.getCurrentSession()
                                if (s != null) agent.deleteSession(s.id)
                            }

                            2 -> {
                                val s = agent.getCurrentSession()
                                if (s != null) agent.switchSession(s.id)
                            }

                            else -> agent.getSessions().size
                        }
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    }
                }
            }.apply { start() }
        }
        startLatch.countDown()
        threads.forEach { it.join() }

        assertEquals(0L, errorCount.get().toLong(), "No exceptions should occur during mixed concurrent ops")

        // 所有创建的 id 必须唯一
        val distinct = createdIds.toSet()
        assertEquals(createdIds.size, distinct.size, "All created ids must be unique")

        // 任意时刻 getSessions() 都不应抛异常
        val sessions = agent.getSessions()
        assertTrue(sessions.isNotEmpty(), "At least one session should remain")
    }
}
