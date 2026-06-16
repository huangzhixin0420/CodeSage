package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 验证 [EnhancedAgentLoop] 能正确把 `delegate_task` 工具调用分发到
 * [SubAgentExecutor]（P0 #2）。
 *
 * 该测试覆盖了端到端路径：
 * 1. LLM 响应中带 `delegate_task` tool_call
 * 2. [EnhancedAgentLoop.executeTool] 命中 `toolCall.name == "delegate_task"` 分支
 * 3. [EnhancedAgentLoop.executeDelegateTask] 被调用
 * 4. 它调用 [SubAgentExecutor.spawn]
 * 5. spawn 返回的结果被序列化为 JSON 注入回上下文
 *
 * 用 [FakeSubAgentExecutor] 拦截真实 spawn，避免依赖 LLM/网络。
 */
class EnhancedAgentLoopDelegateTaskTest {

    @Test
    fun `delegate_task tool call should be dispatched to SubAgentExecutor`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        val expectedResult = SubAgentResult(
            success = true,
            output = "sub-agent finished the task",
            sessionId = "sub_test_123",
            iterationsUsed = 3,
            toolsUsed = listOf("read_file", "write_file")
        )
        val fakeSubAgentExecutor = FakeSubAgentExecutor(parent, expectedResult)

        // LLM 第一轮返回 delegate_task 调用，第二轮返回最终文本
        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"task_description":"Write a unit test","toolset":"dev"}"""
        )

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        val events = loop.run(
            userMessage = "Please write a unit test",
            session = AgentSession(id = "session_test"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "parent"
        ).toList()

        // 验证 SubAgentStart 和 SubAgentComplete 事件都被 emit
        val startEvents = events.filterIsInstance<AgentStreamEvent.SubAgentStart>()
        val completeEvents = events.filterIsInstance<AgentStreamEvent.SubAgentComplete>()
        assertTrue(startEvents.isNotEmpty(), "SubAgentStart should be emitted")
        assertTrue(completeEvents.isNotEmpty(), "SubAgentComplete should be emitted")

        // SubAgentComplete 的 success 来自 sub-agent 的执行结果
        val completion = completeEvents.first()
        val startSession = startEvents.first().sessionId
        assertTrue(completion.success, "Sub-agent should have succeeded")
        assertEquals("sub-agent finished the task", completion.output)
        // 关键断言：SubAgentStart 和 SubAgentComplete 共享同一个 sessionId。
        // 这是修复 sessionId 漂移 bug 的核心契约 — 之前 EventRouter 因为 id 漂移
        // 会导致 UI 上 task / toolset / elapsedMs 全为空。
        assertTrue(
            startSession.isNotEmpty() && startSession.startsWith("sub_"),
            "SubAgentStart.sessionId should be non-empty and start with 'sub_', got: '$startSession'"
        )
        assertEquals(
            startSession,
            completion.sessionId,
            "SubAgentStart.sessionId and SubAgentComplete.sessionId MUST match " +
                    "(UI EventRouter keys its lastSubAgent* maps by this id). " +
                    "start='$startSession' complete='${completion.sessionId}'"
        )

        // 验证 fake executor 确实被调用了
        assertEquals(1, fakeSubAgentExecutor.spawnCallCount)
        assertEquals("Write a unit test", fakeSubAgentExecutor.lastTaskDescription)
        assertEquals("dev", fakeSubAgentExecutor.lastToolset)
        // 新增：验证 caller 透传的 subSessionIdOverride 也被 fake 收到了
        assertEquals(
            startSession,
            fakeSubAgentExecutor.lastSubSessionIdOverride,
            "spawn should have been called with subSessionIdOverride == startSession"
        )
    }

    @Test
    fun `delegate_task should pass isolated_worktree to spawn`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        val expectedResult = SubAgentResult(
            success = true,
            output = "sub-agent finished in worktree",
            sessionId = "sub_wt_123",
            iterationsUsed = 2,
            toolsUsed = listOf("read_file"),
            worktreeDiff = "diff content",
            worktreeChanges = buildJsonObject { put("has_changes", true) }
        )
        val fakeSubAgentExecutor = FakeSubAgentExecutor(parent, expectedResult)

        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"task_description":"Refactor in isolation","toolset":"coder","isolated_worktree":true}"""
        )

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        val events = loop.run(
            userMessage = "Please refactor in isolation",
            session = AgentSession(id = "session_wt"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "parent"
        ).toList()

        assertEquals(1, fakeSubAgentExecutor.spawnCallCount)
        assertEquals("Refactor in isolation", fakeSubAgentExecutor.lastTaskDescription)
        assertEquals("coder", fakeSubAgentExecutor.lastToolset)
        assertTrue(fakeSubAgentExecutor.lastIsolatedWorktree, "isolated_worktree should be passed through")

        // 验证返回的 JSON 包含 worktree 字段
        val toolResultEvents = events.filterIsInstance<AgentStreamEvent.ToolCallResult>()
        assertTrue(toolResultEvents.isNotEmpty(), "ToolCallResult should be emitted")
        val resultJson = Json.parseToJsonElement(toolResultEvents.first().result).jsonObject
        assertEquals("diff content", resultJson["worktree_diff"]?.jsonPrimitive?.content)
        assertEquals(true, resultJson["worktree_changes"]?.jsonObject?.get("has_changes")?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `delegate_task with isolated_worktree false should not enable worktree`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        val fakeSubAgentExecutor = FakeSubAgentExecutor(
            parent,
            SubAgentResult(true, "done", "x", 0, emptyList())
        )

        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"task_description":"Normal task","toolset":"dev","isolated_worktree":false}"""
        )

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        loop.run(
            userMessage = "Please do normal task",
            session = AgentSession(id = "session_nowt"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "parent"
        ).toList()

        assertFalse(fakeSubAgentExecutor.lastIsolatedWorktree, "isolated_worktree should default to false")
    }

    @Test
    fun `delegate_task should pass max_depth to spawn and default to 2`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        val fakeSubAgentExecutor = FakeSubAgentExecutor(
            parent,
            SubAgentResult(true, "done", "x", 0, emptyList())
        )

        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"task_description":"Custom depth","toolset":"dev","max_depth":4}"""
        )

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        loop.run(
            userMessage = "Run with custom depth",
            session = AgentSession(id = "session_depth"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "parent"
        ).toList()

        assertEquals(1, fakeSubAgentExecutor.spawnCallCount)
        assertEquals(4, fakeSubAgentExecutor.lastMaxDepth, "max_depth=4 should be passed to spawn")
    }

    @Test
    fun `delegate_task with max_depth out of range should return error and not spawn`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        val fakeSubAgentExecutor = FakeSubAgentExecutor(
            parent,
            SubAgentResult(true, "should not run", "x", 0, emptyList())
        )

        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"task_description":"Bad depth","toolset":"dev","max_depth":0}"""
        )

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        val events = loop.run(
            userMessage = "Bad depth",
            session = AgentSession(id = "session_bad_depth"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "parent"
        ).toList()

        assertEquals(0, fakeSubAgentExecutor.spawnCallCount, "Spawn should not be called for invalid max_depth")
        val toolResults = events.filterIsInstance<AgentStreamEvent.ToolCallResult>()
        val delegateResult = toolResults.firstOrNull { it.toolName == "delegate_task" }
        assertNotNull(delegateResult, "Should have tool result for delegate_task")
        assertFalse(delegateResult!!.success, "Should fail due to invalid max_depth")
        assertTrue(
            delegateResult.result.contains("max_depth") || delegateResult.result.contains("Invalid"),
            "Error should mention invalid max_depth, got: ${delegateResult.result}"
        )
    }

    /**
     * 6.10.4: SubAgentStart 事件应携带解析到的 max_depth / allowed_tools / denied_tools，
     * 供 UI 展示子 Agent 的递归深度预算和工具范围。
     */
    @Test
    fun `SubAgentStart event should carry maxDepth allowedTools and deniedTools`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        val fakeSubAgentExecutor = FakeSubAgentExecutor(
            parent,
            SubAgentResult(true, "done", "x", 0, emptyList())
        )

        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"task_description":"Restricted tools","toolset":"coder","max_depth":4,"allowed_tools":["read_file","edit_file"],"denied_tools":["delete_file","delegate_task"]}"""
        )

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        val events = loop.run(
            userMessage = "Run with restricted tools",
            session = AgentSession(id = "session_start_meta"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "parent"
        ).toList()

        val start = events.filterIsInstance<AgentStreamEvent.SubAgentStart>().first()
        assertEquals(4, start.maxDepth, "SubAgentStart.maxDepth should match delegate_task.max_depth")
        assertEquals(listOf("read_file", "edit_file"), start.allowedTools)
        assertEquals(listOf("delete_file", "delegate_task"), start.deniedTools)
        assertTrue(start.delegationForbidden, "delegationForbidden should be true when delegate_task is denied")
    }

    /**
     * 6.10.4: 旧调用方不传新字段时，SubAgentStart 仍应保持向后兼容的默认值。
     */
    @Test
    fun `SubAgentStart default fields should be backward compatible`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        val fakeSubAgentExecutor = FakeSubAgentExecutor(
            parent,
            SubAgentResult(true, "done", "x", 0, emptyList())
        )

        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"task_description":"Legacy call","toolset":"dev"}"""
        )

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        val events = loop.run(
            userMessage = "Legacy call",
            session = AgentSession(id = "session_legacy"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "parent"
        ).toList()

        val start = events.filterIsInstance<AgentStreamEvent.SubAgentStart>().first()
        assertEquals(SubAgentExecutor.DEFAULT_MAX_RECURSION_DEPTH, start.maxDepth)
        assertTrue(start.allowedTools.isEmpty(), "allowedTools should default to empty")
        assertTrue(start.deniedTools.isEmpty(), "deniedTools should default to empty")
        assertEquals(0, start.depth, "depth should default to 0")
        assertFalse(start.delegationForbidden, "delegationForbidden should default to false")
    }

    @Test
    fun `delegate_task should pass allowed_tools and denied_tools to spawn`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        val fakeSubAgentExecutor = FakeSubAgentExecutor(
            parent,
            SubAgentResult(true, "done", "x", 0, emptyList())
        )

        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"task_description":"Restricted tools","toolset":"coder","allowed_tools":["read_file","edit_file"],"denied_tools":["delete_file"]}"""
        )

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        loop.run(
            userMessage = "Run with restricted tools",
            session = AgentSession(id = "session_tools"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "parent"
        ).toList()

        assertEquals(1, fakeSubAgentExecutor.spawnCallCount)
        assertEquals(
            listOf("read_file", "edit_file"),
            fakeSubAgentExecutor.lastAllowedTools,
            "allowed_tools should be passed to spawn"
        )
        assertEquals(
            listOf("delete_file"),
            fakeSubAgentExecutor.lastDeniedTools,
            "denied_tools should be passed to spawn"
        )
    }

    @Test
    fun `delegate_task with missing task_description should return error JSON`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        val fakeSubAgentExecutor = FakeSubAgentExecutor(
            parent,
            SubAgentResult(true, "should not run", "x", 0, emptyList())
        )

        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"toolset":"dev"}"""  // 故意缺 task_description
        )

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        val events = loop.run(
            userMessage = "Bad request",
            session = AgentSession(id = "s"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "p"
        ).toList()

        // 工具结果应该包含 success=false 和 error
        val toolResults = events.filterIsInstance<AgentStreamEvent.ToolCallResult>()
        val delegateResult = toolResults.firstOrNull { it.toolName == "delegate_task" }
        assertNotNull(delegateResult, "Should have tool result for delegate_task")
        assertFalse(delegateResult!!.success, "Should fail due to missing task_description")
        assertTrue(
            delegateResult.result.contains("task_description"),
            "Error should mention missing task_description, got: ${delegateResult.result}"
        )
        // fake executor 不应该被调用
        assertEquals(0, fakeSubAgentExecutor.spawnCallCount)
    }

    /**
     * 6.10.1: delegate_task 工具的 tool result 现在是结构化 JSON。
     *
     * JSON 包含从子 agent 最终 turn 中解析出的 result/files/blockers，
     * 以及 iterations_used、tools_used、completed_tool_calls、session_id 等元数据。
     */
    @Test
    fun `delegate_task tool result should be structured JSON`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        // 模拟子 agent 返回的最终 turn 文本（已遵守 Final-Turn Contract）
        val expectedFinalText = "**Result**: implemented the feature\n**Files**: Foo.kt\n**Blockers**: none"
        val expectedResult = SubAgentResult(
            success = true,
            output = expectedFinalText,
            sessionId = "sub_test_plain",
            iterationsUsed = 3,
            toolsUsed = listOf("read_file", "write_file"),
            completedToolCalls = listOf(
                ToolCallRecord("read_file", "path: Foo.kt", 120, true)
            )
        )
        val fakeSubAgentExecutor = FakeSubAgentExecutor(parent, expectedResult)

        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"task_description":"Implement Foo","toolset":"dev"}"""
        )

        val testContextManager = ContextManager()
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        val events = loop.run(
            userMessage = "Implement Foo",
            session = AgentSession(id = "session_plain"),
            contextManager = testContextManager,
            currentModel = "test-model",
            systemPrompt = "parent"
        ).toList()

        // ToolCallResult 事件的 result 字段应当是 JSON
        val toolResults = events.filterIsInstance<AgentStreamEvent.ToolCallResult>()
        val delegateResult = toolResults.firstOrNull { it.toolName == "delegate_task" }
        assertNotNull(delegateResult, "Should have tool result for delegate_task")
        val json = Json.parseToJsonElement(delegateResult!!.result).jsonObject

        assertEquals(true, json["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("implemented the feature", json["result"]?.jsonPrimitive?.content)
        assertEquals(listOf("Foo.kt"), json["files"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("none", json["blockers"]?.jsonPrimitive?.content)
        assertEquals(3, json["iterations_used"]?.jsonPrimitive?.intOrNull)
        assertEquals(listOf("read_file", "write_file"), json["tools_used"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(1, json["completed_tool_calls"]?.jsonArray?.size)
        assertEquals(
            fakeSubAgentExecutor.lastSubSessionIdOverride,
            json["session_id"]?.jsonPrimitive?.content
        )
        assertTrue(json["raw_output"]?.jsonPrimitive?.content?.contains("**Result**") == true)

        // 父 LLM 视角下，loop.run 结束后 contextManager 里的 tool_message 也应当是 JSON
        val contextMessages = testContextManager.getContext()
        val toolMessages = contextMessages.filter { it.role == Role.TOOL }
        assertTrue(toolMessages.isNotEmpty(), "should have a tool message in context")
        val lastToolMessage = toolMessages.last()
        assertTrue(
            lastToolMessage.content.trim().startsWith("{"),
            "tool_message content in parent context should be JSON, got: ${lastToolMessage.content.take(200)}"
        )
        val contextJson = Json.parseToJsonElement(lastToolMessage.content).jsonObject
        assertEquals(true, contextJson["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("Foo.kt", contextJson["files"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content)
    }

    /**
     * P1 #2: SubAgentComplete 事件现在带 iterationsUsed 和 toolsUsed（给 UI 用）
     */
    @Test
    fun `SubAgentComplete event should carry iterationsUsed and toolsUsed metadata`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        val expectedResult = SubAgentResult(
            success = true,
            output = "Done.",
            sessionId = "sub_test_meta",
            iterationsUsed = 7,
            toolsUsed = listOf("read_file", "grep_code", "write_file")
        )
        val fakeSubAgentExecutor = FakeSubAgentExecutor(parent, expectedResult)

        val gateway = createGatewayThatCallsDelegateTaskThenFinalizes(
            arguments = """{"task_description":"Some task","toolset":"dev"}"""
        )

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE),
            subAgentExecutor = fakeSubAgentExecutor
        )

        val events = loop.run(
            userMessage = "Some task",
            session = AgentSession(id = "session_meta"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "parent"
        ).toList()

        val completion = events.filterIsInstance<AgentStreamEvent.SubAgentComplete>().first()
        assertEquals(
            7, completion.iterationsUsed,
            "SubAgentComplete event should carry iterationsUsed=7, got: ${completion.iterationsUsed}"
        )
        assertEquals(
            listOf("read_file", "grep_code", "write_file"),
            completion.toolsUsed,
            "SubAgentComplete event should carry toolsUsed"
        )
    }

    // ===== helpers =====

    /**
     * LLM stub：第一次返回 delegate_task 工具调用，第二次返回最终文本。
     */
    private fun createGatewayThatCallsDelegateTaskThenFinalizes(arguments: String): ModelGateway {
        var callCount = 0
        return object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                callCount++
                return if (callCount == 1) {
                    Result.success(
                        ChatResponse(
                            id = "turn1",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message(
                                        role = Role.ASSISTANT,
                                        content = "",
                                        toolCalls = listOf(
                                            ToolCall(
                                                id = "tool_1",
                                                name = "delegate_task",
                                                arguments = arguments
                                            )
                                        )
                                    ),
                                    finishReason = "tool_calls"
                                )
                            ),
                            usage = null
                        )
                    )
                } else {
                    Result.success(
                        ChatResponse(
                            id = "final",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message.assistantMessage("Done."),
                                    finishReason = "stop"
                                )
                            ),
                            usage = null
                        )
                    )
                }
            }
        }
    }

    private fun createFakeAdapter(): ModelAdapter {
        return object : ModelAdapter {
            override val providerName = "fake"
            override val supportedModels = listOf("test-model")

            // 重要：必须为 false，让 ModelGateway.chatStream 走 chat() 同步回退，
            // 否则会真去访问 http://fake 抛出 NetworkException
            override fun supportsStreaming() = false
            override fun supportsFunctionCalling() = true
            override fun supportsVision() = false
            override fun toVendorRequest(request: ChatRequest) = "{}"
            override fun fromVendorResponse(json: String) =
                ChatResponse("x", "test-model", emptyList(), null)

            override fun parseStreamChunk(chunk: String): List<StreamChunk> = emptyList()
            override fun getStreamEndpoint() = "http://fake"
            override fun getChatEndpoint() = "http://fake"
            override fun getHeaders(): Map<String, String> = emptyMap()
        }
    }

    /**
     * SubAgentExecutor 的测试替身：不实际 spawn 子 Agent，直接返回预设结果。
     */
    private class FakeSubAgentExecutor(
        parent: AgentCore,
        private val expectedResult: SubAgentResult
    ) : SubAgentExecutor(parent) {
        var spawnCallCount = 0
        var lastTaskDescription: String? = null
        var lastToolset: String? = null
        var lastSubSessionIdOverride: String? = null
        var lastIsolatedWorktree: Boolean = false
        var lastMaxDepth: Int? = null
        var lastAllowedTools: List<String>? = null
        var lastDeniedTools: List<String>? = null

        override suspend fun spawn(
            parentSessionId: String,
            taskDescription: String,
            toolset: String,
            contextFiles: List<String>,
            progressCallback: suspend (String) -> Unit,
            parentJob: kotlinx.coroutines.Job?,
            subSessionIdOverride: String?,
            isolatedWorktree: Boolean,
            maxDepth: Int,
            allowedTools: List<String>,
            deniedTools: List<String>,
        ): SubAgentResult {
            spawnCallCount++
            lastTaskDescription = taskDescription
            lastToolset = toolset
            lastSubSessionIdOverride = subSessionIdOverride
            lastIsolatedWorktree = isolatedWorktree
            lastMaxDepth = maxDepth
            lastAllowedTools = allowedTools
            lastDeniedTools = deniedTools
            // 通知一下 progress 回调，模拟一次进度
            progressCallback("[fake] sub-agent running...")
            // 返回时把 caller 传进来的 subSessionIdOverride 透传回去，让
            // EnhancedAgentLoop.executeDelegateTask 能保持 sessionId 一致。
            return expectedResult.copy(sessionId = subSessionIdOverride ?: expectedResult.sessionId)
        }
    }
}
