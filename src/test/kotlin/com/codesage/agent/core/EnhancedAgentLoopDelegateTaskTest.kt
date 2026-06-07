package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
        assertTrue(completion.success, "Sub-agent should have succeeded")
        assertEquals("sub-agent finished the task", completion.output)
        assertEquals("sub_test_123", completion.sessionId)

        // 验证 fake executor 确实被调用了
        assertEquals(1, fakeSubAgentExecutor.spawnCallCount)
        assertEquals("Write a unit test", fakeSubAgentExecutor.lastTaskDescription)
        assertEquals("dev", fakeSubAgentExecutor.lastToolset)
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
     * P1 #1: delegate_task 工具的 tool result 现在是纯文本，不是 JSON
     *
     * 老的实现把 sub-agent 的 output 包装成 JSON:
     *   {"success":true,"output":"...","session_id":"...","iterations_used":3,"tools_used":[...]}
     *
     * 新实现（参考 Claude Code）直接返回 sub-agent 的自然语言最终 turn:
     *   "Done. Result: ...\nFiles: ...\nBlockers: ..."
     *
     * 父 LLM 看到 tool result 时不再需要解析 JSON，可以直接当 user message 解读。
     */
    @Test
    fun `delegate_task tool result should be plain text not JSON`() = runBlocking {
        val parent = AgentCore()
        parent.initialize(AgentConfig(defaultModel = "test-model", systemPrompt = "parent"))

        // 模拟子 agent 返回的最终 turn 文本（已遵守 Final-Turn Contract）
        val expectedFinalText = "**Result**: implemented the feature\n**Files**: Foo.kt\n**Blockers**: none"
        val expectedResult = SubAgentResult(
            success = true,
            output = expectedFinalText,
            sessionId = "sub_test_plain",
            iterationsUsed = 3,
            toolsUsed = listOf("read_file", "write_file")
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

        // ToolCallResult 事件的 result 字段应当是子 agent 的纯文本，
        // 不应以 "{" 开头（JSON 特征）
        val toolResults = events.filterIsInstance<AgentStreamEvent.ToolCallResult>()
        val delegateResult = toolResults.firstOrNull { it.toolName == "delegate_task" }
        assertNotNull(delegateResult, "Should have tool result for delegate_task")
        assertFalse(
            delegateResult!!.result.trim().startsWith("{"),
            "delegate_task tool result should NOT be JSON, got: ${delegateResult.result.take(200)}"
        )
        // 应当包含子 agent 的最终 turn 文本
        assertTrue(
            delegateResult.result.contains("**Result**"),
            "delegate_task tool result should contain the sub-agent's final turn markdown, got: ${delegateResult.result}"
        )
        // 父 LLM 视角下，loop.run 结束后 contextManager 里的 tool_message 应当持有纯文本
        val contextMessages = testContextManager.getContext()
        val toolMessages = contextMessages.filter { it.role == Role.TOOL }
        assertTrue(toolMessages.isNotEmpty(), "should have a tool message in context")
        val lastToolMessage = toolMessages.last()
        assertFalse(
            lastToolMessage.content.trim().startsWith("{"),
            "tool_message content in parent context should NOT be JSON, got: ${lastToolMessage.content.take(200)}"
        )
        assertTrue(lastToolMessage.content.contains("**Result**"))
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
        assertEquals(7, completion.iterationsUsed,
            "SubAgentComplete event should carry iterationsUsed=7, got: ${completion.iterationsUsed}")
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

            override fun parseStreamChunk(chunk: String) = null
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

        override suspend fun spawn(
            parentSessionId: String,
            taskDescription: String,
            toolset: String,
            maxIterations: Int,
            contextFiles: List<String>,
            progressCallback: suspend (String) -> Unit,
            parentJob: kotlinx.coroutines.Job?,
        ): SubAgentResult {
            spawnCallCount++
            lastTaskDescription = taskDescription
            lastToolset = toolset
            // 通知一下 progress 回调，模拟一次进度
            progressCallback("[fake] sub-agent running...")
            return expectedResult
        }
    }
}
