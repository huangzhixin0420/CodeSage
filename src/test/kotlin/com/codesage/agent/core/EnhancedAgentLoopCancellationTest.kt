package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 取消路径专项测试 — 验证 2026-06 重构后的 EnhancedAgentLoop 行为
 *
 * 修复: 取消时(in-flight 工具)不再静默 break,改为对每个 in-flight 工具发 ToolCallError,
 * 让前端 UI 卡片有终态,不会转圈等 30s watchdog。
 *
 * 关键不变量:
 *  1. interrupt 在第一个工具执行后触发,后续 in-flight 工具收到 ToolCallError (而非静默丢失)
 *  2. 已完成的工具正常收到 ToolCallResult(取消前完成的不能丢)
 *  3. 整个 turn 最终仍 emit Done(否则前端 turn_complete 永不触发)
 *  4. 不取消时对照组:所有工具正常 Result,0 个 Error
 *
 * 注: 测试用自定义 gateway + 正确流式 index 0,1,2 (避开默认 chatStream 把所有 tool 都赋
 * index=0 的预存 bug — 该 bug 与本次重构无关)。
 */
class EnhancedAgentLoopCancellationTest {

    @Test
    fun `interrupted during tool execution -- completed tools get Result, in-flight get Error`() = runBlocking {
        val loopRef = CompletableDeferred<EnhancedAgentLoop>()

        val hookWithLoop = object : AgentHooks {
            override suspend fun postToolExecution(toolName: String, result: String, success: Boolean) {
                if (toolName == "list_directory") {
                    val loop = loopRef.await()
                    loop.interrupt()  // 第一个工具执行完后立即中断
                }
            }
        }

        val loop = EnhancedAgentLoop(
            gateway = createGatewayWithToolCalls(toolCallCount = 3),
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            hooks = hookWithLoop,
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )
        loopRef.complete(loop)

        val events = withTimeoutOrNull(10_000) {
            loop.run(
                userMessage = "Test",
                session = AgentSession(id = "test_mid_cancel"),
                contextManager = ContextManager(),
                currentModel = "test-model",
                systemPrompt = "test"
            ).toList()
        } ?: emptyList()

        val toolResults = events.filterIsInstance<AgentStreamEvent.ToolCallResult>().map { it.toolCallId }
        val toolErrorEvents = events.filterIsInstance<AgentStreamEvent.ToolCallError>()
        val toolErrors = toolErrorEvents.map { it.toolCallId }

        // 不变量 1 + 2: 至少 1 个 Result,至少 1 个 Error
        assertTrue(toolResults.isNotEmpty(),
            "At least one tool should have completed: results=$toolResults")
        assertTrue(toolErrors.isNotEmpty(),
            "At least one tool should be in-flight when interrupt fired: errors=$toolErrors")
        // 不重叠
        assertTrue(toolResults.intersect(toolErrors.toSet()).isEmpty(),
            "A tool cannot have both Result and Error: results=$toolResults, errors=$toolErrors")
        // 合并后总数 = 3
        assertEquals(3, (toolResults + toolErrors).toSet().size,
            "All 3 tool calls must have a terminal event (Result or Error): results=$toolResults, errors=$toolErrors")
        // 错误信息说明是取消
        assertTrue(toolErrorEvents.all { it.error.contains("Cancelled", ignoreCase = true) },
            "All in-flight errors should mention cancellation: ${toolErrorEvents.map { it.error }}")

        // 不变量 3: Done 仍然发出
        assertTrue(events.any { it is AgentStreamEvent.Done })
    }

