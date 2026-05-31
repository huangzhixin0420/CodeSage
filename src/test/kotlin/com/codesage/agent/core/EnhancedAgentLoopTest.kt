package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EnhancedAgentLoopTest {

    /**
     * 验证对话在多次工具调用后能正常完成并发送 Done
     */
    @Test
    fun `conversation completes after multiple tool calls`() = runBlocking {
        val gateway = createFakeGatewayWithToolCalls(toolCallCount = 3)
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_session")
        val events = loop.run(
            userMessage = "Test message",
            session = session,
            contextManager = contextManager,
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        // 验证收到了 Done 事件
        assertTrue(events.any { it is AgentStreamEvent.Done }, "Should emit Done event")

        // 验证收到了 TextDelta 事件（最终回答）
        assertTrue(events.any { it is AgentStreamEvent.TextDelta }, "Should emit TextDelta for final answer")
    }

    /**
     * 验证预算耗尽后发送 BudgetExhausted 事件而非 Error
     */
    @Test
    fun `budget exhaustion should emit BudgetExhausted event`() = runBlocking {
        val gateway = createFakeGatewayWithToolCalls(toolCallCount = 10)
        val budgetConfig = TaskBudget.BudgetConfig(maxIterations = 3, enableIteration = true)
        val taskBudget = TaskBudget(budgetConfig)
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE),
            budget = taskBudget
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_session")
        val events = loop.run(
            userMessage = "Test message",
            session = session,
            contextManager = contextManager,
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        // 验证收到了 BudgetExhausted 事件
        val exhaustedEvents = events.filterIsInstance<AgentStreamEvent.BudgetExhausted>()
        assertEquals(1, exhaustedEvents.size, "Should emit exactly one BudgetExhausted event")
        assertTrue(exhaustedEvents.first().reason.contains("迭代次数"), "Reason should mention iterations")
        assertEquals(3, exhaustedEvents.first().consumedIterations, "Should have consumed 3 iterations")

        // 验证没有收到旧的 Error("达到最大迭代次数限制...")
        val errorEvents = events.filterIsInstance<AgentStreamEvent.Error>()
        assertTrue(
            errorEvents.none { it.message.contains("达到最大迭代次数限制") },
            "Should not emit old error message"
        )
    }

    /**
     * 验证预算预警在达到阈值时发送 BudgetStatus 事件
     */
    @Test
    fun `budget warning should emit BudgetStatus event`() = runBlocking {
        val gateway = createFakeGatewayWithToolCalls(toolCallCount = 10)
        val budgetConfig =
            TaskBudget.BudgetConfig(maxIterations = 10, warningThresholdPercent = 70, enableIteration = true)
        val taskBudget = TaskBudget(budgetConfig)
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE),
            budget = taskBudget
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_session")
        val events = loop.run(
            userMessage = "Test message",
            session = session,
            contextManager = contextManager,
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        // 70% of 10 = 7, so after 7th iteration we should get a WARNING status
        val statusEvents = events.filterIsInstance<AgentStreamEvent.BudgetStatus>()
        assertTrue(statusEvents.isNotEmpty(), "Should emit BudgetStatus events")
        // At least one event should have WARNING or CRITICAL status
        assertTrue(
            statusEvents.any { it.status == "WARNING" || it.status == "CRITICAL" },
            "Should emit WARNING or CRITICAL status"
        )
    }

    /**
     * 验证 Token 消耗被正确追踪
     */
    @Test
    fun `token usage should be recorded when usage is present`() = runBlocking {
        val gateway = createFakeGatewayWithToolCalls(toolCallCount = 1, returnUsage = true)
        val budgetConfig = TaskBudget.BudgetConfig(maxTokens = 1000, enableToken = true)
        val taskBudget = TaskBudget(budgetConfig)
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE),
            budget = taskBudget
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_session")
        loop.run(
            userMessage = "Test message",
            session = session,
            contextManager = contextManager,
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        // Each call returns 150 tokens, and there are 2 calls (1 tool + 1 final)
        assertEquals(300, taskBudget.consumedTokens(), "Should record accumulated token usage")
    }

    /**
     * 验证时间预算耗尽会触发 BudgetExhausted
     */
    @Test
    fun `time budget exhaustion should emit BudgetExhausted`() = runBlocking {
        val gateway = createFakeGatewayWithToolCalls(toolCallCount = 10)
        val startTime = System.currentTimeMillis() - 2000 // 2 seconds ago
        val budgetConfig = TaskBudget.BudgetConfig(maxDurationMs = 1000, enableTime = true)
        val taskBudget = TaskBudget(budgetConfig, startTimeMs = startTime)
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE),
            budget = taskBudget
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_session")
        val events = loop.run(
            userMessage = "Test message",
            session = session,
            contextManager = contextManager,
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        val exhaustedEvents = events.filterIsInstance<AgentStreamEvent.BudgetExhausted>()
        assertEquals(1, exhaustedEvents.size, "Should emit BudgetExhausted for time budget")
        assertTrue(exhaustedEvents.first().reason.contains("时间预算"), "Reason should mention time budget")
    }

    /**
     * 验证 CancellationException 不会被错误恢复流程捕获
     */
    @Test
    fun `cancellation exception should not trigger error recovery`(): Unit = runBlocking {
        val gateway = createCancellingGateway()
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_session")

        val job = launch {
            loop.run(
                userMessage = "Test message",
                session = session,
                contextManager = contextManager,
                currentModel = "test-model",
                systemPrompt = "You are a test assistant"
            ).collect()
        }

        delay(50)
        job.cancel()

        // 如果 CancellationException 被错误捕获，job 不会正确取消
        assertTrue(job.isCancelled, "Job should be cancelled properly")
    }

    private fun createFakeGateway(): ModelGateway {
        return object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                return Result.success(
                    ChatResponse(
                        id = "test",
                        model = request.model,
                        choices = listOf(
                            Choice(
                                index = 0,
                                message = Message.assistantMessage("Final answer after tools"),
                                finishReason = "stop"
                            )
                        ),
                        usage = null
                    )
                )
            }
        }
    }

    private fun createFakeGatewayWithToolCalls(toolCallCount: Int, returnUsage: Boolean = false): ModelGateway {
        var callCount = 0
        return object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                callCount++
                val usage =
                    if (returnUsage) Usage(promptTokens = 100, completionTokens = 50, totalTokens = 150) else null
                return if (callCount <= toolCallCount) {
                    Result.success(
                        ChatResponse(
                            id = "test_$callCount",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message(
                                        role = Role.ASSISTANT,
                                        content = "",
                                        toolCalls = listOf(
                                            ToolCall(
                                                id = "tool_$callCount",
                                                name = "list_directory",
                                                arguments = "{\"path\": \"src\"}"
                                            )
                                        )
                                    ),
                                    finishReason = "tool_calls"
                                )
                            ),
                            usage = usage
                        )
                    )
                } else {
                    Result.success(
                        ChatResponse(
                            id = "test_final",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message.assistantMessage("Final comprehensive answer."),
                                    finishReason = "stop"
                                )
                            ),
                            usage = usage
                        )
                    )
                }
            }
        }
    }

    private fun createCancellingGateway(): ModelGateway {
        return object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                delay(10000) // 长时间挂起
                Result.success(
                    ChatResponse(
                        id = "test",
                        model = request.model,
                        choices = emptyList(),
                        usage = null
                    )
                )
                throw CancellationException("Should be cancelled before this")
            }
        }
    }

    private fun createFakeAdapter(): ModelAdapter {
        return object : ModelAdapter {
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
    }

    private fun createFakeToolExecutor(): ToolExecutor {
        return ToolExecutor(null)
    }
}
