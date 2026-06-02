package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.memory.MemoryManager
import com.codesage.agent.memory.MemoryNudger
import com.codesage.agent.tools.SkillToolAdapter
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import com.codesage.shared.exceptions.*
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * 对话阶段枚举
 * 状态机驱动的完整闭环
 */
enum class ConversationPhase {
    INIT,              // 初始化：恢复系统提示、预压缩
    LLM_CALL,          // 调用模型
    STREAM_PROCESS,    // 处理流式响应
    TOOL_DISPATCH,     // 分发工具调用
    TOOL_EXECUTE,      // 执行工具
    RESULT_INTEGRATE,  // 整合工具结果
    POST_TURN_HOOK,    // 后处理钩子
    COMPLETE,          // 完成
    ERROR_RECOVERY,    // 错误恢复
    INTERRUPTED        // 被中断
}

/**
 * 增强型 Agent 对话循环
 *
 * 重构自 AgentCore.chatWithTools()，从「简单 while 循环」进化为「状态机驱动的完整闭环」。
 * 核心特性：
 * - 迭代预算管理（IterationBudget）
 * - 错误分类与恢复（AgentErrorRecovery）
 * - 优先流式路径（即使无消费者也用流式做健康检测）
 * - 流式 context scrubber（过滤内部标签）
 * - 插件 Hook 体系
 */
