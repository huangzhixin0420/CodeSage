package com.codesage.agent.planner

import com.codesage.agent.core.AgentStreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TaskExecutorTest {

    @Test
    fun `should execute independent tasks in parallel`() = runBlocking {
        val executionOrder = ConcurrentHashMap<String, Long>()
        val counter = AtomicInteger(0)

        val executor = StepExecutor { step ->
            executionOrder[step.id] = System.currentTimeMillis()
            counter.incrementAndGet()
            // 模拟短暂执行
            Thread.sleep(50)
            StepExecutionResult(step.id, true, "Done: ${step.description}")
        }

        val steps = listOf(
            TaskStep(id = "A", description = "Task A", dependencies = emptyList()),
            TaskStep(id = "B", description = "Task B", dependencies = emptyList()),
            TaskStep(id = "C", description = "Task C", dependencies = emptyList())
        )

        val plan = DagTaskPlan(
            taskId = "parallel_test",
            description = "Test parallel execution",
            steps = steps,
            executionOrder = listOf(listOf("A", "B", "C")),
            estimatedSteps = 1,
            totalSteps = 3
        )

        val parallelExecutor = ParallelTaskExecutor()
        val events = parallelExecutor.execute(plan, executor).toList()

        // 所有步骤都应该成功执行
        assertEquals(3, counter.get(), "All 3 independent tasks should execute")

        // 验证没有错误事件
        val errors = events.filterIsInstance<AgentStreamEvent.Error>()
        assertTrue(errors.isEmpty(), "Should have no errors during parallel execution")

        // 验证完成思考事件
        val thinkingEvents = events.filterIsInstance<AgentStreamEvent.Thinking>()
        assertTrue(thinkingEvents.any { it.message.contains("完成") })
    }

    @Test
    fun `should wait for dependencies before execution`() = runBlocking {
        val executionTimestamps = ConcurrentHashMap<String, Long>()

        val executor = StepExecutor { step ->
            executionTimestamps[step.id] = System.currentTimeMillis()
            Thread.sleep(20)
            StepExecutionResult(step.id, true, "Done: ${step.description}")
        }

        val steps = listOf(
            TaskStep(id = "A", description = "Task A", dependencies = emptyList()),
            TaskStep(id = "B", description = "Task B", dependencies = listOf("A")),
            TaskStep(id = "C", description = "Task C", dependencies = listOf("A")),
            TaskStep(id = "D", description = "Task D", dependencies = listOf("B", "C"))
        )

        val plan = DagTaskPlan(
            taskId = "dependency_test",
            description = "Test dependency execution",
            steps = steps,
            executionOrder = DagUtils.topologicalSort(steps),
            estimatedSteps = 3,
            totalSteps = 4
        )

        val parallelExecutor = ParallelTaskExecutor()
        val events = parallelExecutor.execute(plan, executor).toList()

        // 验证执行顺序
        val aTime = executionTimestamps["A"] ?: fail("A should have executed")
        val bTime = executionTimestamps["B"] ?: fail("B should have executed")
        val cTime = executionTimestamps["C"] ?: fail("C should have executed")
        val dTime = executionTimestamps["D"] ?: fail("D should have executed")

        assertTrue(aTime < bTime, "A must execute before B")
        assertTrue(aTime < cTime, "A must execute before C")
        assertTrue(bTime < dTime, "B must execute before D")
        assertTrue(cTime < dTime, "C must execute before D")

        // 验证 B 和 C 是并行的（时间差应该很小）
        val bcDiff = kotlin.math.abs(bTime - cTime)
        assertTrue(bcDiff < 100, "B and C should execute in parallel (time diff < 100ms)")
    }

    @Test
    fun `should fail fast when configured`() = runBlocking {
        val executor = StepExecutor { step ->
            if (step.id == "B") {
                StepExecutionResult(step.id, false, "", "Simulated failure")
            } else {
                Thread.sleep(50)
                StepExecutionResult(step.id, true, "Done")
            }
        }

        val steps = listOf(
            TaskStep(id = "A", description = "Task A", dependencies = emptyList()),
            TaskStep(id = "B", description = "Task B", dependencies = emptyList()),
            TaskStep(id = "C", description = "Task C", dependencies = emptyList())
        )

        val plan = DagTaskPlan(
            taskId = "failfast_test",
            description = "Test fail fast",
            steps = steps,
            executionOrder = listOf(listOf("A", "B", "C")),
            estimatedSteps = 1,
            totalSteps = 3
        )

        val config = ParallelExecutionConfig(failFast = true)
        val parallelExecutor = ParallelTaskExecutor(config)
        val events = parallelExecutor.execute(plan, executor).toList()

        // 应该包含中止消息
        val thinkingEvents = events.filterIsInstance<AgentStreamEvent.Thinking>()
        assertTrue(
            thinkingEvents.any { it.message.contains("failFast") || it.message.contains("中止") },
            "Should emit fail-fast abort message"
        )
    }

    @Test
    fun `should continue on failure when not fail fast`() = runBlocking {
        val executedSteps = mutableListOf<String>()

        val executor = StepExecutor { step ->
            executedSteps.add(step.id)
            if (step.id == "B") {
                StepExecutionResult(step.id, false, "", "Simulated failure")
            } else {
                StepExecutionResult(step.id, true, "Done")
            }
        }

        val steps = listOf(
            TaskStep(id = "A", description = "Task A", dependencies = emptyList()),
            TaskStep(id = "B", description = "Task B", dependencies = emptyList()),
            TaskStep(id = "C", description = "Task C", dependencies = emptyList())
        )

        val plan = DagTaskPlan(
            taskId = "continue_test",
            description = "Test continue on failure",
            steps = steps,
            executionOrder = listOf(listOf("A", "B", "C")),
            estimatedSteps = 1,
            totalSteps = 3
        )

        val config = ParallelExecutionConfig(failFast = false)
        val parallelExecutor = ParallelTaskExecutor(config)
        val events = parallelExecutor.execute(plan, executor).toList()

        // 所有步骤都应该被执行（即使 B 失败）
        assertTrue(executedSteps.contains("A"), "A should have executed")
        assertTrue(executedSteps.contains("B"), "B should have executed")
        assertTrue(executedSteps.contains("C"), "C should have executed")

        // 应该包含继续执行的消息
        val thinkingEvents = events.filterIsInstance<AgentStreamEvent.Thinking>()
        assertTrue(
            thinkingEvents.any { it.message.contains("继续") || it.message.contains("continue") || it.message.contains("部分") },
            "Should emit continue message"
        )
    }

    @Test
    fun `should handle step timeout`() = runBlocking {
        val executor = StepExecutor { step ->
            kotlinx.coroutines.delay(500) // 模拟慢挂起操作
            StepExecutionResult(step.id, true, "Done")
        }

        val steps = listOf(
            TaskStep(id = "slow", description = "Slow task", dependencies = emptyList())
        )

        val plan = DagTaskPlan(
            taskId = "timeout_test",
            description = "Test timeout",
            steps = steps,
            executionOrder = listOf(listOf("slow")),
            estimatedSteps = 1,
            totalSteps = 1
        )

        val config = ParallelExecutionConfig(stepTimeoutMs = 100)
        val parallelExecutor = ParallelTaskExecutor(config)
        val events = parallelExecutor.execute(plan, executor).toList()

        // 应该包含超时错误
        val errors = events.filterIsInstance<AgentStreamEvent.Error>()
        assertTrue(
            errors.any { it.message.contains("timed out") || it.message.contains("超时") },
            "Should emit timeout error"
        )
    }

    @Test
    fun `AgentCoreStepExecutor should collect text deltas`() = runBlocking {
        val fakeEvents = kotlinx.coroutines.flow.flow {
            emit(AgentStreamEvent.TextDelta("Hello "))
            emit(AgentStreamEvent.TextDelta("World"))
            emit(AgentStreamEvent.Done)
        }

        val stepExecutor = AgentCoreStepExecutor { fakeEvents }
        val result = stepExecutor.execute(TaskStep(id = "test", description = "Test"))

        assertTrue(result.success)
        assertEquals("Hello World", result.output)
    }

    @Test
    fun `should detect cycle using DFS`() {
        val adjacencyList = mapOf(
            "A" to listOf("B"),
            "B" to listOf("C"),
            "C" to listOf("A")
        )

        val cycle = DagUtils.detectCycle(adjacencyList)
        assertTrue(cycle.isNotEmpty(), "Should detect cycle A -> B -> C -> A")
        assertEquals("A", cycle.first())
        assertEquals("A", cycle.last())
    }

    @Test
    fun `should return empty cycle for acyclic graph`() {
        val adjacencyList = mapOf(
            "A" to listOf("B", "C"),
            "B" to listOf("D"),
            "C" to listOf("D"),
            "D" to emptyList()
        )

        val cycle = DagUtils.detectCycle(adjacencyList)
        assertTrue(cycle.isEmpty(), "Should find no cycles in acyclic graph")
    }
}
