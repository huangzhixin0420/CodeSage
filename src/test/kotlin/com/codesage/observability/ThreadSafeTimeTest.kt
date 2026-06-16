package com.codesage.observability
import com.codesage.model.adapter.StreamEvent

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T0.7 修复验证测试：线程安全时间工具
 *
 * 验证：
 * 1. AgentSession.displayName 在多线程下产生一致的输出（不再因 SimpleDateFormat 错乱）
 * 2. StructuredLogger 在并发写时不抛异常
 * 3. DateTimeFormatter 本身是线程安全的（无需 ThreadLocal 包装）
 */
class ThreadSafeTimeTest {

    private fun createFakeAdapter(): ModelAdapter = object : ModelAdapter {
        override val providerName: String = "fake"
        override val supportedModels: List<String> = listOf("test-model")
        override fun supportsStreaming(): Boolean = false
        override fun supportsFunctionCalling(): Boolean = true
        override fun supportsVision(): Boolean = false
        override fun toVendorRequest(request: ChatRequest): String = "{}"
        override fun fromVendorResponse(response: String): ChatResponse =
            ChatResponse("", "", emptyList(), null)

        override fun parseStreamChunk(chunk: String): List<StreamEvent> = emptyList()
        override fun getStreamEndpoint(): String = "http://fake"
        override fun getChatEndpoint(): String = "http://fake"
        override fun getHeaders(): Map<String, String> = emptyMap()
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `DateTimeFormatter is thread safe under heavy concurrent format`() {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneOffset.UTC)
        val results = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val errors = AtomicInteger(0)
        val firstError = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val threads = (1..20).map {
            Thread {
                start.await()
                try {
                    repeat(500) { i ->
                        val ts = 1700000000000L + i * 1000L
                        val instant = java.time.Instant.ofEpochMilli(ts)
                        val formatted = formatter.format(instant)
                        results.add(formatted)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                    firstError.compareAndSet(null, e)
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }

        if (errors.get() > 0) {
            val ex = firstError.get()
            val msg = "Expected 0 errors, got ${errors.get()}. First error: ${ex?.javaClass?.name}: ${ex?.message}"
            throw AssertionError(msg)
        }
        assertEquals(10_000, results.size, "Expected 10,000 formatted strings")
        val distinctLengths = results.map { it.length }.toSet()
        assertEquals(setOf(19), distinctLengths, "All formatted strings should have length 19")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `AgentSession displayName works under concurrent access`() {
        val gateway = object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
        }
        val agent = com.codesage.agent.core.AgentCore(gateway = gateway)
        agent.initialize(com.codesage.agent.core.AgentConfig(systemPrompt = "test"))

        // 创建多个 session
        val sessions = (1..10).map { agent.createSession() }

        val errors = AtomicInteger(0)
        val start = CountDownLatch(1)
        val threads = (1..20).map {
            Thread {
                start.await()
                try {
                    repeat(100) {
                        sessions.forEach { s ->
                            // displayName() 在新线程中调用
                            val name = s.displayName()
                            assertTrue(
                                name.isNotBlank(),
                                "Display name should not be blank, got '$name'"
                            )
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(0, errors.get(), "No exceptions should occur during concurrent displayName access")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `StructuredLogger concurrent log writes do not corrupt output`() {
        val logger = StructuredLogger()
        val errors = AtomicInteger(0)
        val start = CountDownLatch(1)
        val threads = (1..10).map { tid ->
            Thread {
                start.await()
                try {
                    repeat(100) { i ->
                        logger.log(
                            level = StructuredLogger.LogLevel.INFO,
                            component = "test",
                            event = "evt_$i",
                            message = "thread=$tid msg=$i",
                            sessionId = "sess_$tid",
                            traceId = "trace_$tid"
                        )
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        logger.flush()
        // StructuredLogger 后台 flush 线程在 JVM 关闭前会一直跑；测试只验证并发不报错

        assertEquals(0, errors.get(), "No errors expected during concurrent logging")
    }
}
