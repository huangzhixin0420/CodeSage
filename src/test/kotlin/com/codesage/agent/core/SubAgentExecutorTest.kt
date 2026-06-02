package com.codesage.agent.core

import com.codesage.agent.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * SubAgentExecutor 单元测试
 *
 * 覆盖：
 * - P0 #1: delegate_task 工具已注册到默认 ToolRegistry
 * - P1 #3: 子 Agent prompt 继承父 prompt + sub-agent section
 * - P1 #4: createToolRegistryForToolset 按 toolset 实际过滤
 * - P1 #5: 子 Agent 仍注册 memory 工具
 * - P2 #7: 递归深度限制
 */
class SubAgentExecutorTest {

    // ===== P0 #1: delegate_task 工具注册 =====

    @Test
    fun `delegate_task should be registered in default ToolRegistry`() {
        val registry = ToolRegistry.createDefault()
        assertNotNull(registry.get("delegate_task"), "delegate_task must be registered for LLM visibility")
    }

    @Test
    fun `default ToolRegistry should have many tools including delegate_task`() {
        val registry = ToolRegistry.createDefault()
        val all = registry.getAllTools()
        assertTrue(all.size > 20, "Default registry should have many tools, got ${all.size}")
        assertTrue(all.any { it.name == "delegate_task" })
        assertTrue(all.any { it.name == "read_file" })
    }

    // ===== P1 #3: prompt 构造（纯函数） =====

    @Test
    fun `buildSubAgentPrompt should include parent prompt verbatim`() {
        val parentPrompt = "PARENT_PROMPT_MARKER_xyz"
        val result = SubAgentExecutor.buildSubAgentPrompt(
            taskDescription = "Implement function foo",
            toolset = "dev",
            parentPrompt = parentPrompt,
            depth = 0
        )
        assertTrue(result.contains(parentPrompt), "Sub-agent prompt should include parent prompt")
    }

    @Test
    fun `buildSubAgentPrompt should add Sub-Agent Context section with task and depth`() {
        val result = SubAgentExecutor.buildSubAgentPrompt(
            taskDescription = "TASK_MARKER_abc",
            toolset = "research",
            parentPrompt = "parent",
            depth = 1
        )
        assertTrue(result.contains("Sub-Agent Context (depth=1)"), "Should label sub-agent section with depth")
        assertTrue(result.contains("TASK_MARKER_abc"), "Should include the task description")
        assertTrue(result.contains("research"), "Should mention the toolset name")
        assertTrue(result.contains("No new delegation"), "Should include the 'no new delegation' rule")
        assertTrue(result.contains("Stay focused"), "Should include focus rule")
    }

    @Test
    fun `buildSubAgentPrompt depth value should match input`() {
        for (d in 0..3) {
            val result = SubAgentExecutor.buildSubAgentPrompt("t", "dev", "p", d)
            assertTrue(result.contains("depth=$d"), "depth=$d should appear in prompt")
        }
    }

    // ===== P1 #4: 工具集过滤 =====

    @Test
    fun `dev toolset should keep all tools`() {
        val parent = AgentCore()
        val executor = SubAgentExecutor(parent, depth = 0)
        val filtered = invokeCreateToolRegistryForToolset(executor, "dev")
        val defaultSize = ToolRegistry.createDefault().getAllTools().size
        assertEquals(
            defaultSize,
            filtered.getAllTools().size,
            "dev toolset should keep all default tools"
        )
    }

    @Test
    fun `research toolset should remove write and exec tools`() {
        val parent = AgentCore()
        val executor = SubAgentExecutor(parent, depth = 0)
        val filtered = invokeCreateToolRegistryForToolset(executor, "research")
        val names = filtered.getAllTools().map { it.name }.toSet()

        // 不应该有的写/执行类工具
        assertFalse(names.contains("write_file"), "research should NOT have write_file")
        assertFalse(names.contains("edit_file"), "research should NOT have edit_file")
        assertFalse(names.contains("delete_file"), "research should NOT have delete_file")
        assertFalse(names.contains("run_command"), "research should NOT have run_command")
        assertFalse(names.contains("exec_shell"), "research should NOT have exec_shell")
        assertFalse(names.contains("run_tests"), "research should NOT have run_tests")
        assertFalse(names.contains("maven"), "research should NOT have maven")
        assertFalse(names.contains("gradle"), "research should NOT have gradle")

        // 应该有只读类
        assertTrue(names.contains("read_file"), "research should have read_file")
        assertTrue(names.contains("grep_code"), "research should have grep_code")
        assertTrue(names.contains("search_code"), "research should have search_code")
        assertTrue(names.contains("semantic_search"), "research should have semantic_search")

        // ALWAYS_AVAILABLE 应该保留
        assertTrue(names.contains("delegate_task"), "research should keep delegate_task")
    }

    @Test
    fun `test toolset should keep test running tools but remove heavy write tools`() {
        val parent = AgentCore()
        val executor = SubAgentExecutor(parent, depth = 0)
        val filtered = invokeCreateToolRegistryForToolset(executor, "test")
        val names = filtered.getAllTools().map { it.name }.toSet()

        assertTrue(names.contains("run_tests"), "test toolset should have run_tests")
        assertTrue(names.contains("exec_shell"), "test toolset should have exec_shell")
        assertTrue(names.contains("read_file"), "test toolset should have read_file")
        assertFalse(names.contains("write_file"), "test toolset should NOT have write_file (use edit_file)")
        assertFalse(names.contains("delete_file"), "test toolset should NOT have delete_file")
    }

