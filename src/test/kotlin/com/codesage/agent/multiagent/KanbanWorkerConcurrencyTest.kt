package com.codesage.agent.multiagent

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.SubAgentExecutor
import com.codesage.agent.core.SubAgentResult
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T0.5 修复验证测试：KanbanWorker 并行执行
 *
 * 验证：
 * 1. 并行执行总耗时 < 顺序执行
 * 2. maxConcurrency 限制生效
 * 3. 单个任务失败不阻塞其他任务
 * 4. 进度回调被正确触发
 * 5. 空任务列表立即返回
 *
 * 实现说明：由于 SubAgentExecutor 是 final class 且构造需要 AgentCore，
 * 我们用 Kotlin object 委托方式创建一个最小 stub SubAgentExecutor 行为。
 */
class KanbanWorkerConcurrencyTest {

    /**
     * 创建一个可定制的 SubAgentExecutor，使用委托绕过父类构造逻辑。
     */
    private class FakeSubAgentExecutor(
        private val onSpawn: suspend (String, String) -> SubAgentResult
    ) : SubAgentExecutor(
        // parentAgent: AgentCore - 使用一个简化 stub
        parentAgent = StubAgentCore()
    ) {
        override suspend fun spawn(
            parentSessionId: String,
            taskDescription: String,
            toolset: String,
            maxIterations: Int,
            contextFiles: List<String>,
            progressCallback: suspend (String) -> Unit,
        ): SubAgentResult {
            return onSpawn(parentSessionId, taskDescription)
        }
    }

    /**
     * SubAgentExecutor spawn 只调用了 `parentAgent.getCurrentModel()`，
     * 其它 path 用不到。提供这个 stub 是为了让构造能通过。
     */
    private class StubAgentCore : AgentCore(
        gateway = com.codesage.model.gateway.ModelGateway()
    ) {
        // AgentCore 构造时不会立即调用 getCurrentModel
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `empty task list returns immediately`() = runBlocking {
        val executor = FakeSubAgentExecutor { _, _ ->
            SubAgentResult(true, "ok", "s", 1, listOf(""))
        }
        val worker = KanbanWorker("w", StubAgentCore(), executor)
        val start = System.currentTimeMillis()
        val result = worker.executeTasks(emptyList(), maxConcurrency = 3)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(result.isEmpty())
        assertTrue(elapsed < 100, "Should return immediately, took ${elapsed}ms")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `tasks run in parallel reducing total time`() = runBlocking {
        val taskCount = 6
        val taskDurationMs = 200L

        val executor = FakeSubAgentExecutor { _, _ ->
            delay(taskDurationMs)
            SubAgentResult(true, "ok", "s", 1, listOf(""))
        }
        val worker = KanbanWorker("w", StubAgentCore(), executor)
        val tasks = (1..taskCount).map { KanbanTask(id = "t$it", description = "task_$it") }

        val start = System.currentTimeMillis()
        val result = worker.executeTasks(tasks, maxConcurrency = 3)
        val elapsed = System.currentTimeMillis() - start

        assertEquals(taskCount, result.size, "All tasks should return")
        // 6 个任务每个 200ms，3 并发 → 理论 ~400ms；顺序会需要 1200ms+
        assertTrue(
            elapsed < 800,
            "Parallel execution should be much faster than sequential. " +
                    "Expected < 800ms, got ${elapsed}ms. Sequential would be ~${taskCount * taskDurationMs}ms."
        )
        println("[Test] $taskCount parallel tasks (concurrency=3, ${taskDurationMs}ms each) completed in ${elapsed}ms")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `maxConcurrency is respected`() = runBlocking {
        val taskCount = 9
        val taskDurationMs = 150L
        val maxConcurrency = 3
        val currentConcurrent = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val executor = FakeSubAgentExecutor { _, _ ->
            val concurrent = currentConcurrent.incrementAndGet()
            val prev = maxObserved.get()
            while (concurrent > prev && !maxObserved.compareAndSet(prev, concurrent)) {
                // CAS retry
            }
            try {
                delay(taskDurationMs)
            } finally {
                currentConcurrent.decrementAndGet()
            }
            SubAgentResult(true, "ok", "s", 1, listOf(""))
        }
        val worker = KanbanWorker("w", StubAgentCore(), executor)
        val tasks = (1..taskCount).map { KanbanTask(id = "t$it", description = "task_$it") }

        val start = System.currentTimeMillis()
        worker.executeTasks(tasks, maxConcurrency = maxConcurrency)
        val elapsed = System.currentTimeMillis() - start

        println("[Test] $taskCount tasks (max=$maxConcurrency, ${taskDurationMs}ms each) completed in ${elapsed}ms, max observed concurrency = ${maxObserved.get()}")

        assertTrue(
            maxObserved.get() <= maxConcurrency,
            "Max observed concurrency (${maxObserved.get()}) should not exceed limit ($maxConcurrency)"
        )
        // 至少观察到 2 个并发（说明真的并发执行）
        assertTrue(
            maxObserved.get() >= 2,
            "Should observe at least 2 concurrent tasks, got ${maxObserved.get()} (likely running sequentially)"
        )
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `single task failure does not block others`() = runBlocking {
        val tasks = (1..5).map { KanbanTask(id = "t$it", description = "task_$it") }

        val executor = FakeSubAgentExecutor { _, desc ->
            if ("task_3" in desc) {
                throw RuntimeException("Simulated failure for task 3")
            }
            SubAgentResult(true, "ok", "s", 1, listOf(""))
        }
        val worker = KanbanWorker("w", StubAgentCore(), executor)
        val result = worker.executeTasks(tasks, maxConcurrency = 3)

        assertEquals(tasks.size, result.size, "All tasks should return even if one fails")
        // 至少 4 个成功（第 3 个失败）
        val successCount = result.count { it.status == KanbanStatus.DONE }
        assertTrue(successCount >= 4, "At least 4 tasks should succeed, got $successCount")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `progress callback is invoked for each task`() = runBlocking {
        val tasks = (1..4).map { KanbanTask(id = "t$it", description = "task_$it") }
        val executor = FakeSubAgentExecutor { _, _ ->
            SubAgentResult(true, "ok", "s", 1, listOf(""))
        }
        val worker = KanbanWorker("w", StubAgentCore(), executor)

        val callbackIndices = mutableListOf<Int>()
        val lock = Any()
        worker.executeTasks(tasks, maxConcurrency = 2) { index, _ ->
            synchronized(lock) { callbackIndices.add(index) }
        }

        assertEquals(tasks.size, callbackIndices.size, "Callback should fire for each task")
        assertEquals(tasks.indices.toSet(), callbackIndices.toSet(), "All task indices should be reported")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `failed task is marked BLOCKED not silent skip`() = runBlocking {
        val tasks = listOf(KanbanTask(id = "t1", description = "task_1"))
        val executor = FakeSubAgentExecutor { _, _ ->
            throw RuntimeException("boom")
        }
        val worker = KanbanWorker("w", StubAgentCore(), executor)
        val result = worker.executeTasks(tasks, maxConcurrency = 1)

        assertEquals(1, result.size)
        val failed = result.first()
        assertEquals(KanbanStatus.BLOCKED, failed.status, "Failed task should be marked BLOCKED")
        assertNotNull(failed.blocker, "BLOCKED task should have a blocker message")
    }
}