    @Test
    fun `not interrupted -- all tool calls complete normally, no Error events emitted`() = runBlocking {
        // 对照组:不取消,所有工具正常完成
        val loop = EnhancedAgentLoop(
            gateway = createGatewayWithToolCalls(toolCallCount = 3),
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )

        val events = withTimeoutOrNull(10_000) {
            loop.run(
                userMessage = "Test",
                session = AgentSession(id = "test_normal"),
                contextManager = ContextManager(),
                currentModel = "test-model",
                systemPrompt = "test"
            ).toList()
        } ?: emptyList()

        val toolResults = events.filterIsInstance<AgentStreamEvent.ToolCallResult>()
        val toolErrors = events.filterIsInstance<AgentStreamEvent.ToolCallError>()
        assertEquals(3, toolResults.size, "All 3 tool calls should produce Result: ${toolResults.map { it.toolCallId }}")
        assertEquals(0, toolErrors.size, "No Error events when not interrupted")
        assertTrue(events.any { it is AgentStreamEvent.Done })
    }

    @Test
    fun `interrupt before run -- streaming bails out, turn ends with Done, no tool processed`() = runBlocking {
        // 边界场景: 用户"消息还没发"就先按了 stop(run 还没起来就 interrupted=true)
        // 期望: streaming 阶段检测到 interrupted 直接 return@collect,
        //       不会产生 tool calls,turn 循环 while(!interrupted) 直接退出,emit Done
        val loop = EnhancedAgentLoop(
            gateway = createGatewayWithToolCalls(toolCallCount = 3),
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = ToolExecutor(null),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )
        loop.interrupt()  // run 起来前设中断

        val events = withTimeoutOrNull(10_000) {
            loop.run(
                userMessage = "Test",
                session = AgentSession(id = "test_pre_cancel"),
                contextManager = ContextManager(),
                currentModel = "test-model",
                systemPrompt = "test"
            ).toList()
        } ?: emptyList()

        // 没有 ToolCallResult (没执行), 没有 ToolCallError (没产生 tool calls)
        val toolResults = events.filterIsInstance<AgentStreamEvent.ToolCallResult>()
        val toolErrors = events.filterIsInstance<AgentStreamEvent.ToolCallError>()
        assertEquals(0, toolResults.size, "No tools should have completed")
        assertEquals(0, toolErrors.size, "No tool calls were even generated (streaming bailed out)")
        // Done 仍然发出
        assertTrue(events.any { it is AgentStreamEvent.Done })
    }

    // ===== Test fixtures =====

    /**
     * 返回 3 个 tool call 的 gateway。
     * 自定义 chatStream 给每个 tool 分配独立 index (0, 1, 2),
     * 避开默认 chatStream 把所有 tool 都赋 index=0 的预存 bug。
     */
    private fun createGatewayWithToolCalls(toolCallCount: Int): ModelGateway {
        var callCount = 0
        return object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                callCount++
                return if (callCount == 1) {
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
                                        toolCalls = (1..toolCallCount).map { idx ->
                                            ToolCall(
                                                id = "tool_$idx",
                                                name = "list_directory",
                                                arguments = """{"path":"/test/$idx"}"""
                                            )
                                        }
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
                            id = "test_$callCount",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message.assistantMessage("Final answer"),
                                    finishReason = "stop"
                                )
                            ),
                            usage = null
                        )
                    )
                }
            }

            override fun chatStream(request: ChatRequest): Flow<StreamChunk> = flow {
                callCount++
                if (callCount == 1) {
                    (0 until toolCallCount).forEach { idx ->
                        emit(
                            StreamChunk(
                                id = "test_stream",
                                delta = "",
                                toolCallDeltas = listOf(
                                    StreamToolCallDelta(
                                        index = idx,
                                        id = "tool_${idx + 1}",
                                        name = "list_directory",
                                        arguments = """{"path":"/test/${idx + 1}"}"""
                                    )
                                )
                            )
                        )
                    }
                    emit(StreamChunk(id = "test_stream", delta = "", finishReason = "tool_calls", done = false))
                } else {
                    emit(StreamChunk(id = "test_stream", delta = "Final answer", done = false))
                    emit(StreamChunk(id = "test_stream", delta = "", finishReason = "stop", done = true))
                }
            }
        }
    }

    private fun createFakeAdapter(): ModelAdapter {
        return object : ModelAdapter {
            override val providerName: String = "fake"
            override val supportedModels: List<String> = listOf("test-model")
            override fun supportsStreaming(): Boolean = true
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
}
