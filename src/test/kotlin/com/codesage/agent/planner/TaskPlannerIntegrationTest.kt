package com.codesage.agent.planner

import com.codesage.agent.core.AgentStreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TaskPlannerIntegrationTest {

    @Test
    fun `should decompose task and create valid DAG plan`() {
        val planner = TaskPlanner()
        val task = planner.createTask(
            description = """
                1. Analyze the codebase structure
                2. Refactor the main module after step 1
                3. Write tests for the refactored code after step 2
                4. Update documentation in parallel with step 3
            """.trimIndent(),
            goal = "Refactor and test codebase"
        )

        val dagPlan = planner.decomposeToDagPlan(task)

        assertTrue(dagPlan.totalSteps >= 3)
        assertTrue(dagPlan.executionOrder.isNotEmpty())

        // First step should have no dependencies
        val firstGroup = dagPlan.executionOrder.first()
        val firstStep = dagPlan.steps.find { it.id == firstGroup.first() }
        assertNotNull(firstStep)
        assertTrue(firstStep!!.dependencies.isEmpty())
    }

    @Test
    fun `should build DAG plan from structured steps`() {
        val planner = TaskPlanner()
        val structuredSteps = listOf(
            StructuredStep(id = "setup", description = "Setup", dependencies = emptyList(), priority = "HIGH"),
            StructuredStep(id = "build", description = "Build", dependencies = listOf("setup")),
            StructuredStep(id = "test", description = "Test", dependencies = listOf("build")),
            StructuredStep(id = "deploy", description = "Deploy", dependencies = listOf("test"))
        )

        val plan = planner.buildDagPlanFromStructured("integration_test", "Integration test", structuredSteps)

        assertEquals(4, plan.totalSteps)
        assertEquals(listOf("setup"), plan.executionOrder[0])
        assertEquals(listOf("build"), plan.executionOrder[1])
        assertEquals(listOf("test"), plan.executionOrder[2])
        assertEquals(listOf("deploy"), plan.executionOrder[3])
    }

    @Test
    fun `should validate plan and detect issues`() {
        val planner = TaskPlanner()

        val validPlan = DagTaskPlan(
            taskId = "valid",
            description = "Valid",
            steps = listOf(
                TaskStep(id = "A", description = "A", dependencies = emptyList()),
                TaskStep(id = "B", description = "B", dependencies = listOf("A"))
            ),
            executionOrder = listOf(listOf("A"), listOf("B")),
            estimatedSteps = 2,
            totalSteps = 2
        )

        val result = planner.validatePlan(validPlan)
        assertTrue(result.isValid)

        val invalidSteps = listOf(
            TaskStep(id = "X", description = "X", dependencies = listOf("Y")),
            TaskStep(id = "Y", description = "Y", dependencies = listOf("X"))
        )
        val cycle = planner.detectCircularDependencies(invalidSteps)
        assertTrue(cycle.isNotEmpty())
    }

    @Test
    fun `should execute DAG plan with parallel groups`() = runBlocking {
        val planner = TaskPlanner()
        val executionLog = mutableListOf<String>()

        val executor = StepExecutor { step ->
            executionLog.add(step.id)
            StepExecutionResult(step.id, true, "Completed ${step.description}")
        }

        val steps = listOf(
            TaskStep(id = "prep", description = "Prepare", dependencies = emptyList()),
            TaskStep(id = "frontend", description = "Build frontend", dependencies = listOf("prep")),
            TaskStep(id = "backend", description = "Build backend", dependencies = listOf("prep")),
            TaskStep(id = "integrate", description = "Integration", dependencies = listOf("frontend", "backend"))
        )

        val plan = DagTaskPlan(
            taskId = "exec_test",
            description = "Execution test",
            steps = steps,
            executionOrder = DagUtils.topologicalSort(steps),
            estimatedSteps = 3,
            totalSteps = 4
        )

        val events = planner.executeDagPlan(plan, executor).toList()

        // All steps should be executed
        assertTrue(executionLog.contains("prep"))
        assertTrue(executionLog.contains("frontend"))
        assertTrue(executionLog.contains("backend"))
        assertTrue(executionLog.contains("integrate"))

        // Verify execution order through thinking events
        val thinkingEvents = events.filterIsInstance<AgentStreamEvent.Thinking>()
        assertTrue(thinkingEvents.any { it.message.contains("开始执行") })
        assertTrue(thinkingEvents.any { it.message.contains("完成") })
    }

    @Test
    fun `should handle approval workflow end to end`() = runBlocking {
        val planner = TaskPlanner()
        val plan = DagTaskPlan(
            taskId = "approval_e2e",
            description = "E2E approval test",
            steps = listOf(
                TaskStep(id = "step_1", description = "Step 1", dependencies = emptyList())
            ),
            executionOrder = listOf(listOf("step_1")),
            estimatedSteps = 1,
            totalSteps = 1
        )

        val flow = planner.requestPlanApproval(plan)

        // Approve in background
        launch {
            kotlinx.coroutines.delay(50)
            planner.approvePlan("approval_e2e")
        }

        val events = flow.toList()
        assertTrue(events.any { it is AgentStreamEvent.PlanGenerated })
        assertTrue(events.any { it is AgentStreamEvent.PlanApproved })
    }

    @Test
    fun `should maintain backward compatibility with TaskPlan`() {
        val planner = TaskPlanner()
        val task = planner.createTask("Step 1, Step 2, Step 3", "Test backward compat")

        val taskPlan = planner.decomposeTask(task, emptyList())

        assertNotNull(taskPlan)
        assertTrue(taskPlan.totalSubTasks >= 2)
        assertTrue(taskPlan.executionOrder.isNotEmpty())
        assertTrue(task.subTasks.isNotEmpty())
    }

    @Test
    fun `should calculate critical path for complex plan`() {
        val planner = TaskPlanner()
        val structuredSteps = listOf(
            StructuredStep(id = "A", description = "A", dependencies = emptyList(), estimatedDurationMs = 100),
            StructuredStep(id = "B", description = "B", dependencies = listOf("A"), estimatedDurationMs = 200),
            StructuredStep(id = "C", description = "C", dependencies = listOf("A"), estimatedDurationMs = 50),
            StructuredStep(id = "D", description = "D", dependencies = listOf("B"), estimatedDurationMs = 100),
            StructuredStep(id = "E", description = "E", dependencies = listOf("C"), estimatedDurationMs = 300),
            StructuredStep(id = "F", description = "F", dependencies = listOf("D", "E"), estimatedDurationMs = 50)
        )

        val plan = planner.buildDagPlanFromStructured("cp_test", "Critical path test", structuredSteps)

        // Critical path: A(100) -> C(50) -> E(300) -> F(50) = 500ms
        assertEquals(listOf("A", "C", "E", "F"), plan.criticalPath)
    }
}
