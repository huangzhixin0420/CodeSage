package com.codesage.agent.planner

import com.codesage.agent.core.AgentStreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * 端到端能力演示测试 — 展示 TaskPlanner 各核心能力的实际输出
 *
 * 运行方式:
 *   ./gradlew test --tests "com.codesage.agent.planner.PlannerCapabilityDemoTest" --info
 *
 * 覆盖能力:
 *   1. 自然语言 → 步骤拆分 + 依赖识别
 *   2. 拓扑排序 + 并行组生成
 *   3. 关键路径计算
 *   4. 循环依赖检测
 *   5. 计划验证
 *   6. 计划审批工作流 (流式事件)
 *   7. DAG 计划执行 + 实时事件流
 *   8. 向后兼容 (旧版 TaskPlan API)
 */
class PlannerCapabilityDemoTest {

    private fun section(title: String) {
        println("\n" + "=".repeat(72))
        println("▶ $title")
        println("=".repeat(72))
    }

    @Test
    fun `demonstrate full planner capabilities`() = runBlocking {
        val planner = TaskPlanner()

        // ─────────────────────────────────────────────────────────
        section("1) 自然语言任务分解 + 依赖识别")
        // ─────────────────────────────────────────────────────────
        val naturalLanguage = """
            1. Analyze the codebase structure
            2. Refactor the main module after step 1
            3. Write tests for the refactored code after step 2
            4. Update documentation in parallel with step 3
            5. Review the final code
        """.trimIndent()

        val task = planner.createTask(
            description = naturalLanguage,
            goal = "Refactor and test codebase"
        )
        val dagPlan = planner.decomposeToDagPlan(task)

        println("原始自然语言输入 (${naturalLanguage.lines().size} 行):")
        naturalLanguage.lines().forEach { println("   │ $it") }
        println()
        println("识别出 ${dagPlan.totalSteps} 个步骤:")
        dagPlan.steps.forEach { s ->
            val deps = if (s.dependencies.isEmpty()) "(无依赖)" else "depends on ${s.dependencies}"
            println("   • [${s.id}] ${s.description}  $deps")
        }

        // ─────────────────────────────────────────────────────────
        section("2) 拓扑排序 + 并行执行组")
        // ─────────────────────────────────────────────────────────
        println("并行执行组 (同组内可并行,组间顺序执行):")
        dagPlan.executionOrder.forEachIndexed { i, group ->
            val marker = if (group.size > 1) "⚡ 并行" else "   顺序"
            println("   Layer $i  $marker  →  ${group.joinToString(", ")}")
        }

        // ─────────────────────────────────────────────────────────
        section("3) 关键路径计算 (调度优化依据)")
        // ─────────────────────────────────────────────────────────
        println("关键路径: ${dagPlan.criticalPath.joinToString(" → ")}")
        println("(关键路径上的步骤总耗时决定整体最短完成时间)")

        // ─────────────────────────────────────────────────────────
        section("4) 计划验证 (DAG 完整性检查)")
        // ─────────────────────────────────────────────────────────
        val validation = planner.validatePlan(dagPlan)
        println("验证结果: ${if (validation.isValid) "✅ VALID" else "❌ INVALID"}")
        if (validation.warnings.isNotEmpty()) {
            println("警告:")
            validation.warnings.forEach { println("   ⚠ $it") }
        }

        // ─────────────────────────────────────────────────────────
        section("5) 循环依赖检测 (容错能力)")
        // ─────────────────────────────────────────────────────────
        val cyclicSteps = listOf(
            TaskStep(id = "A", description = "A", dependencies = listOf("C")),
            TaskStep(id = "B", description = "B", dependencies = listOf("A")),
            TaskStep(id = "C", description = "C", dependencies = listOf("B"))
        )
        // ADAPTED: detectCircularDependencies returns List<String> (the cycle path, empty if none)
        val cycleNodes = planner.detectCircularDependencies(cyclicSteps)
        val cycleResultHasCycle = cycleNodes.isNotEmpty()
        println("故意构造环: A → C → B → A")
        println("检测结果: hasCycle=$cycleResultHasCycle, cycleNodes=$cycleNodes")

        // ─────────────────────────────────────────────────────────
        section("6) 结构化输入 → DAG (精确控制场景)")
        // ─────────────────────────────────────────────────────────
        val structured = listOf(
            StructuredStep(id = "design", description = "API design", dependencies = emptyList(), priority = "HIGH", estimatedDurationMs = 200),
            StructuredStep(id = "impl_backend", description = "Backend impl", dependencies = listOf("design"), estimatedDurationMs = 400),
            StructuredStep(id = "impl_frontend", description = "Frontend impl", dependencies = listOf("design"), estimatedDurationMs = 400),
            StructuredStep(id = "integration", description = "Integration", dependencies = listOf("impl_backend", "impl_frontend"), estimatedDurationMs = 100)
        )
        val sPlan = planner.buildDagPlanFromStructured("demo_struct", "Structured demo", structured)
        println("并行组:")
        sPlan.executionOrder.forEachIndexed { i, g ->
            println("   Layer $i  →  ${g.joinToString(", ")}")
        }
        println("关键路径: ${sPlan.criticalPath.joinToString(" → ")}  (总耗时=${sPlan.criticalPath.sumOf { id -> structured.find { it.id == id }!!.estimatedDurationMs!! }}ms)")

        // ─────────────────────────────────────────────────────────
        section("7) 计划审批工作流 (流式事件)")
        // ─────────────────────────────────────────────────────────
        val approvalFlow = planner.requestPlanApproval(dagPlan)
        // 模拟用户延迟 50ms 后批准
        launch {
            kotlinx.coroutines.delay(50)
            planner.approvePlan(dagPlan.taskId)
        }
        val approvalEvents = approvalFlow.toList()
        println("审批事件流 (共 ${approvalEvents.size} 个事件):")
        approvalEvents.forEach { e ->
            println("   📡 ${e::class.simpleName}")
        }

        // ─────────────────────────────────────────────────────────
        section("8) DAG 计划执行 + 实时 Thinking 事件")
        // ─────────────────────────────────────────────────────────
        val execPlan = DagTaskPlan(
            taskId = "exec_demo",
            description = "Execution demo",
            steps = listOf(
                TaskStep(id = "setup",    description = "Setup env",    dependencies = emptyList(),                    estimatedDurationMs = 50),
                TaskStep(id = "build_a",  description = "Build module A", dependencies = listOf("setup"),               estimatedDurationMs = 100),
                TaskStep(id = "build_b",  description = "Build module B", dependencies = listOf("setup"),               estimatedDurationMs = 100),
                TaskStep(id = "verify",   description = "Verify",         dependencies = listOf("build_a", "build_b"), estimatedDurationMs = 80)
            ),
            executionOrder = listOf(
                listOf("setup"),
                listOf("build_a", "build_b"),
                listOf("verify")
            ),
            estimatedSteps = 3,
            totalSteps = 4
        )
        val log = mutableListOf<String>()
        // ADAPTED: executeDagPlan takes a StepExecutor SAM returning StepExecutionResult (not raw String)
        val events = planner.executeDagPlan(execPlan) { step ->
            log.add(step.id)
            StepExecutionResult(stepId = step.id, success = true, output = "done:${step.id}")
        }.toList()
        println("执行顺序: ${log.joinToString(" → ")}")
        println("生成 ${events.size} 个流式事件:")
        events.forEach { e ->
            // ADAPTED: ParallelTaskExecutor only emits Thinking / Error events (no StepStarted/Completed/Failed)
            when (e) {
                is AgentStreamEvent.Thinking -> println("   💭 Thinking: ${e.message}")
                is AgentStreamEvent.Error    -> println("   ✗ Error: ${e.message}")
                else -> println("   • ${e::class.simpleName}")
            }
        }

        // ─────────────────────────────────────────────────────────
        section("9) 向后兼容 (旧版 TaskPlan API)")
        // ─────────────────────────────────────────────────────────
        val legacyTask = planner.createTask("Step A, Step B, Step C", "Legacy compat")
        val legacyPlan = planner.decomposeTask(legacyTask, emptyList())
        println("旧版 API 仍可用: totalSubTasks=${legacyPlan.totalSubTasks}, " +
                "executionOrder=${legacyPlan.executionOrder.size} 层")

        // ─────────────────────────────────────────────────────────
        section("✓ 演示完成")
        // ─────────────────────────────────────────────────────────
    }
}