class EnhancedAgentLoop(
    private val gateway: ModelGateway,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val skillToolAdapter: SkillToolAdapter? = null,
    private val errorRecovery: AgentErrorRecovery = AgentErrorRecovery(),
    private val hooks: AgentHooks = object : AgentHooks {},
    private val fallbackModels: List<String> = AgentErrorRecovery.DEFAULT_FALLBACK_MODELS,
    private val stateFlow: kotlinx.coroutines.flow.MutableStateFlow<AgentState>,
    private val memoryManager: MemoryManager? = null,
    private val memoryNudger: MemoryNudger? = null,
    private val subAgentExecutor: SubAgentExecutor? = null,
    private val agentCore: AgentCore? = null,
    private val budget: TaskBudget? = null
) {

    private val logger = Logger.getLogger<EnhancedAgentLoop>()

    // 事件历史记录
    private val eventHistory = EventHistory()

    // 事件批量发射器（性能优化）
    private val batchEmitter = EventBatchEmitter()

    // 系统提示缓存（prefix cache 优化）
    private var cachedSystemPrompt: String? = null
    private var cachedSystemPromptSessionId: String? = null

    // 中断信号
    @Volatile
    private var interrupted = false

    // 当前任务的预算（用于子 Agent 预算继承）
    private var currentTaskBudget: TaskBudget? = null

    /**
     * 运行增强型对话循环（非流式工具调用，流式文本输出）
     *
     * @param userMessage 用户输入消息
     * @param session 当前会话
     * @param contextManager 上下文管理器
     * @param currentModel 当前模型
     * @param systemPrompt 系统提示
     */
    fun run(
        userMessage: String,
        session: AgentSession,
        contextManager: ContextManager,
        currentModel: String,
        systemPrompt: String,
        isContinuation: Boolean = false
    ): Flow<AgentStreamEvent> = channelFlow {
        var session = session
        interrupted = false
        val taskBudget = budget ?: TaskBudget()
        currentTaskBudget = taskBudget
        var phase = ConversationPhase.INIT
        var currentModelLocal = currentModel
        var turnNumber = 0
        var lastBudgetStatus = TaskBudget.BudgetStatus.OK

        // 事件发射包装：自动记录到 EventHistory
        suspend fun emitEvent(event: AgentStreamEvent) {
            eventHistory.record(event, session.id)
            send(event)
        }

        // INIT: 初始化
        phase = ConversationPhase.INIT
        logger.info("[Session ${session.id}] Starting enhanced conversation loop, userMessage length=${userMessage.length}")

        // 缓存系统提示（prefix cache 优化）
        if (cachedSystemPromptSessionId != session.id || cachedSystemPrompt == null) {
            cachedSystemPrompt = systemPrompt
            cachedSystemPromptSessionId = session.id
        }

        // 确保系统提示在上下文中
        ensureSystemPrompt(contextManager, systemPrompt)

        // 注入记忆系统提示块
        memoryManager?.let { mm ->
            val memSystemPrompt = mm.buildSystemPrompt()
            if (memSystemPrompt.isNotBlank()) {
                contextManager.injectMemoryContext(memSystemPrompt)
            }
        }

        // 添加用户消息（续跑模式下不重复添加）
        if (!isContinuation) {
            contextManager.addMessage(Message.userMessage(userMessage))
        } else {
            logger.info("[Session ${session.id}] Continuing conversation in continuation mode")
        }
        if (session.name.isBlank()) {
            session.name = userMessage.trim().take(30).let {
                if (userMessage.trim().length > 30) "$it..." else it
            }
        }

        // 预取记忆
        memoryManager?.let { mm ->
            val prefetched = mm.prefetchAll(userMessage, session.id)
            if (prefetched.isNotBlank()) {
                contextManager.injectMemoryContext(prefetched)
            }
        }

        // 记录上下文状态（用于诊断空响应问题）
        val contextAfterInit = contextManager.getContext()
        val systemMsgCount = contextAfterInit.count { it.role == Role.SYSTEM }
        logger.info("[Session ${session.id}] Context after INIT: ${contextAfterInit.size} messages, $systemMsgCount system messages, ~${contextManager.estimateTokens()} tokens")
        logger.debug(
            "[Session ${session.id}] Context messages: ${
                contextAfterInit.map {
                    "${it.role}:${
                        it.content.take(
                            40
                        )
                    }"
                }
            }"
        )

        hooks.onTurnStart(turnNumber, contextAfterInit)

        // 主循环
        while (taskBudget.consumeIteration() && taskBudget.checkTimeBudget() && !interrupted) {
            turnNumber++
            logger.info("[Turn $turnNumber] Budget remaining: ${taskBudget.remainingIterations()}, model: $currentModelLocal, summary=${taskBudget.summary()}")

            // 预算状态检查与分层预警
            val currentStatus = taskBudget.status()
            if (currentStatus != lastBudgetStatus && currentStatus.ordinal >= TaskBudget.BudgetStatus.WARNING.ordinal) {
                lastBudgetStatus = currentStatus
                emitEvent(
                    AgentStreamEvent.BudgetStatus(
                        status = currentStatus.name,
                        remainingIterations = taskBudget.remainingIterations(),
                        remainingTokens = taskBudget.remainingTokens(),
                        remainingSeconds = (taskBudget.remainingMs() / 1000).toInt(),
                        usagePercent = taskBudget.usagePercent()
                    )
                )
            }

            try {
                // LLM_CALL: 调用模型
                phase = ConversationPhase.LLM_CALL

                // Memory Nudge：每 N 轮提醒 Agent 回顾记忆
                memoryNudger?.onTurn()?.let { nudge ->
                    contextManager.injectMemoryContext(nudge)
                }

                val messages = contextManager.getContext()
                val processedMessages = hooks.preLLMCall(messages)

                // 根据模型能力决定是否发送 tools
                val adapter = gateway.getCurrentAdapter(currentModelLocal)
                if (adapter == null) {
                    logger.error("[Turn $turnNumber] No adapter found for model: $currentModelLocal")
                    emitEvent(AgentStreamEvent.Error("模型未配置: $currentModelLocal，请在设置中配置API提供商"))
                    break
                }
                val tools = if (adapter.supportsFunctionCalling()) {
                    toolRegistry.getAllTools()
                } else {
                    logger.info("[Turn $turnNumber] Model $currentModelLocal does not support function calling, omitting tools")
                    null
                }

                val request = ChatRequest(
                    model = currentModelLocal,
                    messages = processedMessages,
                    tools = tools,
                    temperature = 0.7,
                    stream = true
                )

                emitEvent(AgentStreamEvent.Thinking("思考中... (turn $turnNumber)"))

                // === 详细请求日志 ===
                val toolsCount = toolRegistry.getAllTools().size
                logger.info("[Turn $turnNumber] Sending request: model=$currentModelLocal, messages=${messages.size}, tools=$toolsCount")
                logger.debug("[Turn $turnNumber] Tools list: ${toolRegistry.getAllTools().map { it.name }}")

                // 流式请求：实时收集文本和工具调用增量
                val result = try {
                    var assistantContent = ""
                    val streamingToolCalls = mutableMapOf<Int, StreamingToolCallBuilder>()
                    var hasToolCalls = false
                    var finishReason: String? = null
                    var responseUsage: Usage? = null

                    gateway.chatStream(request).collect { chunk ->
                        if (interrupted) return@collect

                        // 先处理 finishReason（即使 done chunk 也可能携带）
                        if (chunk.finishReason != null) {
                            finishReason = chunk.finishReason
                            if (chunk.finishReason == "tool_calls") {
                                hasToolCalls = true
                            }
                        }

                        // 处理 done chunk：保存 usage 后返回
                        if (chunk.done) {
                            responseUsage = chunk.usage
                            return@collect
                        }

                        // 文本增量：实时 emit
                        if (chunk.delta.isNotEmpty()) {
                            assistantContent += chunk.delta
                            emitEvent(batchEmitter.acquireTextDelta(chunk.delta))
                        }

                        // 工具调用增量：检测开始、累积参数
                        for (tcDelta in chunk.toolCallDeltas) {
                            val builder = streamingToolCalls.getOrPut(tcDelta.index) {
                                StreamingToolCallBuilder()
                            }
                            if (tcDelta.id != null) builder.id = tcDelta.id
                            if (tcDelta.name != null) {
                                builder.name = tcDelta.name
                                if (builder.id.isNotEmpty()) {
                                    emitEvent(AgentStreamEvent.ToolCallStart(ToolCall(builder.id, builder.name, "")))
                                }
                            }
                            if (tcDelta.arguments != null) {
                                builder.arguments.append(tcDelta.arguments)
                                if (builder.id.isNotEmpty() && builder.name.isNotEmpty()) {
                                    emitEvent(
                                        AgentStreamEvent.ToolCallDelta(
                                            builder.id,
                                            builder.name,
                                            tcDelta.arguments
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // 将流式结果包装为 ChatResponse，复用后续处理逻辑
                    val toolCalls = if (hasToolCalls) {
                        streamingToolCalls.values
                            .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                            .map { ToolCall(it.id, it.name, it.arguments.toString()) }
                    } else null

                    val message = Message.assistantMessage(assistantContent, toolCalls)
                    val choice = Choice(index = 0, message = message, finishReason = finishReason)
                    Result.success(
                        ChatResponse(
                            id = "stream_${System.currentTimeMillis()}",
                            model = currentModelLocal,
                            choices = listOf(choice),
                            usage = responseUsage
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("[Turn $turnNumber] Streaming request failed: ${e.message}", e)
                    Result.failure(e)
                }

                result.fold(
                    onSuccess = { response ->
                        // 追踪 Token 消耗
                        response.usage?.let { usage ->
                            val totalTokens = usage.promptTokens + usage.completionTokens
                            taskBudget.recordTokens(totalTokens)
                            logger.info("[Turn $turnNumber] Token usage: prompt=${usage.promptTokens}, completion=${usage.completionTokens}, total=$totalTokens")
                        }

                        hooks.postApiRequest(response.choices.firstOrNull()?.message, null)

                        val assistantMsg = response.choices.firstOrNull()?.message
                            ?: Message.assistantMessage("")

                        logger.info("[Turn $turnNumber] LLM response: role=${assistantMsg.role}, contentLength=${assistantMsg.content.length}, toolCalls=${assistantMsg.toolCalls?.size ?: 0}")

                        // 记录空响应的上下文快照，用于诊断
                        if (assistantMsg.content.isNullOrBlank() && assistantMsg.toolCalls.isNullOrEmpty()) {
                            logger.warn("[Turn $turnNumber] LLM returned empty content and no tool calls. Context size=${contextManager.getContext().size}, tokens=${contextManager.estimateTokens()}")
                        }

                        if (!assistantMsg.toolCalls.isNullOrEmpty()) {
                            // TOOL_DISPATCH + TOOL_EXECUTE: 处理工具调用
                            phase = ConversationPhase.TOOL_DISPATCH
                            contextManager.addMessage(assistantMsg)

                            val allToolResults = mutableListOf<Triple<ToolCall, String, Boolean>>()

                            for (toolCall in assistantMsg.toolCalls) {
                                if (interrupted) break

                                phase = ConversationPhase.TOOL_EXECUTE
                                logger.info("[Tool] id=${toolCall.id}, name=${toolCall.name}, args=${toolCall.arguments}")
                                // ToolCallStart 已在流式检测阶段发出，此处不再重复
                                hooks.preToolExecution(toolCall.name, parseArguments(toolCall.arguments))

                                val toolStartTime = System.currentTimeMillis()
                                val toolResult = executeTool(toolCall, session, ::emitEvent)
                                val toolDuration = System.currentTimeMillis() - toolStartTime
                                val success = parseToolSuccess(toolResult)

                                logger.info("[Tool] id=${toolCall.id}, name=${toolCall.name}, success=$success, duration=${toolDuration}ms, resultLength=${toolResult.length}")
                                logger.debug("[Tool] id=${toolCall.id}, result=$toolResult")

                                hooks.postToolExecution(toolCall.name, toolResult, success)
                                emitEvent(
                                    AgentStreamEvent.ToolCallResult(
                                        toolCallId = toolCall.id,
                                        toolName = toolCall.name,
                                        result = toolResult,
                                        success = success
                                    )
                                )

                                allToolResults.add(Triple(toolCall, toolResult, success))
                            }

                            // RESULT_INTEGRATE: 整合工具结果到上下文
                            phase = ConversationPhase.RESULT_INTEGRATE
                            for ((toolCall, toolResult, _) in allToolResults) {
                                contextManager.addMessage(Message.toolMessage(toolResult, toolCall.id))
                            }

                            // POST_TURN_HOOK
                            phase = ConversationPhase.POST_TURN_HOOK
                            hooks.onTurnEnd(turnNumber, assistantMsg)

                            // 同步记忆
                            memoryManager?.syncAll(userMessage, assistantMsg.content, session.id)

                            // 继续循环，让 AI 处理工具结果
                        } else {
                            // COMPLETE: AI 返回最终文本回复
                            phase = ConversationPhase.COMPLETE
                            contextManager.addMessage(assistantMsg)

                            val content = assistantMsg.content
                            if (content.isNullOrBlank()) {
                                logger.warn("[Turn $turnNumber] LLM returned empty content, finishReason=${response.choices.firstOrNull()?.finishReason}")
                                emitEvent(AgentStreamEvent.Error("AI 返回了空回复，可能是上下文过长或请求异常，请重试或简化请求"))
                            } else {
                                // 文本已在流式传输中实时输出，无需再次模拟
                                logger.info("[Turn $turnNumber] Final text already streamed, length=${content.length}")
                            }

                            hooks.onTurnEnd(turnNumber, assistantMsg)

                            // 同步记忆
                            memoryManager?.syncAll(userMessage, assistantMsg.content, session.id)

                            // 成功完成，重置错误计数器
                            errorRecovery.resetAllCounters()
                            break
                        }
                    },
                    onFailure = { error ->
                        hooks.postApiRequest(null, error)

                        // === 详细错误日志 ===
                        logger.error("[Turn $turnNumber] LLM request failed: ${error.message}", error)

                        // ERROR_RECOVERY: 尝试恢复
                        phase = ConversationPhase.ERROR_RECOVERY
                        val classified = errorRecovery.classify(
                            error,
                            provider = "unknown",
                            model = currentModelLocal
                        )

                        logger.info("[Turn $turnNumber] Error classified as: ${classified.reason}, retryable=${classified.retryable}, message=${error.message}")

                        val action = errorRecovery.recover(
                            agent = agentCore ?: AgentCore(gateway),
                            classified = classified,
                            fallbackModels = fallbackModels
                        )

                        hooks.onErrorRecovery(classified, action)

                        when (action) {
                            is RecoveryAction.RetryWithModel -> {
                                logger.info("Falling back to model: ${action.model}")
                                currentModelLocal = agentCore?.getCurrentModel() ?: action.model
                                if (action.delayMs > 0) delay(action.delayMs)
                                emitEvent(AgentStreamEvent.Thinking("切换至后备模型 ${action.model} 重试..."))
                                // 继续循环，不 break
                            }

                            is RecoveryAction.CompressAndRetry -> {
                                logger.info("Context compression requested")
                                emitEvent(AgentStreamEvent.Thinking("上下文过长，正在压缩..."))
                                val originalTokens = contextManager.estimateTokens()
                                val compressed = agentCore?.compressContext() ?: false
                                if (compressed) {
                                    val compressedTokens = contextManager.estimateTokens()
                                    emitEvent(
                                        AgentStreamEvent.ContextCompressed(
                                            originalTokens = originalTokens,
                                            compressedTokens = compressedTokens,
                                            strategy = "llm_summarize"
                                        )
                                    )
                                    // Session 迁移：压缩后生成新 session，保持上下文连续性
                                    val migration = agentCore?.migrateSessionAfterCompression()
                                    if (migration != null) {
                                        val (oldId, newId) = migration
                                        val messageCount = contextManager.getContext().size
                                        session = agentCore?.getCurrentSession() ?: session
                                        emitEvent(
                                            AgentStreamEvent.SessionMigrated(
                                                oldSessionId = oldId,
                                                newSessionId = newId,
                                                messageCount = messageCount
                                            )
                                        )
                                        logger.info("Session migrated in loop: $oldId -> $newId")
                                    }
                                    // 退还预算（context 压缩后重置重试计数器，系统重试不占用户预算）
                                    taskBudget.refundIteration()
                                    emitEvent(AgentStreamEvent.Thinking("上下文已压缩，继续重试..."))
                                    // 继续循环，不 break
                                } else {
                                    emitEvent(AgentStreamEvent.Error("上下文压缩失败，请简化请求"))
                                    break
                                }
                            }

                            is RecoveryAction.RefreshAndRetry -> {
                                logger.info("Refreshing credentials and retrying")
                                if (action.delayMs > 0) delay(action.delayMs)
                                emitEvent(AgentStreamEvent.Thinking("刷新凭证后重试..."))
                                // 继续循环
                            }

                            is RecoveryAction.SimpleRetry -> {
                                logger.info("Simple retry with delay ${action.delayMs}ms")
                                if (action.delayMs > 0) delay(action.delayMs)
                                val prefillMsg = action.prefill
                                if (prefillMsg != null) {
                                    // 将 prefill 作为 assistant 消息注入上下文，引导模型继续生成
                                    // 这适用于 EMPTY_RESPONSE / INCOMPLETE_SCRATCHPAD 等场景
                                    contextManager.addMessage(Message.assistantMessage(prefillMsg))
                                    logger.info("Injected prefill message (${prefillMsg.length} chars) into context")
                                }
                                emitEvent(AgentStreamEvent.Thinking("重试中..."))
                                // 继续循环
                            }

                            is RecoveryAction.Abort -> {
                                logger.error("Aborting: ${action.message}")
                                emitEvent(AgentStreamEvent.Error(action.message))
                                break
                            }
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(
                    "[Turn $turnNumber] Unexpected error in phase $phase: ${e.javaClass.name}: ${e.message}",
                    e
                )

                val classified = errorRecovery.classify(e)
                logger.info("[Turn $turnNumber] Exception classified as: ${classified.reason}, message=${e.message}")

                val action = errorRecovery.recover(agentCore ?: AgentCore(gateway), classified, fallbackModels)

                when (action) {
                    is RecoveryAction.Abort -> {
                        logger.error("[Turn $turnNumber] Abort after recovery: ${action.message}")
                        emitEvent(AgentStreamEvent.Error("${action.message} (原始错误: ${e.message})"))
                        break
                    }

                    else -> {
                        emitEvent(AgentStreamEvent.Error("发生错误: ${e.message}，正在尝试恢复..."))
                        // 简单延迟后继续
                        delay(1000)
                    }
                }
            }
        }

        when {
            interrupted -> {
                phase = ConversationPhase.INTERRUPTED
                emitEvent(
                    AgentStreamEvent.BudgetExhausted(
                        reason = "对话被用户中断 (已完成 ${turnNumber} 轮)",
                        consumedIterations = taskBudget.netConsumedIterations(),
                        consumedTokens = taskBudget.consumedTokens(),
                        elapsedSeconds = (taskBudget.elapsedMs() / 1000).toInt(),
                        allowContinue = true
                    )
                )
            }

            taskBudget.isExhausted() -> {
                emitEvent(
                    AgentStreamEvent.BudgetExhausted(
                        reason = taskBudget.exhaustedReason(),
                        consumedIterations = taskBudget.netConsumedIterations(),
                        consumedTokens = taskBudget.consumedTokens(),
                        elapsedSeconds = (taskBudget.elapsedMs() / 1000).toInt(),
                        allowContinue = true
                    )
                )
            }
        }

        stateFlow.value = AgentState.IDLE
        emitEvent(AgentStreamEvent.Done)
        if (turnNumber == 0) {
            logger.warn(
                "[Session ${session.id}] Conversation loop ended without executing any turns! " +
                        "phase=$phase, interrupted=$interrupted, budgetExhausted=${taskBudget.isExhausted()}, " +
                        "budgetSummary=${taskBudget.summary()}"
            )
        } else {
            logger.info("[Session ${session.id}] Conversation loop ended in phase: $phase after $turnNumber turns")
        }

        // 每个任务结束后重置错误恢复计数器，避免影响后续任务
        errorRecovery.resetAllCounters()

        // T0.2 修复：释放 EventBatchEmitter 资源，避免协程泄漏
        batchEmitter.shutdown()
    }

    /**
     * 发送中断信号
     */
    fun interrupt() {
        interrupted = true
        // T0.2 修复：同时关阖 emitter 避免其内部协程泄漏
        batchEmitter.shutdown()
        logger.info("Conversation loop interrupt signal sent")
    }

    /**
     * 检查是否已被中断
     */
    fun isInterrupted(): Boolean = interrupted

    /**
     * 解析工具执行结果中的 success 字段
     */
    private fun parseToolSuccess(toolResult: String): Boolean {
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(toolResult)
            val jsonObj = element as? kotlinx.serialization.json.JsonObject
            val successPrimitive = jsonObj?.get("success") as? kotlinx.serialization.json.JsonPrimitive
            successPrimitive?.content != "false"
        } catch (e: Exception) {
            // 非 JSON 或解析失败时，保守判定为失败
            false
        }
    }

    /**
     * 清除系统提示缓存（用于系统提示变更时）
     */
    fun invalidateSystemPromptCache() {
        cachedSystemPrompt = null
        cachedSystemPromptSessionId = null
    }

    // 内部方法

    private suspend fun executeTool(
        toolCall: ToolCall,
        session: AgentSession,
        emit: suspend (AgentStreamEvent) -> Unit
    ): String {
        return when {
            toolCall.name.startsWith("skill_") -> {
                skillToolAdapter?.execute(toolCall.name, toolCall.arguments)
                    ?: "{\"success\":false,\"error\":\"Skill adapter not available\"}"
            }

            toolCall.name.startsWith("memory_") -> {
                memoryManager?.handleToolCall(toolCall.name, parseArguments(toolCall.arguments))
                    ?: "{\"success\":false,\"error\":\"Memory manager not available\"}"
            }

            toolCall.name == "delegate_task" -> {
                executeDelegateTask(toolCall, session, emit)
            }

            else -> try {
                toolExecutor.execute(toolCall)
            } catch (e: com.codesage.tools.guardrails.ToolExecutionBlocked) {
                val escapedMessage = e.message?.replace("\"", "\\\"") ?: "Execution blocked"
                "{\"success\":false,\"error\":\"$escapedMessage\",\"reason\":\"${e.reason.name}\",\"tool\":\"${e.toolName ?: toolCall.name}\"}"
            }
        }
    }

    private suspend fun executeDelegateTask(
        toolCall: ToolCall,
        session: AgentSession,
        emit: suspend (AgentStreamEvent) -> Unit
    ): String {
        val args = parseArguments(toolCall.arguments)
        val executor = subAgentExecutor
            ?: return "{\"success\":false,\"error\":\"SubAgent executor not configured\"}"

        val taskDescription = args["task_description"] as? String
            ?: return "{\"success\":false,\"error\":\"Missing task_description\"}"
        val toolset = args["toolset"] as? String ?: "dev"
        val maxIterations = (args["max_iterations"] as? Number)?.toInt() ?: 10
        val contextFiles = when (val files = args["context_files"]) {
            is List<*> -> files.filterIsInstance<String>()
            else -> emptyList()
        }

        return try {
            emit(
                AgentStreamEvent.SubAgentStart(
                    sessionId = "sub_${session.id}_${System.currentTimeMillis()}",
                    taskDescription = taskDescription,
                    toolset = toolset
                )
            )

            val result = executor.spawn(
                parentSessionId = session.id,
                taskDescription = taskDescription,
                toolset = toolset,
                maxIterations = maxIterations,
                contextFiles = contextFiles,
                parentBudget = currentTaskBudget,
                progressCallback = { progress ->
                    try {
                        emit(
                            AgentStreamEvent.SubAgentProgress(
                                sessionId = "sub_${session.id}",
                                message = progress
                            )
                        )
                    } catch (e: Exception) {
                        logger.warn("Failed to emit sub-agent progress: ${e.message}")
                    }
                }
            )

            emit(
                AgentStreamEvent.SubAgentComplete(
                    sessionId = result.sessionId,
                    success = result.success,
                    output = result.output
                )
            )

            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.JsonObject(
                    mapOf(
                        "success" to kotlinx.serialization.json.JsonPrimitive(result.success),
                        "output" to kotlinx.serialization.json.JsonPrimitive(result.output),
                        "session_id" to kotlinx.serialization.json.JsonPrimitive(result.sessionId),
                        "iterations_used" to kotlinx.serialization.json.JsonPrimitive(result.iterationsUsed),
                        "tools_used" to kotlinx.serialization.json.JsonArray(
                            result.toolsUsed.map { kotlinx.serialization.json.JsonPrimitive(it) }
                        )
                    )
                )
            )
        } catch (e: Exception) {
            emit(
                AgentStreamEvent.SubAgentComplete(
                    sessionId = "sub_${session.id}",
                    success = false,
                    output = e.message ?: "Unknown error"
                )
            )
            "{\"success\":false,\"error\":\"SubAgent execution failed: ${e.message}\"}"
        }
    }

    private fun parseArguments(arguments: String): Map<String, Any> {
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(arguments)
            if (element is kotlinx.serialization.json.JsonObject) {
                element.mapValues { (_, value) ->
                    when (value) {
                        is kotlinx.serialization.json.JsonPrimitive -> value.content
                        else -> value.toString()
                    }
                }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun ensureSystemPrompt(contextManager: ContextManager, systemPrompt: String) {
        val context = contextManager.getContext()
        // 精确匹配原始系统提示，避免被 [CONTEXT SUMMARY] 等系统消息误导
        val hasOriginalPrompt = context.any {
            it.role == Role.SYSTEM && it.content == systemPrompt
        }
        if (!hasOriginalPrompt) {
            logger.info("Original system prompt missing, injecting it back")
            contextManager.addMessage(Message.systemMessage(systemPrompt))
        }
    }

    private suspend fun emitStreamedText(
        content: String,
        emit: suspend (AgentStreamEvent) -> Unit
    ) {
        var emitted = 0
        val chunkSize = 240 // 增大 chunk 减少事件数量
        while (emitted < content.length) {
            val end = (emitted + chunkSize).coerceAtMost(content.length)
            emit(batchEmitter.acquireTextDelta(content.substring(emitted, end)))
            emitted = end
            if (content.length > 50) delay(8) // 降低延迟提升流畅度
        }
    }

    /**
     * 流式工具调用构建器
     * 用于在 SSE 流中逐步累积单个工具调用的 id、name 和 arguments
     */
    private class StreamingToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

}
