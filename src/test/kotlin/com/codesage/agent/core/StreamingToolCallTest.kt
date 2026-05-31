package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class StreamingToolCallTest {

    /**
     * 验证流式响应中能检测到 tool_call 的开始（包含 id 和 name）
     */
    @Test
    fun `should detect tool_call start in streaming response`() = runBlocking {
        val gateway = createStreamingGatewayWithToolCall()
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE)
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_session")
        val events = loop.run(
            userMessage = "List files",
            session = session,
            contextManager = contextManager,
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        // 验证发出了 ToolCallStart 事件
        val toolCallStarts = events.filterIsInstance<AgentStreamEvent.ToolCallStart>()
        assertTrue(toolCallStarts.isNotEmpty(), "Should emit ToolCallStart event")
        assertEquals("list_directory", toolCallStarts.first().toolCall.name)
    }

    /**
     * 验证工具参数在流式响应中逐步累积
     */
    @Test
    fun `should accumulate tool arguments incrementally`() = runBlocking {
        val gateway = createStreamingGatewayWithIncrementalArgs()
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE)
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_session")
        val events = loop.run(
            userMessage = "List files",
            session = session,
            contextManager = contextManager,
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        // 验证发出了 ToolCallDelta 事件（参数增量）
        val toolCallDeltas = events.filterIsInstance<AgentStreamEvent.ToolCallDelta>()
        assertTrue(toolCallDeltas.isNotEmpty(), "Should emit ToolCallDelta events for argument accumulation")

        // 验证参数被完整累积后执行了工具
        val toolCallResults = events.filterIsInstance<AgentStreamEvent.ToolCallResult>()
        assertTrue(toolCallResults.isNotEmpty(), "Should emit ToolCallResult after tool execution")
        assertEquals("list_directory", toolCallResults.first().toolName)
    }

    /**
     * 验证当 finish_reason 为 tool_calls 时，触发完整的工具调用执行流程
     */
    @Test
    fun `should emit ToolCallComplete when finish_reason is tool_calls`() = runBlocking {
        val gateway = createStreamingGatewayWithToolCall()
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE)
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_session")
        val events = loop.run(
            userMessage = "List files",
            session = session,
            contextManager = contextManager,
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        // 验证 ToolCallResult 事件被发出（表示工具执行完成）
        val toolCallResults = events.filterIsInstance<AgentStreamEvent.ToolCallResult>()
        assertTrue(toolCallResults.isNotEmpty(), "Should emit ToolCallResult when finish_reason is tool_calls")

        // 验证最终也收到了 Done 事件
        assertTrue(events.any { it is AgentStreamEvent.Done }, "Should emit Done event")
    }

    /**
     * 验证能处理多个 tool_calls 顺序执行
     */
    @Test
    fun `should handle multiple tool_calls in sequence`() = runBlocking {
        val gateway = createStreamingGatewayWithMultipleToolCalls()
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AgentState.IDLE)
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_session")
        val events = loop.run(
            userMessage = "List files and read one",
            session = session,
            contextManager = contextManager,
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        // 验证发出了两个 ToolCallStart 事件
        val toolCallStarts = events.filterIsInstance<AgentStreamEvent.ToolCallStart>()
        assertEquals(2, toolCallStarts.size, "Should emit two ToolCallStart events")

        // 验证发出了两个 ToolCallResult 事件
        val toolCallResults = events.filterIsInstance<AgentStreamEvent.ToolCallResult>()
        assertEquals(2, toolCallResults.size, "Should emit two ToolCallResult events")

        val toolNames = toolCallResults.map { it.toolName }
        assertTrue(toolNames.contains("list_directory"))
        assertTrue(toolNames.contains("read_file"))
    }

    // ===== Helper Methods =====

    private fun createFakeToolExecutor(): ToolExecutor {
        return ToolExecutor(null)
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

    /**
     * 创建一个流式 Gateway，模拟单个 tool_call 的 SSE 流
     * 第一次返回 tool_calls，第二次返回最终文本
     */
    private fun createStreamingGatewayWithToolCall(): ModelGateway {
        var callCount = 0
        return object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<StreamChunk> =
                kotlinx.coroutines.flow.flow {
                    callCount++
                    if (callCount == 1) {
                        // 1. tool call 开始（id + name）
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                toolCallDeltas = listOf(
                                    StreamToolCallDelta(
                                        index = 0,
                                        id = "call_1",
                                        name = "list_directory"
                                    )
                                )
                            )
                        )
                        // 2. 参数增量
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                toolCallDeltas = listOf(
                                    StreamToolCallDelta(
                                        index = 0,
                                        arguments = "{\"path\": \"src\"}"
                                    )
                                )
                            )
                        )
                        // 3. finish_reason = tool_calls
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                finishReason = "tool_calls"
                            )
                        )
                    } else {
                        // 最终文本
                        emit(StreamChunk(id = "stream_final", delta = "Here are the files."))
                        emit(StreamChunk(id = "stream_final", delta = "", finishReason = "stop"))
                    }
                    // 4. 完成
                    emit(StreamChunk(id = "stream_1", delta = "", done = true))
                }
        }
    }

    /**
     * 创建一个流式 Gateway，模拟参数分多次增量的 SSE 流
     */
    private fun createStreamingGatewayWithIncrementalArgs(): ModelGateway {
        var callCount = 0
        return object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<StreamChunk> =
                kotlinx.coroutines.flow.flow {
                    callCount++
                    if (callCount == 1) {
                        // 1. tool call 开始
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                toolCallDeltas = listOf(
                                    StreamToolCallDelta(
                                        index = 0,
                                        id = "call_1",
                                        name = "list_directory"
                                    )
                                )
                            )
                        )
                        // 2. 参数分两次增量
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                toolCallDeltas = listOf(StreamToolCallDelta(index = 0, arguments = "{\"path\": "))
                            )
                        )
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                toolCallDeltas = listOf(StreamToolCallDelta(index = 0, arguments = "\"src\"}"))
                            )
                        )
                        // 3. finish_reason
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                finishReason = "tool_calls"
                            )
                        )
                    } else {
                        emit(StreamChunk(id = "stream_final", delta = "Done."))
                        emit(StreamChunk(id = "stream_final", delta = "", finishReason = "stop"))
                    }
                    emit(StreamChunk(id = "stream_1", delta = "", done = true))
                }
        }
    }

    /**
     * 创建一个流式 Gateway，模拟多个 tool_calls 的 SSE 流
     */
    private fun createStreamingGatewayWithMultipleToolCalls(): ModelGateway {
        var callCount = 0
        return object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<StreamChunk> =
                kotlinx.coroutines.flow.flow {
                    callCount++
                    if (callCount == 1) {
                        // 第一个工具调用
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                toolCallDeltas = listOf(
                                    StreamToolCallDelta(
                                        index = 0,
                                        id = "call_1",
                                        name = "list_directory"
                                    )
                                )
                            )
                        )
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                toolCallDeltas = listOf(
                                    StreamToolCallDelta(
                                        index = 0,
                                        arguments = "{\"path\": \"src\"}"
                                    )
                                )
                            )
                        )
                        // 第二个工具调用
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                toolCallDeltas = listOf(
                                    StreamToolCallDelta(
                                        index = 1,
                                        id = "call_2",
                                        name = "read_file"
                                    )
                                )
                            )
                        )
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                toolCallDeltas = listOf(
                                    StreamToolCallDelta(
                                        index = 1,
                                        arguments = "{\"path\": \"src/main.kt\"}"
                                    )
                                )
                            )
                        )
                        // finish_reason
                        emit(
                            StreamChunk(
                                id = "stream_1",
                                delta = "",
                                finishReason = "tool_calls"
                            )
                        )
                    } else {
                        emit(StreamChunk(id = "stream_final", delta = "Here are the results."))
                        emit(StreamChunk(id = "stream_final", delta = "", finishReason = "stop"))
                    }
                    emit(StreamChunk(id = "stream_1", delta = "", done = true))
                }
        }
    }
}
