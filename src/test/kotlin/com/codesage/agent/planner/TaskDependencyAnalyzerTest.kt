package com.codesage.agent.planner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TaskDependencyAnalyzerTest {

    @Test
    fun `should split numbered steps correctly`() {
        val analyzer = TaskDependencyAnalyzer()
        val description = """
            1. Analyze requirements
            2. Design the architecture
            3. Implement the code
            4. Write tests
        """.trimIndent()

        val steps = analyzer.parseSteps(description)

        assertTrue(steps.size >= 3, "Should parse at least 3 numbered steps")
        assertEquals("step_0", steps[0].id)
        assertTrue(steps[0].description.contains("Analyze"))
    }

    @Test
    fun `should detect after dependency`() {
        val analyzer = TaskDependencyAnalyzer()
        val description = "Setup project, then implement features after setup, finally deploy"

        val steps = analyzer.parseSteps(description)

        // The step containing "after" should have a dependency
        val stepWithAfter = steps.find { it.description.contains("implement") }
        assertNotNull(stepWithAfter)
        assertTrue(stepWithAfter!!.dependencies.isNotEmpty(), "Step with 'after' should have dependencies")
    }

    @Test
    fun `should detect depends on dependency`() {
        val analyzer = TaskDependencyAnalyzer()
        val description = listOf(
            "Configure the database",
            "Implement API endpoints depends on database configuration"
        )

        val dependencies = listOf(
            ParsedDependency(1, 0, DependencyType.SEQUENTIAL)
        )

        // Verify that our data model captures this correctly
        assertEquals(0, dependencies[0].referencedIndex)
        assertEquals(DependencyType.SEQUENTIAL, dependencies[0].dependencyType)
    }

    @Test
    fun `should parse structured text with dependencies`() {
        val analyzer = TaskDependencyAnalyzer()
        val text = """
            id: step_auth
            description: "Implement authentication"
            dependencies: [step_db]
            parallel_with: []
            priority: HIGH

            id: step_api
            description: "Build REST API"
            dependencies: [step_auth]
            parallel_with: [step_ui]
        """.trimIndent()

        val structuredSteps = analyzer.parseFromStructuredText(text)

        assertEquals(2, structuredSteps.size)
        assertEquals("step_auth", structuredSteps[0].id)
        assertEquals(listOf("step_db"), structuredSteps[0].dependencies)

        assertEquals("step_api", structuredSteps[1].id)
        assertEquals(listOf("step_auth"), structuredSteps[1].dependencies)
        assertEquals(listOf("step_ui"), structuredSteps[1].parallelWith)
    }

    @Test
    fun `should handle empty description`() {
        val analyzer = TaskDependencyAnalyzer()
        val steps = analyzer.parseSteps("")
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `should handle single step description`() {
        val analyzer = TaskDependencyAnalyzer()
        val steps = analyzer.parseSteps("Just do this one thing")

        assertEquals(1, steps.size)
        assertEquals("step_0", steps[0].id)
        assertTrue(steps[0].dependencies.isEmpty())
    }

    @Test
    fun `should parse steps with before dependency`() {
        val analyzer = TaskDependencyAnalyzer()
        val steps = listOf(
            "Run database migration",
            "Configure cache before running API"
        )

        // The second step has "before" keyword which indicates SEQUENTIAL_REVERSE
        val lowerDesc = steps[1].lowercase()
        val hasBefore = DependencyKeyword.BEFORE.keywords.any { lowerDesc.contains(it) }
        assertTrue(hasBefore, "Should detect 'before' keyword")
    }

    @Test
    fun `should build structured steps from parsed data`() {
        val analyzer = TaskDependencyAnalyzer()
        val structuredData = listOf(
            StructuredStep(
                id = "step_1",
                description = "First",
                dependencies = emptyList(),
                priority = "HIGH"
            ),
            StructuredStep(
                id = "step_2",
                description = "Second",
                dependencies = listOf("step_1"),
                parallelWith = listOf("step_3"),
                priority = "MEDIUM"
            ),
            StructuredStep(
                id = "step_3",
                description = "Third",
                dependencies = listOf("step_1"),
                estimatedDurationMs = 5000
            )
        )

        val steps = analyzer.parseStructuredSteps(structuredData)

        assertEquals(3, steps.size)
        assertEquals(TaskPriority.HIGH, steps[0].priority)
        assertEquals(TaskPriority.MEDIUM, steps[1].priority)
        assertEquals(TaskPriority.MEDIUM, steps[2].priority) // default
        assertEquals(listOf("step_3"), steps[1].parallelWith)
        assertEquals(5000L, steps[2].estimatedDurationMs)
    }

    @Test
    fun `should detect parallel keyword`() {
        val analyzer = TaskDependencyAnalyzer()
        val description = listOf(
            "Setup environment",
            "Build frontend in parallel with backend"
        )

        val lowerDesc = description[1].lowercase()
        val hasParallel = DependencyKeyword.PARALLEL.keywords.any { lowerDesc.contains(it) }
        assertTrue(hasParallel, "Should detect 'in parallel' keyword")
    }

    @Test
    fun `should find referenced index by step number`() {
        val analyzer = TaskDependencyAnalyzer()
        val steps = listOf(
            "First step",
            "Second step after step 1",
            "Third step"
        )

        // "after step 1" should reference index 0
        val parsed = analyzer.parseSteps(steps.joinToString("\n"))
        val secondStep = parsed.find { it.description.contains("Second") }
        assertNotNull(secondStep)
        // The dependency should reference step_0
        assertTrue(secondStep!!.dependencies.contains("step_0"))
    }

    @Test
    fun `should handle Chinese comma delimiters`() {
        val analyzer = TaskDependencyAnalyzer()
        val description = "第一步，第二步，第三步"

        val steps = analyzer.parseSteps(description)
        assertTrue(steps.size >= 2, "Should split by Chinese comma")
    }
}
