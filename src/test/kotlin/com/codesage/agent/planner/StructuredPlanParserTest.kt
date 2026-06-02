package com.codesage.agent.planner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T4.3 修复验证测试：Planner 输出结构化解析
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T4.3）：
 * - [x] 单元测试：合法 YAML 解析为正确 DagTaskPlan
 * - [x] 单元测试：循环依赖的 YAML 被识别并拒绝
 * - [x] 单元测试：LLM 输出 markdown 包裹的 yaml 时正确剥离
 */
class StructuredPlanParserTest {

    // === extractCodeBlock 测试 ===

    @Test
    fun `extractCodeBlock strips yaml fence`() {
        val text = """
            Here is my plan:
            ```yaml
            plan:
              steps:
                - id: step_1
                  description: "do something"
            ```
        """.trimIndent()
        val extracted = StructuredPlanParser.extractCodeBlock(text)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("plan:"))
        assertTrue(extracted.contains("steps:"))
    }

    @Test
    fun `extractCodeBlock strips json fence`() {
        val text = """
            ```json
            {"steps": [{"id": "s1"}]}
            ```
        """.trimIndent()
        val extracted = StructuredPlanParser.extractCodeBlock(text)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("\"steps\""))
    }

    @Test
    fun `extractCodeBlock returns null when no fence`() {
        val text = "no code block here"
        assertNull(StructuredPlanParser.extractCodeBlock(text))
    }

    // === YAML 解析 ===

    @Test
    fun `parses valid YAML plan correctly`() {
        val yaml = """
            plan:
              steps:
                - id: step_1
                  description: "Read the file"
                  priority: HIGH
                  dependencies: []
                  parallel_with: []
                  estimated_duration_ms: 5000
                - id: step_2
                  description: "Edit the file"
                  priority: MEDIUM
                  dependencies: [step_1]
                  parallel_with: []
                  estimated_duration_ms: 10000
        """.trimIndent()
        val result = StructuredPlanParser.parseYamlPlan(yaml)
        assertEquals(2, result.size)
        assertEquals("step_1", result[0].id)
        assertEquals("Read the file", result[0].description)
        assertEquals(TaskPriority.HIGH, result[0].priority)
        assertEquals(emptyList<String>(), result[0].dependencies)
        assertEquals(5000L, result[0].estimatedDurationMs)
        assertEquals(listOf("step_1"), result[1].dependencies)
    }

    @Test
    fun `parseYamlList parses inline list`() {
        assertEquals(listOf("a", "b", "c"), StructuredPlanParser.parseYamlList("[a, b, c]"))
        assertEquals(listOf("step_1", "step_2"), StructuredPlanParser.parseYamlList("[step_1, step_2]"))
        assertEquals(listOf("quoted"), StructuredPlanParser.parseYamlList("[\"quoted\"]"))
        assertEquals(emptyList<String>(), StructuredPlanParser.parseYamlList("[]"))
    }

    // === 主 parse 方法 ===

    @Test
    fun `parse returns Success for valid markdown-wrapped YAML`() {
        val text = """
            Here is the plan:
            ```yaml
            plan:
              steps:
                - id: step_1
                  description: "first"
                  dependencies: []
                  estimated_duration_ms: 1000
                - id: step_2
                  description: "second"
                  dependencies: [step_1]
                  estimated_duration_ms: 2000
            ```
        """.trimIndent()
        val result = StructuredPlanParser.parse(text)
        assertTrue(result is StructuredPlanParser.ParseResult.Success, "expected Success, got $result")
        result as StructuredPlanParser.ParseResult.Success
        assertEquals(StructuredPlanParser.ParseStrategy.YAML, result.strategy)
        assertEquals(2, result.steps.size)
    }

    @Test
    fun `parse returns Success for JSON wrapped in markdown`() {
        val text = """
            ```json
            {"steps": [{"id": "j1", "description": "do json", "dependencies": []}]}
            ```
        """.trimIndent()
        val result = StructuredPlanParser.parse(text)
        assertTrue(result is StructuredPlanParser.ParseResult.Success, "expected Success, got $result")
        result as StructuredPlanParser.ParseResult.Success
        // YAML 解析器可能也会接受这个输入（JSON 是 YAML 的子集）— 接受任一策略
        assertTrue(result.strategy == StructuredPlanParser.ParseStrategy.JSON || result.strategy == StructuredPlanParser.ParseStrategy.YAML)
        assertEquals(1, result.steps.size)
        assertEquals("j1", result.steps[0].id)
    }

    @Test
    fun `parse returns Failure for empty input`() {
        val result = StructuredPlanParser.parse("")
        assertTrue(result is StructuredPlanParser.ParseResult.Failure)
    }

    @Test
    fun `parse falls back to natural language when no code block`() {
        val text = """
            1. First, read the file
            2. Then edit the file
            3. Finally, run the tests
        """.trimIndent()
        val result = StructuredPlanParser.parse(text)
        assertTrue(result is StructuredPlanParser.ParseResult.Success)
        result as StructuredPlanParser.ParseResult.Success
        assertEquals(StructuredPlanParser.ParseStrategy.NATURAL_LANGUAGE, result.strategy)
    }

    // === 验证规则 ===

    @Test
    fun `rejects YAML with circular dependency`() {
        val yaml = """
            plan:
              steps:
                - id: step_1
                  description: "first"
                  dependencies: [step_2]
                - id: step_2
                  description: "second"
                  dependencies: [step_1]
        """.trimIndent()
        val result = StructuredPlanParser.parse("```yaml\n$yaml\n```")
        assertTrue(result is StructuredPlanParser.ParseResult.Failure, "expected Failure, got $result")
        result as StructuredPlanParser.ParseResult.Failure
        assertTrue(
            result.reason.contains("Circular") || result.reason.contains("circular"),
            "failure reason should mention circular, got: ${result.reason}"
        )
    }

    @Test
    fun `rejects YAML with unknown dependency reference`() {
        val yaml = """
            plan:
              steps:
                - id: step_1
                  description: "first"
                  dependencies: [nonexistent]
        """.trimIndent()
        val result = StructuredPlanParser.parse("```yaml\n$yaml\n```")
        assertTrue(result is StructuredPlanParser.ParseResult.Failure)
        result as StructuredPlanParser.ParseResult.Failure
        assertTrue(result.reason.contains("unknown"))
    }

    @Test
    fun `rejects YAML with duplicate step ids`() {
        val yaml = """
            plan:
              steps:
                - id: step_1
                  description: "first"
                - id: step_1
                  description: "duplicate"
        """.trimIndent()
        val result = StructuredPlanParser.parse("```yaml\n$yaml\n```")
        assertTrue(result is StructuredPlanParser.ParseResult.Failure)
        result as StructuredPlanParser.ParseResult.Failure
        assertTrue(result.reason.contains("Duplicate"))
    }

    @Test
    fun `rejects YAML with unknown parallel_with reference`() {
        val yaml = """
            plan:
              steps:
                - id: step_1
                  description: "first"
                  parallel_with: [ghost]
        """.trimIndent()
        val result = StructuredPlanParser.parse("```yaml\n$yaml\n```")
        assertTrue(result is StructuredPlanParser.ParseResult.Failure)
        result as StructuredPlanParser.ParseResult.Failure
        assertTrue(result.reason.contains("parallel_with") || result.reason.contains("unknown"))
    }

    // === parseAndBuildPlan ===

    @Test
    fun `parseAndBuildPlan returns plan with execution order`() {
        val text = """
            ```yaml
            plan:
              steps:
                - id: step_1
                  description: "first"
                  dependencies: []
                  estimated_duration_ms: 1000
                - id: step_2
                  description: "second"
                  dependencies: [step_1]
                  estimated_duration_ms: 1000
                - id: step_3
                  description: "third (parallel with step_2)"
                  dependencies: [step_1]
                  parallel_with: [step_2]
                  estimated_duration_ms: 1000
            ```
        """.trimIndent()
        val plan = StructuredPlanParser.parseAndBuildPlan(text, taskId = "t1", description = "complex task")
        assertNotNull(plan)
        assertEquals("t1", plan!!.taskId)
        assertEquals(3, plan.steps.size)
        assertEquals(2, plan.executionOrder.size, "should be 2 levels: [step_1] then [step_2, step_3]")
        assertEquals(listOf("step_1"), plan.executionOrder[0])
        assertEquals(setOf("step_2", "step_3"), plan.executionOrder[1].toSet())
    }

    @Test
    fun `parseAndBuildPlan returns null for invalid plan`() {
        val text = """
            ```yaml
            plan:
              steps:
                - id: step_1
                  description: "first"
                  dependencies: [nonexistent]
            ```
        """.trimIndent()
        val plan = StructuredPlanParser.parseAndBuildPlan(text)
        assertNull(plan)
    }

    @Test
    fun `parseAndBuildPlan handles natural language fallback`() {
        val text = """
            1. Read the source file
            2. Modify the function body
            3. Run the test suite
        """.trimIndent()
        val plan = StructuredPlanParser.parseAndBuildPlan(text, taskId = "t1", description = "fallback test")
        assertNotNull(plan, "NL fallback should produce a plan for numbered list")
        assertEquals(3, plan!!.steps.size)
    }

    // === 复杂场景 ===

    @Test
    fun `parses deeply nested plan with all features`() {
        val text = """
            Generated plan for the user request.

            ```yaml
            plan:
              steps:
                - id: step_1
                  description: "Initial setup"
                  priority: CRITICAL
                  dependencies: []
                  parallel_with: []
                  estimated_duration_ms: 60000
                - id: step_2
                  description: "Create database schema"
                  priority: HIGH
                  dependencies: [step_1]
                  parallel_with: []
                  estimated_duration_ms: 30000
                - id: step_3
                  description: "Implement API endpoints"
                  priority: HIGH
                  dependencies: [step_2]
                  parallel_with: [step_4]
                  estimated_duration_ms: 120000
                - id: step_4
                  description: "Write unit tests"
                  priority: MEDIUM
                  dependencies: [step_2]
                  parallel_with: [step_3]
                  estimated_duration_ms: 90000
                - id: step_5
                  description: "Deploy"
                  priority: CRITICAL
                  dependencies: [step_3, step_4]
                  parallel_with: []
                  estimated_duration_ms: 60000
            ```
        """.trimIndent()
        val plan = StructuredPlanParser.parseAndBuildPlan(text, taskId = "t1", description = "build app")
        assertNotNull(plan)
        assertEquals(5, plan!!.steps.size)
        assertEquals(4, plan.executionOrder.size, "4 levels: [step_1] / [step_2] / [step_3, step_4] / [step_5]")
        assertEquals(listOf("step_1"), plan.executionOrder[0])
        assertEquals(listOf("step_2"), plan.executionOrder[1])
        assertEquals(setOf("step_3", "step_4"), plan.executionOrder[2].toSet())
        assertEquals(listOf("step_5"), plan.executionOrder[3])
        // 关键路径应包含 step_1, step_2, step_3 (or 4), step_5
        assertTrue(plan.criticalPath.contains("step_1"))
        assertTrue(plan.criticalPath.contains("step_5"))
    }

    @Test
    fun `strips comments from YAML`() {
        val yaml = """
            # This is a comment
            plan:
              # Another comment
              steps:
                - id: step_1  # inline comment
                  description: "first"
                  dependencies: []
            """.trimIndent()
        val steps = StructuredPlanParser.parseYamlPlan(yaml)
        assertEquals(1, steps.size)
        assertEquals("step_1", steps[0].id)
    }
}
