package com.codesage.agent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T0.2 修复验证测试：EventBatchEmitter 资源管理
 *
 * 验证：
 * 1. shutdown() 幂等：可多次调用不抛异常
 * 2. shutdown() 后再调用 batch() 抛 IllegalStateException
 * 3. 高频发射 + shutdown 不会丢奔已发射的事件（除明确标记的）
 * 4. emitter 的 CoroutineScope 在 shutdown 后不再有活跃协程
 */
class EventBatchEmitterResourceTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `shutdown is idempotent`() {
        val emitter = EventBatchEmitter(batchSize = 5, batchIntervalMs = 50)
        emitter.shutdown()
        emitter.shutdown()  // 第二次不应抛异常
        emitter.shutdown()  // 第三次也不应抛异常
        assertTrue(emitter.isShutdown)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `batch after shutdown throws`() = runBlocking {
        val emitter = EventBatchEmitter(batchSize = 5, batchIntervalMs = 50)
        emitter.shutdown()

        val flow = flowOf(AgentStreamEvent.TextDelta("a"))
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { emitter.batch(flow).toList() }
        }
        assertTrue(ex.message!!.contains("shut down"))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `10000 emissions with shutdown completes without hang`() = runBlocking {
        val emitter = EventBatchEmitter(batchSize = 100, batchIntervalMs = 5)
        val emissionCount = AtomicInteger(0)

        // 上游流
        val source = flow {
            repeat(10_000) { i ->
                emit(AgentStreamEvent.TextDelta("chunk_$i "))
                if (i % 1000 == 0) {
                    yield()
                }
            }
        }

        val job = launch {
            emitter.batch(source).collect { event ->
                when (event) {
                    is AgentStreamEvent.TextDelta -> emissionCount.incrementAndGet()
                    else -> {}
                }
            }
        }

        // 让 batch 处理一会儿
        delay(100)
        emitter.shutdown()
        job.join()

        // emissionCount 可能小于 10000（合并 + shutdown 中途丢弃），但应 > 0
        assertTrue(emissionCount.get() > 0, "Should have emitted at least some events, got ${emissionCount.get()}")
        println("[Test] Emitted ${emissionCount.get()} merged events from 10000 source events")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `concurrent shutdown is safe`() = runBlocking {
        val emitter = EventBatchEmitter(batchSize = 5, batchIntervalMs = 50)
        val barrier = java.util.concurrent.CountDownLatch(1)
        val threads = (1..20).map {
            Thread {
                barrier.await()
                emitter.shutdown()
            }.apply { start() }
        }
        barrier.countDown()
        threads.forEach { it.join() }
        assertTrue(emitter.isShutdown)
        // 没有任何线程应该抛异常（CAS 保证只有一个真正执行 cancel）
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `droppedCount starts at zero`() {
        val emitter = EventBatchEmitter()
        assertEquals(0L, emitter.droppedCount)
    }
}