    @Test
    fun `browser toolset should keep only web tools`() {
        val parent = AgentCore()
        val executor = SubAgentExecutor(parent, depth = 0)
        val filtered = invokeCreateToolRegistryForToolset(executor, "browser")
        val names = filtered.getAllTools().map { it.name }.toSet()

        assertTrue(names.contains("http_request"), "browser should have http_request")
        assertTrue(names.contains("web_scraper"), "browser should have web_scraper")
        assertTrue(names.contains("clipboard"), "browser should have clipboard")
        assertFalse(names.contains("read_file"), "browser should NOT have read_file")
        assertFalse(names.contains("run_command"), "browser should NOT have run_command")
        assertFalse(names.contains("exec_shell"), "browser should NOT have exec_shell")
    }

    @Test
    fun `unknown toolset should fall back to dev (all tools)`() {
        val parent = AgentCore()
        val executor = SubAgentExecutor(parent, depth = 0)
        val filtered = invokeCreateToolRegistryForToolset(executor, "non-existent-toolset")
        val defaultSize = ToolRegistry.createDefault().getAllTools().size
        assertEquals(defaultSize, filtered.getAllTools().size)
    }

    // ===== P1 #5: memory 工具在子 Agent 中仍可注册 =====

    @Test
    fun `parent AgentCore should register memory tools after initialize`() {
        val parent = AgentCore()
        parent.initialize(
            AgentConfig(
                defaultModel = "test-model",
                systemPrompt = "parent prompt"
            )
        )
        val names = parent.getToolNamesForTest()
        // memory tools 应在 initialize 期间被 memoryManager.getAllToolSchemas() 注入
        // 可能是 memory_search / builtin_memory_search / external_*_memory_search
        val hasMemory = names.any {
            it.contains("memory_") || it.contains("_memory_")
        }
        assertTrue(
            hasMemory,
            "Parent AgentCore should register memory tools, got: ${names.filter { it.contains("memory") || it.contains("Memory") }}"
        )
    }

    @Test
    fun `delegate_task survives toolset filtering so sub-agent can still report back`() {
        // 验证无论何种 toolset，delegate_task 都在 ALWAYS_AVAILABLE 中保留
        // （因为子 Agent 需要能汇报给父 Agent）
        for (toolset in listOf("dev", "research", "test", "browser", "unknown")) {
            val parent = AgentCore()
            val executor = SubAgentExecutor(parent, depth = 0)
            val filtered = invokeCreateToolRegistryForToolset(executor, toolset)
            val names = filtered.getAllTools().map { it.name }.toSet()
            assertTrue(
                names.contains("delegate_task"),
                "Toolset '$toolset' should keep delegate_task, got: $names"
            )
        }
    }

    // ===== P2 #7: 递归深度限制 =====

    @Test
    fun `spawn at depth equals MAX_RECURSION_DEPTH should refuse to run`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(
            AgentConfig(
                defaultModel = "test-model",
                systemPrompt = "parent prompt"
            )
        )
        // 在 depth = MAX 处放一个 SubAgentExecutor
        val executor = SubAgentExecutor(parent, depth = SubAgentExecutor.MAX_RECURSION_DEPTH)
        val result = executor.spawn(
            parentSessionId = "test",
            taskDescription = "Should be rejected",
            toolset = "dev",
            maxIterations = 5
        )
        assertFalse(result.success, "Sub-agent at MAX depth should fail")
        assertTrue(
            result.output.contains("recursion") || result.output.contains("depth"),
            "Failure output should mention recursion/depth, got: ${result.output}"
        )
        assertEquals(0, result.iterationsUsed, "Should not consume any iterations")
    }

    @Test
    fun `MAX_RECURSION_DEPTH should be 2 to allow parent to spawn one level`() {
        // 设计约束：parent (depth=0) → sub (depth=1)，sub 在尝试 spawn 孙子时被拒
        // 即 sub-agent 本身可以存在（它不是被拒，它自己 spawn 才被拒）
        assertEquals(2, SubAgentExecutor.MAX_RECURSION_DEPTH)
    }

    // ===== 已有的小测试（保留向后兼容） =====

    @Test
    fun `sub task config should have defaults`() {
        val config = SubAgentExecutor.SubTaskConfig(description = "Simple task")
        assertEquals("dev", config.toolset)
        assertEquals(10, config.maxIterations)
        assertTrue(config.contextFiles.isEmpty())
    }

    @Test
    fun `AgentCore should accept toolRegistryOverride for sub-agent injection`() {
        val customRegistry = ToolRegistry().apply { register(ToolRegistry.createDefault().get("read_file")!!) }
        val custom = AgentCore(toolRegistryOverride = customRegistry)
        assertNotNull(custom)
        // 验证 custom 拿到的就是注入的 registry
        // (内部私有 field，但 size 应该是 1)
        assertEquals(1, custom.getToolRegistrySizeForTest())
    }

    @Test
    fun `AgentCore should accept subAgentDepth for recursion limit`() {
        val custom = AgentCore(subAgentDepth = SubAgentExecutor.MAX_RECURSION_DEPTH)
        assertNotNull(custom)
        // depth 由构造参数传入；subAgentExecutor 内部使用
        // (无法从外部直接断言，但能构造成功即为正向信号)
    }

    // ===== 工具方法 =====

    /**
     * 通过反射调用 SubAgentExecutor 的 private fun createToolRegistryForToolset，
     * 避免为了测试而把方法暴露成 internal。
     */
    private fun invokeCreateToolRegistryForToolset(
        executor: SubAgentExecutor,
        toolset: String
    ): ToolRegistry {
        val method = SubAgentExecutor::class.java.declaredMethods
            .first { it.name == "createToolRegistryForToolset" && it.parameterCount == 1 }
        method.isAccessible = true
        return method.invoke(executor, toolset) as ToolRegistry
    }
}
