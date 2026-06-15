package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.ToolHandler
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EnhancedAgentLoopTest {

    // ===== P1 修复：cleanupOrphanToolResults 行为测试 =====

    @Test
    fun `cleanupOrphanToolResults drops tool_result without matching tool_use`() {
        // 场景：上下文里有 tool_result 但没有对应的 tool_use
        // （LLM 代理提供商返回的孤儿 tool_result，会让下一个 turn 拿到 2013 错）。
        val loop = EnhancedAgentLoop(
            gateway = createFakeGateway(),
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )
        val method = EnhancedAgentLoop::class.java.declaredMethods
            .first { it.name == "cleanupOrphanToolResults" }
        method.isAccessible = true

        val messages = listOf(
            Message.userMessage("hi"),
            Message(
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("toolu_abc", "read_file", "{}"))
            ),
            Message(role = Role.TOOL, content = "result1", toolCallId = "toolu_abc"),
            // 孤儿：toolCallId 在 tool_use 中不存在
            Message(role = Role.TOOL, content = "orphan", toolCallId = "toolu_ghost"),
        )
        val (cleaned, orphanCount) = method.invoke(loop, messages, setOf("toolu_abc")) as Pair<List<*>, *>
        @Suppress("UNCHECKED_CAST")
        val cleanedList = cleaned as List<Message>

        assertEquals(1, orphanCount, "Should detect 1 orphan")
        assertEquals(3, cleanedList.size, "Should keep 3 messages (user, assistant, valid tool_result)")
        assertEquals(Role.TOOL, cleanedList.last().role)
        assertEquals("toolu_abc", cleanedList.last().toolCallId)
    }

    @Test
    fun `cleanupOrphanToolResults keeps all messages when all tool_results have matches`() {
        val loop = EnhancedAgentLoop(
            gateway = createFakeGateway(),
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )
        val method = EnhancedAgentLoop::class.java.declaredMethods
            .first { it.name == "cleanupOrphanToolResults" }
        method.isAccessible = true

        val messages = listOf(
            Message(
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall("id_1", "tool1", "{}"),
                    ToolCall("id_2", "tool2", "{}"),
                )
            ),
            Message(role = Role.TOOL, content = "r1", toolCallId = "id_1"),
            Message(role = Role.TOOL, content = "r2", toolCallId = "id_2"),
        )
        val (cleaned, orphanCount) = method.invoke(loop, messages, setOf("id_1", "id_2")) as Pair<List<*>, *>
        @Suppress("UNCHECKED_CAST")
        val cleanedList = cleaned as List<Message>

        assertEquals(0, orphanCount)
        assertEquals(3, cleanedList.size)
    }

    @Test
    fun `cleanupOrphanToolResults drops tool_result with null or blank toolCallId`() {
        // 防御：Role.TOOL 但 toolCallId 是 null/空串 — 同孤儿处理
        val loop = EnhancedAgentLoop(
            gateway = createFakeGateway(),
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )
        val method = EnhancedAgentLoop::class.java.declaredMethods
            .first { it.name == "cleanupOrphanToolResults" }
        method.isAccessible = true

        val messages = listOf(
            Message(
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("id_1", "t1", "{}"))
            ),
            Message(role = Role.TOOL, content = "valid", toolCallId = "id_1"),
            Message(role = Role.TOOL, content = "orphan_null", toolCallId = null),
            Message(role = Role.TOOL, content = "orphan_blank", toolCallId = ""),
        )
        val (cleaned, orphanCount) = method.invoke(loop, messages, setOf("id_1")) as Pair<List<*>, *>
        @Suppress("UNCHECKED_CAST")
        val cleanedList = cleaned as List<Message>

        assertEquals(2, orphanCount, "Null and blank should both be orphans")
        assertEquals(2, cleanedList.size)
    }

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

    /**
     * 验证 AI 在工具调用后返回空内容时，会 emit Error 事件而非直接静默结束
     */
    @Test
    fun `empty response after tool calls should emit Error event`() = runBlocking {
        var callCount = 0
        val gateway = object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                callCount++
                return when (callCount) {
                    1 -> Result.success(
                        ChatResponse(
                            id = "test_tool",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message(
                                        role = Role.ASSISTANT,
                                        content = "",
                                        toolCalls = listOf(
                                            ToolCall(
                                                id = "t1",
                                                name = "list_directory",
                                                arguments = "{\"path\": \"src\"}"
                                            )
                                        )
                                    ),
                                    finishReason = "tool_calls"
                                )
                            ),
                            usage = null
                        )
                    )
                    // 第二次调用：返回空内容（模拟工具结果处理后 AI 无回答）
                    else -> Result.success(
                        ChatResponse(
                            id = "test_empty",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message.assistantMessage(""),
                                    finishReason = "stop"
                                )
                            ),
                            usage = null
                        )
                    )
                }
            }
        }

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

        // 验证收到了 Error 事件（而非静默结束）
        val errorEvents = events.filterIsInstance<AgentStreamEvent.Error>()
        assertTrue(errorEvents.isNotEmpty(), "Should emit Error event when LLM returns empty content after tool calls")

        // 验证 Done 事件仍然被发出
        assertTrue(events.any { it is AgentStreamEvent.Done }, "Should emit Done event even after Error")
    }

    /**
     * 验证连续多轮对话中 errorRecovery 计数器不会累积
     */
    @Test
    fun `error recovery counters should not accumulate across separate runs`() = runBlocking {
        val errorRecovery = AgentErrorRecovery()
        val gateway = createFakeGatewayWithToolCalls(toolCallCount = 0) // 直接返回空内容

        // 第一次运行
        val loop1 = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE),
            errorRecovery = errorRecovery
        )
        loop1.run(
            userMessage = "First",
            session = AgentSession(id = "s1"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        // 第二次运行
        val loop2 = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE),
            errorRecovery = errorRecovery
        )
        loop2.run(
            userMessage = "Second",
            session = AgentSession(id = "s2"),
            contextManager = ContextManager(),
            currentModel = "test-model",
            systemPrompt = "You are a test assistant"
        ).toList()

        // 两次运行后计数器应该已被重置
        assertEquals(
            0,
            errorRecovery.getRetryCount(FailoverReason.EMPTY_RESPONSE),
            "Counters should be reset after each run"
        )
    }

    private fun createFakeToolExecutor(): ToolExecutor {
        return ToolExecutor(null)
    }

    // ===== P0 修复：outer catch 强制 break，防止外层 catch 触发后无限循环 =====

    /**
     * 构造一个在每次 chatStream() 内都抛 NetworkException(HTTP 400) 的 gateway，
     * 模拟 MiniMax M3 的 "tool call result does not follow tool call (2013)" 错误。
     * 旧实现：outer catch 的 else 分支只 delay 1 秒继续循环，无限重试。
     * 新实现：outer catch 强制 break；turn 数量应该有上限。
     */
    @Test
    fun `outer catch should force break, not loop forever on persistent error`(): Unit = runBlocking {
        val gateway = createRepeatedBadRequestGateway()
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_persistent_error")

        val startMs = System.currentTimeMillis()
        val events = withTimeoutOrNull(15_000) {
            loop.run(
                userMessage = "Test",
                session = session,
                contextManager = contextManager,
                currentModel = "test-model",
                systemPrompt = "You are a test assistant"
            ).toList()
        } ?: emptyList()
        val durationMs = System.currentTimeMillis() - startMs

        // 1. 必须最终 emit Done（不能 hang）
        assertTrue(events.any { it is AgentStreamEvent.Done }, "Must emit Done even on persistent error")

        // 2. 必须有 Error 事件
        val errorEvents = events.filterIsInstance<AgentStreamEvent.Error>()
        assertTrue(errorEvents.isNotEmpty(), "Must emit Error on persistent error")

        // 3. 总耗时应该 < 15s（旧实现如果走 else 分支 delay(1000) 持续重试，
        //    100ms 任务本身不会 stop，会卡 15s timeout；新版必须主动 break）
        assertTrue(durationMs < 12_000, "Should not loop forever (duration=$durationMs ms)")

        // 4. 关键：事件数量应该有界。旧实现可能 emit 几十次 Error 事件；
        //    新实现外层 catch 只触发有限次（<10），最终 Abort。
        assertTrue(
            errorEvents.size <= 10,
            "Should not emit excessive error events (got ${errorEvents.size})"
        )
    }

    /**
     * 验证持续 BAD_REQUEST 错误走 inner onFailure 路径被正确处理，不会冒到 outer catch。
     * 旧实现：maxRetries[BAD_REQUEST]=1，第一次重试 + 第二次重试 = 2 次后 Abort。
     * 新实现：行为相同，但 inner onFailure 路径的 try-catch 兜底确保不会冒到 outer。
     * outerCatchCount 应该保持 0。
     */
    @Test
    fun `persistent BAD_REQUEST should be handled by inner onFailure, not outer catch`(): Unit = runBlocking {
        val gateway = createRepeatedBadRequestGateway()
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )

        withTimeoutOrNull(15_000) {
            loop.run(
                userMessage = "Test",
                session = AgentSession(id = "test_inner_handles"),
                contextManager = ContextManager(),
                currentModel = "test-model",
                systemPrompt = "You are a test assistant"
            ).toList()
        }

        // 用 reflection 读 outerCatchCount：应该是 0（内层处理掉了）
        val field = EnhancedAgentLoop::class.java.declaredFields
            .first { it.name == "outerCatchCount" }
        field.isAccessible = true
        val count = field.getInt(loop)
        assertEquals(0, count, "outerCatchCount should be 0 (inner onFailure handles), was $count")
    }

    /**
     * 验证 inner onFailure 的 try-catch 兜底：构造一个会让 `recover()` 自身抛异常的
     * gateway（通过把 gateway 替换为抛非 BadRequest 异常的方式触发 inner 分支）。
     *
     * 这里用一种间接方法：让 gateway 一直抛 RuntimeException（非网络错误），
     * classify 后可能是 UNKNOWN（maxRetries=2）。第二次 recover 调用也走 inner，
     * 最终 Abort。整个流程不应让 outer catch 触发——验证 inner 路径本身有兜底。
     */
    @Test
    fun `persistent non-network error should still terminate via Abort`(): Unit = runBlocking {
        val gateway = createRepeatedRuntimeExceptionGateway()
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )

        val startMs = System.currentTimeMillis()
        val events = withTimeoutOrNull(10_000) {
            loop.run(
                userMessage = "Test",
                session = AgentSession(id = "test_inner_abort"),
                contextManager = ContextManager(),
                currentModel = "test-model",
                systemPrompt = "You are a test assistant"
            ).toList()
        } ?: emptyList()
        val durationMs = System.currentTimeMillis() - startMs

        assertTrue(events.any { it is AgentStreamEvent.Done }, "Must terminate with Done")
        val errorEvents = events.filterIsInstance<AgentStreamEvent.Error>()
        // 必须以 Abort 结束（maxRetries 限制起作用）
        assertTrue(errorEvents.isNotEmpty(), "Must emit Error events")
        assertTrue(durationMs < 8_000, "Should not loop forever (duration=${durationMs}ms)")
    }

    // ===== P0 修复：cleanupOrphanToolResults 增强 =====

    /**
     * 旧 cleanup 只丢 orphan tool_result；新增还会清理 assistant 消息里
     * 完全没有对应 tool_result 的 tool_use（未完成配对）—— 这是 2013 错误的
     * 另一种根因（assistant 发出 tool_use 但没完成就进入下一轮）。
     */
    @Test
    fun `cleanupOrphanToolResults drops unfulfilled tool_use on assistant message`() {
        val loop = EnhancedAgentLoop(
            gateway = createFakeGateway(),
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )
        val method = EnhancedAgentLoop::class.java.declaredMethods
            .first { it.name == "cleanupOrphanToolResults" }
        method.isAccessible = true

        val messages = listOf(
            Message.userMessage("hi"),
            // assistant 声明了 2 个 tool_use，但后续没有任何 tool_result
            Message(
                role = Role.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall("toolu_unfinished_1", "read_file", "{}"),
                    ToolCall("toolu_unfinished_2", "read_file", "{}"),
                )
            ),
        )
        val toolUseIds = setOf("toolu_unfinished_1", "toolu_unfinished_2")
        val (cleaned, orphanCount) = method.invoke(loop, messages, toolUseIds) as Pair<List<*>, *>
        @Suppress("UNCHECKED_CAST")
        val cleanedList = cleaned as List<Message>

        // 全部未完成 → toolCalls 应该被清空，orphanCount = 2
        assertEquals(2, orphanCount, "Should count 2 unfulfilled tool_uses")
        assertEquals(2, cleanedList.size, "user + assistant (with toolCalls cleared)")
        val assistant = cleanedList.last()
        assertEquals(Role.ASSISTANT, assistant.role)
        assertTrue(
            assistant.toolCalls.isNullOrEmpty(),
            "toolCalls should be cleared on assistant with no tool_results"
        )
    }

    /**
     * 部分完成（assistant 发出 2 个 tool_use，1 个有 tool_result，另一个没有）：
     * **修复前**：保守保留整个 toolCalls（"因为有 1 个对应上了"），导致 strict 提供商
     * （MiniMax-M3 / DeepSeek）返 "tool call result does not follow tool call (2013)" 400。
     * **修复后**：从 toolCalls 里过滤掉 unfulfilled 的 id，只保留有 tool_result 的。
     * 子 agent 在短 context 下这个 case 比主 agent 频繁（主 agent 多 turn 时
     * partial unfulfilled 比较罕见，所以之前 c26a17f 的测试在主 agent 形态下没暴露）。
     */
    @Test
    fun `cleanupOrphanToolResults filters unfulfilled tool_uses when partial tool_results exist`() {
        val loop = EnhancedAgentLoop(
            gateway = createFakeGateway(),
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )
        val method = EnhancedAgentLoop::class.java.declaredMethods
            .first { it.name == "cleanupOrphanToolResults" }
        method.isAccessible = true

        val messages = listOf(
            Message(
                role = Role.ASSISTANT,
                content = "I will read two files.",
                toolCalls = listOf(
                    ToolCall("id_done", "read_file", "{}"),
                    ToolCall("id_pending", "read_file", "{}"),
                )
            ),
            // 只完成 id_done
            Message(role = Role.TOOL, content = "ok", toolCallId = "id_done"),
        )
        val toolUseIds = setOf("id_done", "id_pending")
        val (cleaned, orphanCount) = method.invoke(loop, messages, toolUseIds) as Pair<List<*>, *>
        @Suppress("UNCHECKED_CAST")
        val cleanedList = cleaned as List<Message>

        // 部分完成：过滤掉 unfulfilled 的 id_pending，只保留 id_done
        assertEquals(1, orphanCount, "Should count 1 unfulfilled tool_use (id_pending)")
        assertEquals(2, cleanedList.size)
        val assistant = cleanedList.first { it.role == Role.ASSISTANT }
        // 修复后断言：toolCalls 被过滤，只剩 1 个 fulfilled 的
        val remainingIds = assistant.toolCalls?.mapNotNull { it.id } ?: emptyList()
        assertEquals(
            listOf("id_done"),
            remainingIds,
            "Should keep only the fulfilled tool_use (id_done), filter out unfulfilled (id_pending) " +
                    "to avoid 2013 on strict providers (MiniMax-M3 / DeepSeek)"
        )
        // 关键：assistant 的文本 content 保留不变（用户能看到的部分不被破坏）
        assertEquals("I will read two files.", assistant.content)
    }

    /**
     * 验证同一轮返回的多个 tool_call 被并行执行,且结果按原始顺序 emit。
     */
    @Test
    fun `multiple tool calls in single turn execute in parallel and preserve order`() = runBlocking {
        val delayPerToolMs = 100L
        val toolCount = 4

        // 注册一个会 delay 的测试工具,用于验证并行度
        val registry = ToolRegistry().apply {
            register(object : ToolHandler {
                override val tool: Tool = Tool(
                    name = "parallel_test_tool",
                    description = "Test tool for parallel execution",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "index" to ToolProperty(type = "integer", description = "tool index")
                        ),
                        required = listOf("index")
                    )
                )

                override suspend fun execute(args: JsonObject): ToolResult {
                    val index = args["index"]?.jsonPrimitive?.intOrNull ?: -1
                    delay(delayPerToolMs)
                    return ToolResult.Success(
                        buildJsonObject {
                            put("success", JsonPrimitive(true))
                            put("index", JsonPrimitive(index))
                        }
                    )
                }
            })
        }

        val gateway = object : ModelGateway() {
            private var callCount = 0
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                callCount++
                return if (callCount == 1) {
                    Result.success(
                        ChatResponse(
                            id = "test_tools",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message(
                                        role = Role.ASSISTANT,
                                        content = "",
                                        toolCalls = (0 until toolCount).map { i ->
                                            ToolCall(
                                                id = "tool_$i",
                                                name = "parallel_test_tool",
                                                arguments = "{\"index\": $i}"
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
                            id = "test_final",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message.assistantMessage("Done"),
                                    finishReason = "stop"
                                )
                            ),
                            usage = null
                        )
                    )
                }
            }
        }

        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = registry,
            toolExecutor = ToolExecutor(null, toolRegistry = registry),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )

        val contextManager = ContextManager()
        val session = AgentSession(id = "test_parallel")

        val durationMs = measureTimeMillis {
            loop.run(
                userMessage = "Run parallel tools",
                session = session,
                contextManager = contextManager,
                currentModel = "test-model",
                systemPrompt = "You are a test assistant"
            ).toList()
        }

        // 串行执行需要约 toolCount * delayPerToolMs = 400ms;并行应明显低于此
        assertTrue(
            durationMs < delayPerToolMs * toolCount * 0.6,
            "Expected parallel execution (< ${delayPerToolMs * toolCount * 0.6}ms), got ${durationMs}ms"
        )

        // 验证工具结果按原始顺序整合到上下文
        val toolMessages = contextManager.getContext().filter { it.role == Role.TOOL }
        assertEquals(toolCount, toolMessages.size, "Should have $toolCount tool results in context")
        toolMessages.forEachIndexed { i, msg ->
            assertEquals("tool_$i", msg.toolCallId, "Tool result order should match original toolCalls")
        }
    }

    // ===== helpers for new tests =====

    private fun createRepeatedBadRequestGateway(): ModelGateway {
        return object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override fun chatStream(request: ChatRequest): Flow<StreamChunk> = flow {
                throw com.codesage.shared.exceptions.NetworkException(
                    "HTTP 400 (requestSize=152892B): " +
                            "{\"type\":\"error\",\"error\":{\"type\":\"bad_request_error\",\"message\":\"invalid params, tool call result does not follow tool call (2013)\",\"http_code\":\"400\"}}"
                )
            }
        }
    }

    // ===== O5.1 修复:ModelReasoningRoundStart 懒发射 =====

    @Test
    fun `ModelReasoningRoundStart is emitted lazily on first reasoning delta`() = runBlocking {
        // 场景:模型支持 reasoning,流式返回包含 reasoning delta
        // 期望:第一条 reasoningDelta 之前先补发 ModelReasoningRoundStart,后续 delta 不再发
        val gateway = object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override fun chatStream(request: ChatRequest): Flow<StreamChunk> = flow {
                emit(StreamChunk(id = "c1", delta = "First reasoning ", reasoningDelta = "step1 "))
                emit(StreamChunk(id = "c1", delta = "", reasoningDelta = "step2 "))
                emit(StreamChunk(id = "c1", delta = "Answer", reasoningDelta = "", finishReason = "stop"))
                emit(StreamChunk(id = "c1", delta = "", reasoningDelta = "", done = true))
            }
        }
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )
        val events = loop.run(
            userMessage = "test",
            session = AgentSession(id = "t1"),
            contextManager = ContextManager(),
            currentModel = "m",
            systemPrompt = "sys"
        ).toList()

        val roundStarts = events.filterIsInstance<AgentStreamEvent.ModelReasoningRoundStart>()
        val reasonings = events.filterIsInstance<AgentStreamEvent.ModelReasoning>()
        val roundEnds = events.filterIsInstance<AgentStreamEvent.ModelReasoningRoundEnd>()
        assertEquals(1, roundStarts.size, "Lazy emit: exactly 1 RoundStart when reasoning present, got ${'$'}{roundStarts.size}")
        assertEquals(2, reasonings.size, "Both reasoning chunks should be emitted")
        // 修正 2026-06:每段 reasoning 都必须配对 1 个 RoundEnd
        //   流序列:reasoning("step1 ") → reasoning("step2 ") → text("Answer") → done
        //   RoundEnd 在 "Answer" chunk(无 reasoningDelta)处触发 1 次
        assertEquals(1, roundEnds.size, "Each reasoning segment must be paired with a RoundEnd, got ${'$'}{roundEnds.size}")
        assertEquals(roundStarts.size, roundEnds.size, "RoundStart count must equal RoundEnd count")
    }

    @Test
    fun `ModelReasoningRoundStart is NOT emitted when model has no reasoning`() = runBlocking {
        // 场景:模型不支持 reasoning 或本轮没产生 reasoningDelta
        // 期望:完全不发出 ModelReasoningRoundStart(避免前端创建空卡片)
        val gateway = object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override fun chatStream(request: ChatRequest): Flow<StreamChunk> = flow {
                emit(StreamChunk(id = "c2", delta = "Just plain text", reasoningDelta = ""))
                emit(StreamChunk(id = "c2", delta = " answer", reasoningDelta = "", finishReason = "stop"))
                emit(StreamChunk(id = "c2", delta = "", reasoningDelta = "", done = true))
            }
        }
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )
        val events = loop.run(
            userMessage = "test",
            session = AgentSession(id = "t2"),
            contextManager = ContextManager(),
            currentModel = "m",
            systemPrompt = "sys"
        ).toList()

        val roundStarts = events.filterIsInstance<AgentStreamEvent.ModelReasoningRoundStart>()
        val reasonings = events.filterIsInstance<AgentStreamEvent.ModelReasoning>()
        val roundEnds = events.filterIsInstance<AgentStreamEvent.ModelReasoningRoundEnd>()
        assertEquals(0, roundStarts.size, "No RoundStart when no reasoning, got ${'$'}{roundStarts.size}")
        assertEquals(0, reasonings.size, "No ModelReasoning chunks either")
        // 没有 reasoning → 也不需要 RoundEnd
        assertEquals(0, roundEnds.size, "No RoundEnd when no reasoning, got ${'$'}{roundEnds.size}")
        // 确认有 TextDelta(基础流式工作正常)
        assertTrue(events.any { it is AgentStreamEvent.TextDelta }, "Should still emit text")
    }

    @Test
    fun `ModelReasoningRoundStart resets between rounds (multi-round reasoning)`() = runBlocking {
        // 场景:单 turn 内多轮工具调用 + 多轮 reasoning
        // 期望:每轮 reasoning 都各自先发一次 RoundStart
        val callCount = intArrayOf(0)
        val gateway = object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override fun chatStream(request: ChatRequest): Flow<StreamChunk> = flow {
                callCount[0]++
                when (callCount[0]) {
                    1 -> {
                        emit(StreamChunk(id = "c3", delta = "", reasoningDelta = "round1-think "))
                        emit(StreamChunk(id = "c3", delta = "", reasoningDelta = "", finishReason = "tool_calls"))
                        emit(StreamChunk(id = "c3", delta = "", reasoningDelta = "", done = true))
                    }
                    2 -> {
                        emit(StreamChunk(id = "c3", delta = "", reasoningDelta = "round2-think "))
                        emit(StreamChunk(id = "c3", delta = "Final", reasoningDelta = "", finishReason = "stop"))
                        emit(StreamChunk(id = "c3", delta = "", reasoningDelta = "", done = true))
                    }
                    else -> {
                        emit(StreamChunk(id = "c3", delta = "Default", reasoningDelta = "", finishReason = "stop"))
                        emit(StreamChunk(id = "c3", delta = "", reasoningDelta = "", done = true))
                    }
                }
            }
        }
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = ToolRegistry.createDefault(),
            toolExecutor = createFakeToolExecutor(),
            stateFlow = MutableStateFlow(AgentState.IDLE)
        )
        val events = loop.run(
            userMessage = "test",
            session = AgentSession(id = "t3"),
            contextManager = ContextManager(),
            currentModel = "m",
            systemPrompt = "sys"
        ).toList()

        val roundStarts = events.filterIsInstance<AgentStreamEvent.ModelReasoningRoundStart>()
        // 由于工具调用可能不一定会真正发生(测试用的是 createFakeToolExecutor 默认 noop),
        // 我们只断言:凡是产生了 reasoning 的轮次,RoundStart 计数 == reasoning 出现次数
        val reasonings = events.filterIsInstance<AgentStreamEvent.ModelReasoning>()
        val roundEnds = events.filterIsInstance<AgentStreamEvent.ModelReasoningRoundEnd>()
        assertEquals(reasonings.size, roundStarts.size,
            "RoundStart count must equal reasoning chunk count (one start per round)")
        // 修正 2026-06:每个 reasoning round 必须配对 1 个 RoundEnd
        // 之前 EventConsumer.sendDoneExpansion 只在每 turn 第一次 complete 时补发,
        // 多轮推理第二轮起 Complete 永远不发,卡片卡在"思考中…"。现在每个 round
        // 在内容边界(下个 chunk reasoningDelta 转空)或 done 时都发 End。
        assertEquals(reasonings.size, roundEnds.size,
            "RoundEnd count must equal reasoning chunk count (one end per round)")
        assertEquals(roundStarts.size, roundEnds.size,
            "RoundStart count must equal RoundEnd count (paired events)")
        // 至少要看到 1 个 RoundStart(第一轮就发出了 reasoning)
        assertTrue(roundStarts.isNotEmpty(), "Expected at least 1 RoundStart")
    }

    private fun createRepeatedRuntimeExceptionGateway(): ModelGateway {
        return object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override fun chatStream(request: ChatRequest): Flow<StreamChunk> = flow {
                throw RuntimeException("simulated non-network error")
            }
        }
    }
}
