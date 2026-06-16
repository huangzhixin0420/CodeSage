package com.codesage.agent.core
import com.codesage.model.adapter.StreamEvent

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

        // Phase 1 并行执行：用不同时长的 run_command 确保取消时仍有 in-flight 工具。
        // 第一个命令 100ms 先完成，后两个 1s 仍在运行，此时触发中断可验证取消路径。
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val hookWithLoop = object : AgentHooks {
            override suspend fun postToolExecution(toolName: String, result: String, success: Boolean) {
                if (toolName == "run_command" && interrupted.compareAndSet(false, true)) {
                    val loop = loopRef.await()
                    loop.interrupt()  // 第一个工具执行完后立即中断
                }
            }
        }

        val toolRegistry = ToolRegistry.createDefault()
        val loop = EnhancedAgentLoop(
            gateway = createGatewayWithRunCommand(toolCallCount = 3, durationsMs = listOf(100L, 1000L, 1000L)),
            toolRegistry = toolRegistry,
            toolExecutor = ToolExecutor(null, toolRegistry = toolRegistry),
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
        assertTrue(
            toolResults.isNotEmpty(),
            "At least one tool should have completed: results=$toolResults"
        )
        assertTrue(
            toolErrors.isNotEmpty(),
            "At least one tool should be in-flight when interrupt fired: errors=$toolErrors"
        )
        // 不重叠
        assertTrue(
            toolResults.intersect(toolErrors.toSet()).isEmpty(),
            "A tool cannot have both Result and Error: results=$toolResults, errors=$toolErrors"
        )
        // 合并后总数 = 3
        assertEquals(
            3, (toolResults + toolErrors).toSet().size,
            "All 3 tool calls must have a terminal event (Result or Error): results=$toolResults, errors=$toolErrors"
        )
        // 错误信息说明是取消
        assertTrue(
            toolErrorEvents.all { it.error.contains("Cancelled", ignoreCase = true) },
            "All in-flight errors should mention cancellation: ${toolErrorEvents.map { it.error }}"
        )

        // 不变量 3: Done 仍然发出
        assertTrue(events.any { it is AgentStreamEvent.Done })
    }

    @Test
    fun `not interrupted -- all tool calls complete normally, no Error events emitted`() = runBlocking {
        // 对照组:不取消,所有工具正常完成。list_directory 足够快,避免 run_command sleep 拖时间。
        val toolRegistry = ToolRegistry.createDefault()
        val loop = EnhancedAgentLoop(
            gateway = createGatewayWithToolCalls(toolCallCount = 3),
            toolRegistry = toolRegistry,
            toolExecutor = ToolExecutor(null, toolRegistry = toolRegistry),
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
        assertEquals(
            3,
            toolResults.size,
            "All 3 tool calls should produce Result: ${toolResults.map { it.toolCallId }}"
        )
        assertEquals(0, toolErrors.size, "No Error events when not interrupted")
        assertTrue(events.any { it is AgentStreamEvent.Done })
    }

    @Test
    fun `interrupt before run -- streaming bails out, turn ends with Done, no tool processed`() = runBlocking {
        // 边界场景: 用户"消息还没发"就先按了 stop(run 还没起来就 interrupted=true)
        // 期望: streaming 阶段检测到 interrupted 直接 return@collect,
        //       不会产生 tool calls,turn 循环 while(!interrupted) 直接退出,emit Done
        val toolRegistry = ToolRegistry.createDefault()
        val loop = EnhancedAgentLoop(
            gateway = createGatewayWithToolCalls(toolCallCount = 3),
            toolRegistry = toolRegistry,
            toolExecutor = ToolExecutor(null, toolRegistry = toolRegistry),
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

    private fun runCommandArguments(durationMs: Long): String {
        return """{"command":"sleep ${durationMs / 1000.0}","timeout":30000}"""
    }

    /**
     * 返回 3 个 list_directory tool call 的 gateway（快路径）。
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

            override fun chatStreamLegacy(request: ChatRequest): Flow<StreamChunk> = flow {
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
            override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<com.codesage.model.adapter.StreamEvent> = chatStreamLegacy(request).toStreamEventFlow()
        }
    }

    /**
     * 返回 3 个 run_command tool call 的 gateway。
     * 自定义 chatStream 给每个 tool 分配独立 index (0, 1, 2),
     * 避开默认 chatStream 把所有 tool 都赋 index=0 的预存 bug。
     */
    private fun createGatewayWithRunCommand(toolCallCount: Int, durationsMs: List<Long>): ModelGateway {
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
                                            val duration = durationsMs.getOrElse(idx - 1) { 100L }
                                            ToolCall(
                                                id = "tool_$idx",
                                                name = "run_command",
                                                arguments = """{"command":"sleep ${duration / 1000.0}","timeout":30000}"""
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

            override fun chatStreamLegacy(request: ChatRequest): Flow<StreamChunk> = flow {
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
                                        name = "run_command",
                                        arguments = runCommandArguments(durationsMs.getOrElse(idx) { 100L })
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
            override fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<com.codesage.model.adapter.StreamEvent> = chatStreamLegacy(request).toStreamEventFlow()
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

            override fun parseStreamChunk(chunk: String): List<StreamEvent> = emptyList()
            override fun getStreamEndpoint(): String = "http://fake"
            override fun getChatEndpoint(): String = "http://fake"
            override fun getHeaders(): Map<String, String> = emptyMap()
        }
    }
}


/**
 * 2026-06: Test helper - 把 StreamChunk 转换为 StreamEvent 列表。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
/**
 * 2026-06: Test helper - 跨多个 StreamChunk 跟踪 tool call id(按 index 关联)。
 * 既有 chatStreamLegacy 测试,后续 chunk 通常不带 id,需要回退到首 chunk 的 id,
 * 才能让 TurnReducer 正确累积。
 */
private val STREAM_TEST_TOOL_IDS = mutableMapOf<Int, String>()

private fun com.codesage.model.dto.StreamChunk.toStreamEvent(): List<com.codesage.model.adapter.StreamEvent> {
    val events = mutableListOf<com.codesage.model.adapter.StreamEvent>()
    if (delta.isNotEmpty()) {
        events += com.codesage.model.adapter.StreamEvent.Content.Text(delta = delta)
    }
    if (!reasoningDelta.isNullOrEmpty()) {
        events += com.codesage.model.adapter.StreamEvent.Content.Reasoning(delta = reasoningDelta!!)
    }
    for (tc in toolCallDeltas) {
        // 2026-06: 同一 index 上,后续 chunk 缺省 id 时回退到首 chunk 的 id
        // (OpenAI/Anthropic 协议首 chunk 带 id+name,后续 chunk 只追加 arguments,
        //  新协议用 toolCallId 作主键,无 id 等于新工具调用,会破坏累积)。
        val effectiveId = tc.id ?: STREAM_TEST_TOOL_IDS.getOrPut(tc.index) { "test_call_${tc.index}" }
        if (tc.id != null) STREAM_TEST_TOOL_IDS[tc.index] = tc.id
        events += com.codesage.model.adapter.StreamEvent.ToolCall.Delta(
            toolCallId = effectiveId,
            toolName = tc.name,
            argumentsFragment = tc.arguments ?: "",
        )
    }
    when (val cb = codeBlock) {
        is com.codesage.model.dto.CodeBlockEvent.Start ->
            events += com.codesage.model.adapter.StreamEvent.CodeBlock.Started(codeBlockId = cb.codeBlockId, language = cb.language)
        is com.codesage.model.dto.CodeBlockEvent.Delta ->
            events += com.codesage.model.adapter.StreamEvent.CodeBlock.Delta(codeBlockId = cb.codeBlockId, delta = cb.delta)
        is com.codesage.model.dto.CodeBlockEvent.End ->
            events += com.codesage.model.adapter.StreamEvent.CodeBlock.Ended(codeBlockId = cb.codeBlockId)
        null -> {}
    }
    if (done) {
        events += com.codesage.model.adapter.StreamEvent.Flow.Finished(
            finishReason = com.codesage.model.dto.FinishReason.from(finishReason),
            usage = usage,
        )
    } else if (finishReason != null) {
        events += com.codesage.model.adapter.StreamEvent.Flow.Finished(
            finishReason = com.codesage.model.dto.FinishReason.from(finishReason),
            usage = usage,
        )
    }
    return events
}

/**
 * 2026-06: Test helper - 把 Flow<StreamChunk> 转换为 Flow<StreamEvent>。
 * 让既有 chatStreamLegacy 模拟可以复用 — 在 chatStream override 中调用此 helper。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.flow.Flow<com.codesage.model.dto.StreamChunk>.toStreamEventFlow(): kotlinx.coroutines.flow.Flow<com.codesage.model.adapter.StreamEvent> =
    kotlinx.coroutines.flow.flow {
        collect { chunk ->
            for (event in chunk.toStreamEvent()) {
                emit(event)
            }
        }
    }
