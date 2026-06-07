package com.codesage.agent.core

import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import com.codesage.persistence.ConversationPersistence
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * SubAgentExecutor 单元测试
 *
 * 覆盖：
 * - P0 #1: delegate_task 工具已注册到默认 ToolRegistry
 * - P1 #3 (v2): 子 Agent prompt **独立**，不继承父 prompt
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

    // ===== P1 #3 (v2): prompt 构造（纯函数，独立、不继承父 prompt） =====

    @Test
    fun `buildSubAgentPrompt should NOT include parent prompt - sub-agent has independent context`() {
        // v2 重构：子 Agent 拥有完全独立的 system prompt，不继承主 Agent 的 prompt。
        // 之前继承式设计曾因父 prompt 累积到 150KB+ 触发 MiniMax 2013 错误。
        val parentPrompt = "PARENT_PROMPT_MARKER_xyz_PARENT_SHOULD_NOT_LEAK"
        // 新签名只接受 (taskDescription, toolset, depth)
        val result = SubAgentExecutor.buildSubAgentPrompt(
            taskDescription = "Implement function foo",
            toolset = "dev",
            depth = 0
        )
        assertFalse(
            result.contains(parentPrompt),
            "Sub-agent prompt should NOT inherit parent prompt. " +
                    "Sub-agent must have independent context, not the parent's full system prompt."
        )
        assertFalse(
            result.contains("PARENT_PROMPT_MARKER"),
            "Sub-agent prompt must not leak parent content"
        )
    }

    @Test
    fun `buildSubAgentPrompt should be minimal - under 2KB`() {
        // 旧实现：150KB+（继承父 prompt）→ 触发 2013 错误
        // 新实现：< 2KB（独立最小 prompt）
        val result = SubAgentExecutor.buildSubAgentPrompt(
            taskDescription = "Implement a complex function that does many things",
            toolset = "dev",
            depth = 0
        )
        assertTrue(
            result.length < 2048,
            "Sub-agent prompt must be < 2KB (independent). Got ${result.length}B. " +
                    "If you really need a larger prompt, the design is wrong - sub-agent should focus on its task."
        )
    }

    @Test
    fun `buildSubAgentPrompt should contain task, toolset, depth, rules, output format, recursion`() {
        val result = SubAgentExecutor.buildSubAgentPrompt(
            taskDescription = "TASK_MARKER_abc",
            toolset = "research",
            depth = 1
        )
        // 必备字段
        assertTrue(result.contains("TASK_MARKER_abc"), "Should include the task description")
        assertTrue(result.contains("research"), "Should mention the toolset name")
        assertTrue(result.contains("depth=1"), "Should label the recursion depth")
        // 操作规则（不再用 "No new delegation"，新规则更明确）
        assertTrue(result.contains("Focus") || result.contains("focus"), "Should include focus rule")
        assertTrue(result.contains("No delegation") || result.contains("delegation"), "Should include no-delegation rule")
        assertTrue(result.contains("No new tasks"), "Should include no-new-tasks rule")
        // 输出格式
        assertTrue(result.contains("Output Format") || result.contains("Output"), "Should specify output format")
        // 递归限制
        assertTrue(result.contains("Recursion") || result.contains("recursion"), "Should include recursion constraint")
        assertTrue(result.contains("MAX_RECURSION_DEPTH") || result.contains("max="), "Should include max recursion depth value")
    }

    @Test
    fun `buildSubAgentPrompt depth value should match input`() {
        for (d in 0..3) {
            val result = SubAgentExecutor.buildSubAgentPrompt("t", "dev", d)
            assertTrue(result.contains("depth=$d"), "depth=$d should appear in prompt")
        }
    }

    @Test
    fun `buildSubAgentPrompt should not mention parent agent identity`() {
        // 真正的隔离：子 Agent 不应知道"主 Agent"是谁、做什么
        val result = SubAgentExecutor.buildSubAgentPrompt(
            taskDescription = "Read all test files",
            toolset = "dev",
            depth = 1
        )
        // 不应包含 "parent" 这个词作为角色指代（"parent" 可能出现在 recursion
        // 描述里说"父 Agent"是允许的，但要确认 prompt 不依赖主 Agent 的 context）
        val lower = result.lowercase()
        // "spawned by a parent" 这种角色关系描述不应出现
        assertFalse(
            lower.contains("spawned by"),
            "Sub-agent should not be told it was 'spawned by' a parent"
        )
        assertFalse(
            lower.contains("the parent agent will read your output"),
            "Sub-agent should not be told about parent's reading behavior"
        )
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
            toolset = "dev"
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

    // ===== P0 修复测试 =====

    /**
     * 创建一个不会触发真实 LLM 调用的 ModelGateway：
     * getCurrentAdapter 返回一个最小化的 fake adapter，toVendorRequest 返回空 JSON，
     * fromVendorResponse 返回空 choices。这样 EnhancedAgentLoop 在没真 LLM 时不会 NPE。
     */
    private fun createFakeAdapter(): ModelAdapter = object : ModelAdapter {
        override val providerName: String = "fake"
        override val supportedModels: List<String> = listOf("test-model")
        override fun supportsStreaming(): Boolean = false
        override fun supportsFunctionCalling(): Boolean = true
        override fun supportsVision(): Boolean = false
        override fun toVendorRequest(request: ChatRequest): String = "{}"
        override fun fromVendorResponse(response: String): ChatResponse =
            ChatResponse("", "", emptyList(), null)
        override fun parseStreamChunk(chunk: String): StreamChunk? = null
        override fun getStreamEndpoint(): String = "http://fake"
        override fun getChatEndpoint(): String = "http://fake"
        override fun getHeaders(): Map<String, String> = emptyMap()
    }

    private fun createFakeGateway(): ModelGateway = object : ModelGateway() {
        override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
    }

    /**
     * Test 1 (P0 核心): 子 Agent skipRestore=true 时不应看到父 Agent 持久化的会话
     *
     * 旧实现：AgentCore.initialize() 默认调 restoreSessions(RESTORE_ALL) 把父 Agent
     * 磁盘上所有 session 拉进自己的 sessions map。子 Agent 拿到一个不属于自己的 session，
     * 包含父的 tool_call_id，发到 LLM 触发 2013 错误。
     *
     * 验证：skipRestore=true 后，子 Agent 的 history 只有 1 条 system message，
     * 且 session id 与父的不同。
     */
    @Test
    fun `sub-agent with skipRestore must not see parent's persisted sessions`(
        @TempDir tempDir: Path
    ) = runBlocking {
        val parentPersistence = ConversationPersistence(tempDir.resolve("parent").toFile())
        val subPersistence = ConversationPersistence(tempDir.resolve("sub").toFile())
        val gateway = createFakeGateway()

        // 1. 父 Agent 初始化 + 强制保存一个 session 到磁盘
        val parentCore = AgentCore(
            gateway = gateway,
            conversationPersistenceOverride = parentPersistence
        )
        parentCore.initialize(AgentConfig(systemPrompt = "parent prompt"))
        val parentSession = parentCore.getCurrentSession()!!
        val parentHistory = parentCore.getCurrentHistory()
        parentPersistence.saveSessionSync(parentSession, parentHistory)
        assertTrue(
            parentPersistence.loadAllSessions().isNotEmpty(),
            "parent should have persisted at least one session"
        )

        // 2. 子 Agent 用独立 persistence + skipRestore + skipAutoSave
        val subCore = AgentCore(
            gateway = gateway,
            conversationPersistenceOverride = subPersistence
        )
        subCore.initialize(
            AgentConfig(systemPrompt = "isolated sub prompt"),
            skipRestore = true,
            skipAutoSave = true
        )

        // 3. 验证：子 Agent 的 session id 不在父的 session id 集合里
        val subSessionId = subCore.getCurrentSession()?.id
        assertNotNull(subSessionId)
        assertNotEquals(parentSession.id, subSessionId, "sub-agent must not reuse parent's session id")

        // 4. 验证：子 Agent 的 history 只有 1 条 system message
        val subHistory = subCore.getCurrentHistory()
        assertEquals(
            1, subHistory.size,
            "sub agent should have only 1 system message, but got ${subHistory.size}: ${subHistory.map { it.role }}"
        )
        assertEquals(Role.SYSTEM, subHistory[0].role)
        assertEquals("isolated sub prompt", subHistory[0].content)
    }

    /**
     * Test 2 (P0 核心): 子 Agent skipAutoSave=true 时不应写到自己的持久化目录
     *
     * 旧实现：AgentCore.initialize() 默认启动 sessionRestore.startAutoSave()，
     * 任何 saveSession 都会落到磁盘。子 Agent 写到父的 ~/.codesage/conversations/ 就污染了。
     *
     * 验证：skipAutoSave=true 后，子 Agent 跑过几轮后 tmp 目录里没有任何 session 文件。
     */
    @Test
    fun `sub-agent with skipAutoSave must not write to its own persistence`(
        @TempDir tempDir: Path
    ) = runBlocking {
        val subPersistence = ConversationPersistence(tempDir.toFile())
        val gateway = createFakeGateway()

        val subCore = AgentCore(
            gateway = gateway,
            conversationPersistenceOverride = subPersistence
        )
        subCore.initialize(
            AgentConfig(systemPrompt = "sub"),
            skipRestore = true,
            skipAutoSave = true
        )

        // 触发 auto-save 的关键路径：AgentCore 的 chat() 内部走 chatWithTools，
        // finally 块里 conversationPersistence.saveSession() 会被异步调度。
        // fake gateway 会让 LLM 调用快速结束（空响应），触发 save 路径。
        try {
            subCore.chat("test")
        } catch (e: Exception) {
            // fake gateway 可能抛异常，吞掉 — 我们只关心副作用（写盘）
        }

        // 验证：tmp 持久化目录里没有任何 session json 文件
        val files = tempDir.toFile().listFiles { f -> f.extension == "json" }
        assertTrue(
            files.isNullOrEmpty(),
            "sub agent should not write to its own persistence, but found: ${files?.map { it.name }}"
        )
    }

    /**
     * Test 3 (P0 regression): 父 Agent 的 restoreSessions 仍正常工作
     *
     * 防止 P0 修复误伤父 Agent 的常规启动路径。
     */
    @Test
    fun `parent agent restoreSessions still works - regression for P0 fix`(
        @TempDir tempDir: Path
    ) = runBlocking {
        val persistence = ConversationPersistence(tempDir.toFile())
        val gateway = createFakeGateway()

        // 1. 第一个父 Agent 跑过 + 保存
        val parent1 = AgentCore(
            gateway = gateway,
            conversationPersistenceOverride = persistence
        )
        parent1.initialize(AgentConfig(systemPrompt = "p1"))
        val p1SessionId = parent1.getCurrentSession()?.id
        assertNotNull(p1SessionId)
        persistence.saveSessionSync(parent1.getCurrentSession()!!, parent1.getCurrentHistory())

        // 2. 第二个父 Agent 实例模拟"重启"，默认 skipRestore=false
        val parent2 = AgentCore(
            gateway = gateway,
            conversationPersistenceOverride = persistence
        )
        parent2.initialize(AgentConfig(systemPrompt = "p2"))

        // 3. 验证：父 Agent 仍能从磁盘恢复自己之前的 session
        val restoredSessionIds = parent2.getSessions().map { it.id }
        assertTrue(
            p1SessionId in restoredSessionIds,
            "parent agent should still restore its own persisted sessions; " +
                "got: $restoredSessionIds, expected to contain: $p1SessionId"
        )
    }

    /**
     * Test 4 (P0 集成): 完整 SubAgentExecutor.spawn() 流程不污染父 Agent 磁盘
     *
     * 这是端到端验证：走 SubAgentExecutor.spawn 完整路径（创建 tmp 持久化、initialize、
     * 跑任务、清理），父 Agent 的持久化目录应该完全不变。
     */
    @Test
    fun `SubAgentExecutor spawn does not pollute parent persistence`(
        @TempDir tempDir: Path
    ) = runBlocking {
        val parentPersistence = ConversationPersistence(tempDir.resolve("parent").toFile())
        val gateway = createFakeGateway()

        // 父 Agent + 保存
        val parentCore = AgentCore(
            gateway = gateway,
            conversationPersistenceOverride = parentPersistence
        )
        parentCore.initialize(AgentConfig(systemPrompt = "parent"))
        persistence_saveSessionSync_bridge(parentCore, parentPersistence)
        val parentSessionCountBefore = parentPersistence.loadAllSessions().size
        val parentSessionIdBefore = parentCore.getCurrentSession()?.id
        assertNotNull(parentSessionIdBefore)

        // 走完整 SubAgentExecutor.spawn
        val subExecutor = SubAgentExecutor(parentCore)
        val result = subExecutor.spawn(
            parentSessionId = parentSessionIdBefore!!,
            taskDescription = "isolated sub task",
            toolset = "dev"
        )

        // 验证 1: 父的磁盘 session 数量没变
        val parentSessionCountAfter = parentPersistence.loadAllSessions().size
        assertEquals(
            parentSessionCountBefore, parentSessionCountAfter,
            "SubAgentExecutor.spawn must not write to parent's persistence; " +
                "before=$parentSessionCountBefore, after=$parentSessionCountAfter"
        )

        // 验证 2: 父的 session id 没出现在子 agent 的最终 result 里（防止父 session 泄漏到子 context）
        assertFalse(
            result.output.contains(parentSessionIdBefore),
            "sub-agent output should not leak parent session id; got: ${result.output.take(200)}"
        )
    }

    /**
     * 辅助函数：给 parentCore 触发 saveSessionSync（不依赖 async save 调度）
     */
    private suspend fun persistence_saveSessionSync_bridge(
        core: AgentCore,
        persistence: ConversationPersistence
    ) {
        val session = core.getCurrentSession() ?: return
        val history = core.getCurrentHistory()
        persistence.saveSessionSync(session, history)
    }

    // ===== P1 修复测试 =====

    /**
     * P1 #1: buildSubAgentPrompt 强化为 "Final-Turn Output Contract"
     *
     * 老 prompt 里的 "## Output Format" 只是软建议，LLM 经常把中间思考也当成 output。
     * 新 prompt 用了 "Final-Turn Output Contract" + "Hard rules" 提升为协议级约束。
     */
    @Test
    fun `buildSubAgentPrompt should enforce final-turn plain-text contract`() {
        val prompt = SubAgentExecutor.buildSubAgentPrompt("t", "dev", 0)
        // Final-Turn Contract 段
        assertTrue(prompt.contains("Final-Turn Output Contract"),
            "prompt should have a 'Final-Turn Output Contract' section, got: ${prompt.take(300)}...")
        // 强约束词
        assertTrue(prompt.contains("Hard rules for the final turn"))
        assertTrue(prompt.contains("Plain text only"))
        assertTrue(prompt.contains("Do NOT include JSON"))
        assertTrue(prompt.contains("Do NOT call any tools in the final turn"))
        assertTrue(prompt.contains("Do NOT add follow-up suggestions"))
        // 摘要结构
        assertTrue(prompt.contains("**Result**"))
        assertTrue(prompt.contains("**Files**"))
        assertTrue(prompt.contains("**Blockers**"))
    }

    /**
     * P1 #2: buildSubAgentPrompt 仍然 < 2KB
     *
     * Final-Turn Contract 加了 ~300B，仍应在 2KB 内。
     */
    @Test
    fun `buildSubAgentPrompt with final-turn contract should stay under 2KB`() {
        val prompt = SubAgentExecutor.buildSubAgentPrompt(
            taskDescription = "Implement a complex function that does many things",
            toolset = "dev",
            depth = 0
        )
        assertTrue(
            prompt.length < 2048,
            "Sub-agent prompt must stay < 2KB even with Final-Turn Contract. Got ${prompt.length}B."
        )
    }

    /**
     * P1 #3: extractFinalTurnSummary — 正常情况（final turn 有内容）
     */
    @Test
    fun `extractFinalTurnSummary should return finalTurnText when non-blank`() {
        val summary = SubAgentExecutor.extractFinalTurnSummary(
            finalTurnText = "Done. Result: implemented.\nFiles: foo.kt",
            allText = "I will read the file.\nDone. Result: implemented.\nFiles: foo.kt",
            iterationsUsed = 3,
            logger = com.intellij.openapi.diagnostic.Logger.getInstance("test")
        )
        assertEquals("Done. Result: implemented.\nFiles: foo.kt", summary)
    }

    /**
     * P1 #4: extractFinalTurnSummary — final turn 为空（子 agent 没写摘要）
     *        兜底到 allText
     */
    @Test
    fun `extractFinalTurnSummary should fall back to allText when finalTurnText is blank`() {
        val summary = SubAgentExecutor.extractFinalTurnSummary(
            finalTurnText = "",
            allText = "I will read the file.\nThen I will write.",
            iterationsUsed = 2,
            logger = com.intellij.openapi.diagnostic.Logger.getInstance("test")
        )
        assertEquals("I will read the file.\nThen I will write.", summary)
    }

    /**
     * P1 #5: extractFinalTurnSummary — 都为空时返回 sentinel
     */
    @Test
    fun `extractFinalTurnSummary should return sentinel when both are blank`() {
        val summary = SubAgentExecutor.extractFinalTurnSummary(
            finalTurnText = "",
            allText = "",
            iterationsUsed = 0,
            logger = com.intellij.openapi.diagnostic.Logger.getInstance("test")
        )
        assertEquals("(sub-agent produced no output)", summary)
    }

    /**
     * P1 #6: extractFinalTurnSummary — finalTurnText 优先于 allText（即使 allText 更长）
     */
    @Test
    fun `extractFinalTurnSummary should prefer finalTurnText over allText even when shorter`() {
        val summary = SubAgentExecutor.extractFinalTurnSummary(
            finalTurnText = "Short summary",
            allText = "A".repeat(10000),
            iterationsUsed = 1,
            logger = com.intellij.openapi.diagnostic.Logger.getInstance("test")
        )
        assertEquals("Short summary", summary)
    }

    // ===== P2 #1: extractToolCallArgSummary =====

    /**
     * P2 #1: extractToolCallArgSummary — read_file 抽 path 字段
     */
    @Test
    fun `extractToolCallArgSummary should extract path from read_file args`() {
        val summary = SubAgentExecutor.extractToolCallArgSummary(
            toolName = "read_file",
            argumentsJson = """{"path": "/Users/leo/foo.kt"}"""
        )
        assertEquals("path: /Users/leo/foo.kt", summary)
    }

    /**
     * P2 #2: extractToolCallArgSummary — grep_code 抽 pattern 字段
     */
    @Test
    fun `extractToolCallArgSummary should extract pattern from grep_code args`() {
        val summary = SubAgentExecutor.extractToolCallArgSummary(
            toolName = "grep_code",
            argumentsJson = """{"pattern": "fun.*auth", "path": "/src"}"""
        )
        assertTrue(summary.contains("pattern: fun.*auth"))
        assertTrue(summary.contains("path: /src"))
    }

    /**
     * P2 #3: extractToolCallArgSummary — run_command 抽 command 字段
     */
    @Test
    fun `extractToolCallArgSummary should extract command from run_command args`() {
        val summary = SubAgentExecutor.extractToolCallArgSummary(
            toolName = "run_command",
            argumentsJson = """{"command": "gradle build"}"""
        )
        assertEquals("command: gradle build", summary)
    }

    /**
     * P2 #4: extractToolCallArgSummary — 未知 tool 名 fallback 截断 200 字符
     */
    @Test
    fun `extractToolCallArgSummary should truncate for unknown tool`() {
        val args = """{"random_field": "value"}"""
        val summary = SubAgentExecutor.extractToolCallArgSummary(
            toolName = "totally_unknown_tool",
            argumentsJson = args
        )
        assertEquals(args, summary)
    }

    /**
     * P2 #5: extractToolCallArgSummary — 无关键字段时 fallback 截断 200 字符
     */
    @Test
    fun `extractToolCallArgSummary should truncate when key fields missing`() {
        val args = """{"unrelated_field": "value"}"""
        val summary = SubAgentExecutor.extractToolCallArgSummary(
            toolName = "read_file",
            argumentsJson = args
        )
        assertEquals(args, summary)
    }

    // ===== P2 #2: extractCancelledSummary =====

    /**
     * P2 #6: extractCancelledSummary — marker 必出在第一行
     */
    @Test
    fun `extractCancelledSummary should start with Cancelled by user marker`() {
        val summary = SubAgentExecutor.extractCancelledSummary(
            lastAssistantText = "I was working on auth",
            allText = "all text",
            completedToolCalls = listOf(
                ToolCallRecord("read_file", "path: /a.kt", 100, true)
            ),
            logger = com.intellij.openapi.diagnostic.Logger.getInstance("test")
        )
        assertTrue(
            summary.startsWith("Cancelled by user."),
            "Parent LLM needs to detect the marker to avoid auto-retry. Got: ${summary.take(100)}"
        )
    }

    /**
     * P2 #7: extractCancelledSummary — 包含 tool calls 列表（带 ✓/✗ mark）
     */
    @Test
    fun `extractCancelledSummary should include completed tool calls with marks`() {
        val summary = SubAgentExecutor.extractCancelledSummary(
            lastAssistantText = "I refactored X",
            allText = "",
            completedToolCalls = listOf(
                ToolCallRecord("read_file", "path: /a.kt", 100, true),
                ToolCallRecord("write_file", "path: /b.kt", 50, true),
                ToolCallRecord("run_command", "command: ls", 0, false)
            ),
            logger = com.intellij.openapi.diagnostic.Logger.getInstance("test")
        )
        assertTrue(summary.contains("**Tool calls completed** (3)"))
        assertTrue(summary.contains("✓ `read_file` (100B): path: /a.kt"))
        assertTrue(summary.contains("✓ `write_file` (50B): path: /b.kt"))
        assertTrue(summary.contains("✗ `run_command` (0B): command: ls"))
    }

    /**
     * P2 #8: extractCancelledSummary — 空 tool calls 时降级提示
     */
    @Test
    fun `extractCancelledSummary should handle empty tool calls gracefully`() {
        val summary = SubAgentExecutor.extractCancelledSummary(
            lastAssistantText = "thinking...",
            allText = "",
            completedToolCalls = emptyList(),
            logger = com.intellij.openapi.diagnostic.Logger.getInstance("test")
        )
        assertTrue(summary.contains("**Tool calls completed** (0)"))
        assertTrue(summary.contains("thinking..."))
    }

    /**
     * P2 #9: extractCancelledSummary — 都为空时只输出 marker + 0 tool calls
     *        （不崩溃；父 LLM 拿到的是干净的 "Cancelled by user. ..." 摘要）
     */
    @Test
    fun `extractCancelledSummary should not crash when both texts are blank`() {
        val summary = SubAgentExecutor.extractCancelledSummary(
            lastAssistantText = "",
            allText = "",
            completedToolCalls = emptyList(),
            logger = com.intellij.openapi.diagnostic.Logger.getInstance("test")
        )
        assertTrue(summary.startsWith("Cancelled by user."))
        assertTrue(summary.contains("(0)"))
    }

    // ===== P2 #3: buildSubAgentPrompt 应包含 Cancellation Semantics 段 =====

    /**
     * P2 #10: buildSubAgentPrompt — 包含 "Cancellation Semantics" 段
     *        （让子 agent 知道输出 marker 后父 LLM 不应自动 retry）
     */
    @Test
    fun `buildSubAgentPrompt should include Cancellation Semantics section`() {
        val prompt = SubAgentExecutor.buildSubAgentPrompt(
            taskDescription = "do X",
            toolset = "dev",
            depth = 0
        )
        assertTrue(
            prompt.contains("Cancellation Semantics"),
            "Sub-agent prompt must mention cancellation semantics for parent LLM. Prompt: $prompt"
        )
        assertTrue(
            prompt.contains("Cancelled by user."),
            "Sub-agent prompt must show the marker parent LLM should detect"
        )
    }

    // ===== P2 #4: SubAgentResult cancelled 字段默认 false =====

    /**
     * P2 #11: SubAgentResult.cancelled 默认 false（向后兼容）
     */
    @Test
    fun `SubAgentResult cancelled should default to false`() {
        val r = SubAgentResult(
            success = true,
            output = "ok",
            sessionId = "sub_test",
            iterationsUsed = 1,
            toolsUsed = listOf("read_file")
        )
        assertFalse(r.cancelled, "Default cancelled should be false (backward compat)")
        assertTrue(r.completedToolCalls.isEmpty(), "Default completedToolCalls should be empty")
    }

    // ===== P3: 工具集命名（coder / explorer / verifier / webfetcher + alias 兼容） =====

    /**
     * P3 #1: 新名 `coder` 保留全部 IDE 工具（默认）
     */
    @Test
    fun `P3 new toolset name coder should keep all tools (default)`() {
        val parent = AgentCore()  // 不会被实际调用，createToolRegistryForToolset 不依赖 parent
        val executor = SubAgentExecutor(parent)
        val fullRegistry = ToolRegistry.createDefault()
        val fullCount = fullRegistry.getAllTools().size

        val registry = invokeCreateToolRegistryForToolset(executor, "coder")

        assertEquals(
            fullCount, registry.getAllTools().size,
            "coder should keep all tools (default behavior)"
        )
    }

    /**
     * P3 #2: 新名 `explorer` 走 RESEARCH_TOOLS + ALWAYS_AVAILABLE
     */
    @Test
    fun `P3 new toolset name explorer should filter to read-only tools`() {
        val parent = AgentCore()
        val executor = SubAgentExecutor(parent)
        val fullRegistry = ToolRegistry.createDefault()
        val fullCount = fullRegistry.getAllTools().size

        val registry = invokeCreateToolRegistryForToolset(executor, "explorer")

        val kept = registry.getAllTools().map { it.name }
        assertTrue(kept.contains("read_file"), "explorer should keep read_file")
        assertTrue(kept.contains("grep_code"), "explorer should keep grep_code")
        assertTrue(kept.contains("delegate_task"), "explorer should keep delegate_task (ALWAYS_AVAILABLE)")
        // 写 / 跑命令的工具应该被过滤掉
        assertFalse(kept.contains("write_file"), "explorer should NOT keep write_file")
        assertFalse(kept.contains("run_tests"), "explorer should NOT keep run_tests")
        assertTrue(
            registry.getAllTools().size < fullCount,
            "explorer should filter some tools out (full=$fullCount, explorer=${kept.size})"
        )
    }

    /**
     * P3 #3: 新名 `verifier` 走 TEST_TOOLS + ALWAYS_AVAILABLE
     */
    @Test
    fun `P3 new toolset name verifier should keep test-running tools`() {
        val parent = AgentCore()
        val executor = SubAgentExecutor(parent)
        val registry = invokeCreateToolRegistryForToolset(executor, "verifier")

        val kept = registry.getAllTools().map { it.name }
        assertTrue(kept.contains("run_tests"), "verifier should keep run_tests")
        assertTrue(kept.contains("run_command"), "verifier should keep run_command")
        assertTrue(kept.contains("read_file"), "verifier should keep read_file")
    }

    /**
     * P3 #4: 新名 `webfetcher` 走 BROWSER_TOOLS + ALWAYS_AVAILABLE
     */
    @Test
    fun `P3 new toolset name webfetcher should keep network tools`() {
        val parent = AgentCore()
        val executor = SubAgentExecutor(parent)
        val registry = invokeCreateToolRegistryForToolset(executor, "webfetcher")

        val kept = registry.getAllTools().map { it.name }
        assertTrue(kept.contains("http_request"), "webfetcher should keep http_request")
        assertTrue(kept.contains("web_scraper"), "webfetcher should keep web_scraper")
        // 写文件工具应该被过滤掉
        assertFalse(kept.contains("write_file"), "webfetcher should NOT keep write_file")
    }

    /**
     * P3 #5: 旧名 alias `dev` 行为 = `coder`（保留全部）
     */
    @Test
    fun `P3 old alias dev should behave like coder`() {
        val parent = AgentCore()
        val executor = SubAgentExecutor(parent)
        val fullRegistry = ToolRegistry.createDefault()
        val fullCount = fullRegistry.getAllTools().size

        val registry = invokeCreateToolRegistryForToolset(executor, "dev")

        assertEquals(
            fullCount, registry.getAllTools().size,
            "old alias 'dev' should behave like new name 'coder' (keep all tools)"
        )
    }

    /**
     * P3 #6: 旧名 alias `research` 行为 = `explorer`
     */
    @Test
    fun `P3 old alias research should behave like explorer`() {
        val parent = AgentCore()
        val executor = SubAgentExecutor(parent)
        val explorerRegistry = invokeCreateToolRegistryForToolset(executor, "explorer")
        val researchRegistry = invokeCreateToolRegistryForToolset(executor, "research")

        val explorerTools = explorerRegistry.getAllTools().map { it.name }.toSet()
        val researchTools = researchRegistry.getAllTools().map { it.name }.toSet()
        assertEquals(
            explorerTools, researchTools,
            "old alias 'research' should produce identical toolset as 'explorer'"
        )
    }

    /**
     * P3 #7: `delegateTaskTool()` 的 `toolset` 参数 description 包含新名 + alias 提示
     *        （Tool description 主描述保持简洁，新名集中在 toolset 字段说明里）
     */
    @Test
    fun `P3 delegateTaskTool toolset param description should mention new names and old aliases`() {
        val tool = com.codesage.agent.tools.delegateTaskTool()
        val toolsetDesc = tool.parameters.properties["toolset"]?.description ?: ""
        assertTrue(toolsetDesc.contains("coder"), "toolset description should mention new name 'coder'")
        assertTrue(toolsetDesc.contains("explorer"), "toolset description should mention new name 'explorer'")
        assertTrue(toolsetDesc.contains("verifier"), "toolset description should mention new name 'verifier'")
        assertTrue(toolsetDesc.contains("webfetcher"), "toolset description should mention new name 'webfetcher'")
        assertTrue(toolsetDesc.contains("dev"), "toolset description should mention old alias 'dev'")
        assertTrue(toolsetDesc.contains("deprecated") || toolsetDesc.contains("WARN"),
            "toolset description should warn about old aliases")
    }

    /**
     * P3 #8: `delegateTaskTool()` 的 toolset enum 包含新旧名（兼容老 prompt）
     */
    @Test
    fun `P3 delegateTaskTool toolset enum should include new and old names`() {
        val tool = com.codesage.agent.tools.delegateTaskTool()
        val enumList = tool.parameters.properties["toolset"]?.enum ?: emptyList()
        // 新名
        listOf("coder", "explorer", "verifier", "webfetcher").forEach { newName ->
            assertTrue(
                enumList.contains(newName),
                "enum should include new name '$newName' (got: $enumList)"
            )
        }
        // 旧名 alias
        listOf("dev", "research", "test", "browser").forEach { oldName ->
            assertTrue(
                enumList.contains(oldName),
                "enum should keep old alias '$oldName' for backward compat (got: $enumList)"
            )
        }
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
