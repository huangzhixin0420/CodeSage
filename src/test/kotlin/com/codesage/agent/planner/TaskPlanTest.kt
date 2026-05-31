package com.codesage.agent.planner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TaskPlanTest {

    @Test
    fun `should parse structured plan from LLM output`() {
        val analyzer = TaskDependencyAnalyzer()
        val yamlText = """
            id: step_1
            description: "Analyze project structure"
            priority: HIGH
            dependencies: []

            id: step_2
            description: "Implement core logic"
            priority: HIGH
            dependencies: [step_1]

            id: step_3
            description: "Write unit tests"
            priority: MEDIUM
            dependencies: [step_2]
        """.trimIndent()

        val structuredSteps = analyzer.parseFromStructuredText(yamlText)
        assertEquals(3, structuredSteps.size)
        assertEquals("step_1", structuredSteps[0].id)
        assertEquals("Analyze project structure", structuredSteps[0].description)
        assertTrue(structuredSteps[0].dependencies.isNullOrEmpty())

        assertEquals("step_2", structuredSteps[1].id)
        assertEquals(listOf("step_1"), structuredSteps[1].dependencies)

        assertEquals("step_3", structuredSteps[2].id)
        assertEquals(listOf("step_2"), structuredSteps[2].dependencies)
    }

    @Test
    fun `should detect parallel steps`() {
        val steps = listOf(
            TaskStep(id = "step_1", description = "Setup environment", dependencies = emptyList()),
            TaskStep(
                id = "step_2",
                description = "Build frontend",
                dependencies = listOf("step_1"),
                parallelWith = listOf("step_3")
            ),
            TaskStep(
                id = "step_3",
                description = "Build backend",
                dependencies = listOf("step_1"),
                parallelWith = listOf("step_2")
            ),
            TaskStep(id = "step_4", description = "Deploy", dependencies = listOf("step_2", "step_3"))
        )

        val executionOrder = DagUtils.topologicalSort(steps)

        // step_2 和 step_3 应该在同一组（并行）
        val parallelGroup = executionOrder.find { it.contains("step_2") && it.contains("step_3") }
        assertNotNull(parallelGroup, "step_2 and step_3 should be in the same parallel group")

        // step_4 应该在后面的组
        val step4GroupIndex = executionOrder.indexOfFirst { it.contains("step_4") }
        val step2GroupIndex = executionOrder.indexOfFirst { it.contains("step_2") }
        assertTrue(step4GroupIndex > step2GroupIndex, "step_4 should execute after step_2")
    }

    @Test
    fun `should sort tasks topologically`() {
        val steps = listOf(
            TaskStep(id = "C", description = "Task C", dependencies = listOf("A")),
            TaskStep(id = "A", description = "Task A", dependencies = emptyList()),
            TaskStep(id = "D", description = "Task D", dependencies = listOf("B", "C")),
            TaskStep(id = "B", description = "Task B", dependencies = listOf("A"))
        )

        val executionOrder = DagUtils.topologicalSort(steps)

        // A 必须在第一位
        assertEquals(listOf("A"), executionOrder[0])

        // B 和 C 可以在第二位（都依赖 A）
        val secondGroup = executionOrder[1].toSet()
        assertEquals(setOf("B", "C"), secondGroup)

        // D 必须在最后（依赖 B 和 C）
        assertEquals(listOf("D"), executionOrder[2])
    }

    @Test
    fun `should detect circular dependency`() {
        val steps = listOf(
            TaskStep(id = "A", description = "Task A", dependencies = listOf("C")),
            TaskStep(id = "B", description = "Task B", dependencies = listOf("A")),
            TaskStep(id = "C", description = "Task C", dependencies = listOf("B"))
        )

        val exception = assertThrows(CircularDependencyException::class.java) {
            DagUtils.topologicalSort(steps)
        }

        assertTrue(exception.message?.contains("Circular dependency detected") == true)
    }

    @Test
    fun `should validate DAG correctly`() {
        val validSteps = listOf(
            TaskStep(id = "step_1", description = "First", dependencies = emptyList()),
            TaskStep(id = "step_2", description = "Second", dependencies = listOf("step_1"))
        )

        val validResult = DagUtils.validateDag(validSteps)
        assertTrue(validResult.isValid)

        val invalidSteps = listOf(
            TaskStep(id = "step_1", description = "First", dependencies = listOf("step_2")),
            TaskStep(id = "step_2", description = "Second", dependencies = listOf("step_1"))
        )

        val invalidResult = DagUtils.validateDag(invalidSteps)
        assertFalse(invalidResult.isValid)
        assertNotNull(invalidResult.errorMessage)
    }

    @Test
    fun `should calculate critical path`() {
        val steps = listOf(
            TaskStep(id = "A", description = "Task A", dependencies = emptyList(), estimatedDurationMs = 100),
            TaskStep(id = "B", description = "Task B", dependencies = listOf("A"), estimatedDurationMs = 200),
            TaskStep(id = "C", description = "Task C", dependencies = listOf("A"), estimatedDurationMs = 50),
            TaskStep(id = "D", description = "Task D", dependencies = listOf("B", "C"), estimatedDurationMs = 100)
        )

        val executionOrder = DagUtils.topologicalSort(steps)
        val criticalPath = DagUtils.calculateCriticalPath(steps, executionOrder)

        // Critical path should be A -> B -> D (longest path: 100 + 200 + 100 = 400ms)
        assertEquals(listOf("A", "B", "D"), criticalPath)
    }

    @Test
    fun `should parse natural language dependencies`() {
        val analyzer = TaskDependencyAnalyzer()
        val description = """
            1. Setup the database schema
            2. Implement user authentication after step 1
            3. Build frontend components independently
            4. Deploy application after step 2
        """.trimIndent()

        val steps = analyzer.parseSteps(description)

        assertTrue(steps.size >= 3, "Should parse multiple steps from numbered list")

        // Step 2 should depend on step 1
        val step2 = steps.find { it.description.contains("authentication") }
        assertNotNull(step2)
        assertTrue(step2!!.dependencies.isNotEmpty(), "Step with 'after' should have dependencies")
    }

    @Test
    fun `DagTaskPlan should support adjacency list and dependents lookup`() {
        val steps = listOf(
            TaskStep(id = "root", description = "Root", dependencies = emptyList()),
            TaskStep(id = "child1", description = "Child 1", dependencies = listOf("root")),
            TaskStep(id = "child2", description = "Child 2", dependencies = listOf("root"))
        )

        val plan = DagTaskPlan(
            taskId = "test_task",
            description = "Test",
            steps = steps,
            executionOrder = DagUtils.topologicalSort(steps),
            estimatedSteps = 2,
            totalSteps = 3
        )

        val adjacency = plan.toAdjacencyList()
        assertEquals(emptyList<String>(), adjacency["root"])
        assertEquals(listOf("root"), adjacency["child1"])

        val dependents = plan.getDependents("root")
        assertEquals(setOf("child1", "child2"), dependents.toSet())
    }
}
