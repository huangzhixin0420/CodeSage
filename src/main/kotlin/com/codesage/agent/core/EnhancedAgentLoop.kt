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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.booleanOrNull

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

    // 外层 catch 触发计数器：主循环在 try 体内嵌套了多层错误恢复（流式内层 try-catch
    // → onFailure → recover，onSuccess 阶段的 tool executor 等），理论上所有可恢复错误
    // 都应该在内层处理完。但若 recover() 自身抛异常、或内层分支误抛，异常会冒到
    // 外层 catch。原实现的 else 分支只 delay 1 秒后继续循环——这意味着只要外层
    // recover() 持续返回非 Abort 动作（例如新 reason 的 SimpleRetry），循环就不会结束。
    //
    // 防御措施：
    // 1. 外层 catch 强制 break——它本来就是"未预料的异常"路径，不应该让主循环继续
    // 2. 再加一层显式次数上限作为兜底（防御性，正常路径应在内层已 abort）
    private var outerCatchCount = 0


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
        var phase = ConversationPhase.INIT
        var currentModelLocal = currentModel
        var turnNumber = 0

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
        while (!interrupted) {
            turnNumber++
            // 主动检查父协程 / Job 状态：父被 cancel 时此处的结构化并发会立刻抛
            // CancellationException，比 "等 LLM 流读完" 早很多收到取消信号
            currentCoroutineContext().ensureActive()
            logger.info("[Turn $turnNumber] model: $currentModelLocal")

            try {
                // LLM_CALL: 调用模型
                phase = ConversationPhase.LLM_CALL

                // Memory Nudge：每 N 轮提醒 Agent 回顾记忆
                memoryNudger?.onTurn()?.let { nudge ->
                    contextManager.injectMemoryContext(nudge)
                }

                val messages = contextManager.getContext()
                // 清理 orphan tool_result：tool_result 必须有对应的 tool_use。
                // 上下文来源：用户上轮代理 Claude 的提供商（如 MiniMax、、
                // 其它中文 LLM 代理 Claude 的提供商）会偶发返回 tool_result id
                // 与上下文里同名 tool_use id 不一致的请求。API 返 2013
                // 'tool result\'s tool id (xxx) not found' 后 400。预防：只发
                // 有匹配的 tool_result。孤儿会被丢 + 记 WARN。
                val toolUseIds = messages
                    .flatMap {
                        it.toolCalls?.mapNotNull { tc -> tc.id.takeIf { id -> id.isNotEmpty() } } ?: emptyList()
                    }
                    .toSet()
                val (cleanedMessages, orphanCount) = cleanupOrphanToolResults(messages, toolUseIds)
                if (orphanCount > 0) {
                    logger.warn(
                        "[Turn $turnNumber] Cleaned $orphanCount orphan tool pair(s) " +
                                "(orphan tool_result + unfulfilled tool_use) before sending to LLM"
                    )
                }
                val processedMessages = hooks.preLLMCall(cleanedMessages)

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

                        val action = try {
                            errorRecovery.recover(
                                agent = agentCore ?: AgentCore(gateway),
                                classified = classified,
                                fallbackModels = fallbackModels
                            )
                        } catch (recoverEx: Exception) {
                            // recover 自身抛异常——把异常降级为 Abort 并跳出内层
                            // onFailure，让外层 catch 看到 Abort 走 break 路径。
                            logger.error(
                                "[Turn $turnNumber] inner recover() threw: " +
                                        "${recoverEx.javaClass.simpleName}: ${recoverEx.message}",
                                recoverEx
                            )
                            RecoveryAction.Abort(
                                "错误恢复失败 (${recoverEx.javaClass.simpleName}: ${recoverEx.message?.take(200)})"
                            )
                        }

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

                val action = try {
                    errorRecovery.recover(agentCore ?: AgentCore(gateway), classified, fallbackModels)
                } catch (recoverEx: Exception) {
                    // recover() 自身抛异常（罕见，例如 agent.switchModel/compressContext 抛
                    // 网络/IO 错误）。原实现会让异常继续冒到外层 catch 的 else 分支，
                    // 触发无限循环。这里把异常降级为 Abort，避免在已经错乱的状态上继续重试。
                    logger.error(
                        "[Turn $turnNumber] errorRecovery.recover() itself threw: " +
                                "${recoverEx.javaClass.name}: ${recoverEx.message}",
                        recoverEx
                    )
                    RecoveryAction.Abort(
                        "错误恢复失败 (${recoverEx.javaClass.simpleName}: ${recoverEx.message?.take(200)})"
                    )
                }

                outerCatchCount++
                when (action) {
                    is RecoveryAction.Abort -> {
                        logger.error("[Turn $turnNumber] Abort after recovery: ${action.message}")
                        emitEvent(AgentStreamEvent.Error("${action.message} (原始错误: ${e.message})"))
                        break
                    }

                    else -> {
                        // 兜底：理论上 recover 总会返回 Abort（无论内层或外层触发），
                        // 但万一因为 reason key 重新计数等原因返回了非 Abort，
                        // 这里也强制 break 而不是继续循环——主循环不应该在未预料的
                        // 异常路径上无限重试。
                        outerCatchCount++
                        logger.error(
                            "[Turn $turnNumber] Outer catch non-abort action=${action::class.simpleName} " +
                                    "after $outerCatchCount outer catches; force breaking to avoid loop"
                        )
                        emitEvent(AgentStreamEvent.Error(
                            "未预料的错误恢复动作 (${action::class.simpleName})，强制终止。原始错误: ${e.message}"
                        ))
                        break
                    }
                }
            }
        }

        stateFlow.value = AgentState.IDLE
        emitEvent(AgentStreamEvent.Done)
        if (turnNumber == 0) {
            logger.warn(
                "[Session ${session.id}] Conversation loop ended without executing any turns! " +
                        "phase=$phase, interrupted=$interrupted"
            )
        } else {
            logger.info("[Session ${session.id}] Conversation loop ended in phase: $phase after $turnNumber turns")
        }

        // 每个任务结束后重置错误恢复计数器，避免影响后续任务
        errorRecovery.resetAllCounters()
        outerCatchCount = 0

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
     *
     * H8 修复：原实现用 `content != "false"` 做判定，会把以下情况误判为 success：
     * - `{"success": 0}` → content="0"，判定成功（语义是失败）
     * - `{"success": "0"}` → content="0"，判定成功
     * - `{"success": null}` → `as? JsonPrimitive` 失败 → 走 elvis → 判定成功（最严重）
     * - `{"success": false}` 走 `booleanOrNull` 才是唯一正确路径
     *
     * 正确判定规则：
     * 1. JSON 解析失败 → false
     * 2. 没有 "success" 字段 → false（不假定存在）
     * 3. success 是字面量 `false` → false
     * 4. success 是字面量 `true` → true
     * 5. 其它（数字、字符串、null） → false（保守，避免误判）
     */
    private fun parseToolSuccess(toolResult: String): Boolean {
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(toolResult)
            val jsonObj = element as? kotlinx.serialization.json.JsonObject
                ?: return false
            val successElement = jsonObj["success"]
                ?: return false
            val prim = successElement as? kotlinx.serialization.json.JsonPrimitive
                ?: return false
            // booleanOrNull 仅当 literal 是 true/false 时返回非 null
            // 字符串 "true"/"false"、数字 0/1、null 全部返回 null（→ false）
            prim.booleanOrNull ?: false
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
        val executor = subAgentExecutor ?: run {
            // 之前这里只 return 一行 JSON 错误，不知道是 wiring 断在哪一段
            logger.error(
                "[DelegateTask] subAgentExecutor is null | toolCallId=${toolCall.id} " +
                    "parentSession=${session.id} — wiring broken (AgentCore.subAgentDepth " +
                    "or EnhancedAgentLoop 构造参数透传失败)"
            )
            return "{\"success\":false,\"error\":\"SubAgent executor not configured\"}"
        }

        val taskDescription = args["task_description"] as? String
        if (taskDescription == null) {
            // 不再静默：打 WARN 把 raw args + 解析结果打出来，方便定位是
            // 1) LLM 真的没传 task_description
            // 2) LLM 传了但 JSON 畸形（parseArguments 吞错返回 emptyMap）
            // 3) LLM 传了但 value 类型不是 string（罕见）
            logger.warn(
                "[DelegateTask] task_description missing | toolCallId=${toolCall.id} " +
                    "rawArgs=${toolCall.arguments.take(500)} " +
                    "parsedKeys=${args.keys} " +
                    "taskDescriptionType=${args["task_description"]?.javaClass?.simpleName}"
            )
            return "{\"success\":false,\"error\":\"Missing task_description\"}"
        }
        val toolset = args["toolset"] as? String ?: "dev"
        val contextFiles = when (val files = args["context_files"]) {
            is List<*> -> files.filterIsInstance<String>()
            else -> emptyList()
        }

        // 兼容老 LLM prompt / 老用户习惯：曾几何时 delegate_task 工具的 schema
        // 里有 `max_iterations` 参数（已被 f8000c7 移除），如果模型还在传，
        // 打个 WARN 让用户能从 idea.log 看到"这个参数已废弃、被忽略"。
        // 不抛错、不返回 JSON 错误——保证老 prompt 仍然能跑。
        if (args.containsKey("max_iterations")) {
            logger.warn(
                "[DelegateTask] ignoring deprecated parameter 'max_iterations' " +
                    "(value=${args["max_iterations"]}); the budget system was removed in f8000c7."
            )
        }

        // 关键修复：subSessionId 在 executeDelegateTask 入口生成一次，
        // 后面 SubAgentStart / SubAgentProgress / SubAgentComplete / catch 兜底
        // **全部复用同一个 id**。修复了之前 EventRouter 因 sessionId 漂移导致
        // task / toolset / elapsedMs 全为空的 UI bug。
        val subSessionId = "sub_${session.id}_${System.currentTimeMillis()}"
        val startTs = System.currentTimeMillis()
        logger.info(
            "[DelegateTask] entry | toolCallId=${toolCall.id} parentSession=${session.id} " +
                "subSession=$subSessionId toolset=$toolset taskLen=${taskDescription.length} " +
                "contextFiles=${contextFiles.size}"
        )

        return try {
            emit(
                AgentStreamEvent.SubAgentStart(
                    sessionId = subSessionId,
                    taskDescription = taskDescription,
                    toolset = toolset
                )
            )

            val result = executor.spawn(
                parentSessionId = session.id,
                taskDescription = taskDescription,
                toolset = toolset,
                contextFiles = contextFiles,
                // P2: 透传父协程 Job，让子 agent 在父被 cancel 时能感知
                // （结构化并发 + catch CancellationException 抽 cancelled summary）
                parentJob = currentCoroutineContext()[Job],
                // 关键：把入口生成的 subSessionId 透传给 spawn，让 SubAgentComplete
                // 用的 sessionId 跟 Start 是同一个串。
                subSessionIdOverride = subSessionId,
                progressCallback = { progress ->
                    try {
                        emit(
                            AgentStreamEvent.SubAgentProgress(
                                sessionId = subSessionId,
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
                    sessionId = subSessionId,
                    success = result.success,
                    output = result.output,
                    iterationsUsed = result.iterationsUsed,
                    toolsUsed = result.toolsUsed,
                    // P2: 透传 cancelled 状态和已完成 tool calls
                    cancelled = result.cancelled,
                    completedToolCalls = result.completedToolCalls
                )
            )

            logger.info(
                "[DelegateTask] done | toolCallId=${toolCall.id} subSession=$subSessionId " +
                    "success=${result.success} cancelled=${result.cancelled} " +
                    "iterations=${result.iterationsUsed} tools=${result.toolsUsed} " +
                    "elapsedMs=${System.currentTimeMillis() - startTs}"
            )

            // P1: 返回纯文本摘要（不再 JSON 包装）。
            // 父 LLM 收到的是子 agent 的自然语言最终 turn（见 buildSubAgentPrompt 的
            // "Final-Turn Output Contract"）。iterations / tools 等元数据通过
            // SubAgentComplete 事件给 UI，不进父 LLM context。
            result.output
        } catch (e: Exception) {
            // 关键修复：之前 catch 里 SubAgentComplete.sessionId = "sub_${session.id}"
            // （无时间戳），跟 Start 不匹配，UI 看到 start/complete 两个不同 sub-agent。
            // 现在统一用 subSessionId。
            emit(
                AgentStreamEvent.SubAgentComplete(
                    sessionId = subSessionId,
                    success = false,
                    output = e.message ?: "Unknown error"
                )
            )
            // 关键修复：之前 `e` 没传给 logger，栈丢光。现在带 throwable 一起进 idea.log。
            logger.error(
                "[DelegateTask] failed | toolCallId=${toolCall.id} subSession=$subSessionId " +
                    "elapsedMs=${System.currentTimeMillis() - startTs}",
                e
            )
            // P1: 失败也走纯文本兜底，不返回 JSON
            "SubAgent execution failed: ${e.message}"
        }
    }

    /**
     * 清理 orphan tool 配对：
     * 1. orphan tool_result：tool_result 消息的 toolCallId 必须在 assistant
     *    消息的 toolCalls[].id 集合里出现过，否则丢掉。
     * 2. 未完成 tool_use：assistant 消息的 toolCalls[].id 里如果有 id 在后续
     *    tool_result 中找不到对应（这条 assistant 之后没有任何 tool_result
     *    消息），把这条 assistant 消息的 toolCalls 置空（仅当它没在 textContent
     *    之外携带有用信息时）。保守起见：只清空 toolCalls 字段，保留 assistant
     *    文本内容。
     *
     * 为什么需要：中文 LLM 代理 Claude 的提供商（MiniMax、智谱、月之暗面等）
     * 在转 OpenAI/Anthropic 双向格式时偶尔会丢失 tool_call_id 对应关系，导致
     * 下一个 turn 请求带 "tool result does not follow tool call (2013)" 400。
     * 预防性在 EnhancedAgentLoop 请求前清掉孤儿 tool_result / 未完成 tool_use，
     * 避免触发 API 2013。
     *
     * 返回 (cleanedMessages, orphanCount)。cleanedMessages 是丢完后的
     * 消息列表（顺序、其它消息原封不动）。orphanCount 包含丢弃的 tool_result
     * 数量 + 清理的未完成 tool_use 数量。
     */
    private fun cleanupOrphanToolResults(
        messages: List<Message>,
        toolUseIds: Set<String>
    ): Pair<List<Message>, Int> {
        var orphanCount = 0

        // 第一遍：先收集 assistant 消息中所有声明的 tool_use id，
        // 以及后续 tool_result 实际提供的 id 集合。
        // 用 map<assistant_index, declared_ids> 跟踪。
        val assistantDeclaredByIndex = mutableMapOf<Int, Set<String>>()
        val toolResultIds = mutableSetOf<String>()
        messages.forEachIndexed { idx, msg ->
            if (msg.role == Role.ASSISTANT && !msg.toolCalls.isNullOrEmpty()) {
                assistantDeclaredByIndex[idx] = msg.toolCalls.mapNotNull { it.id.takeIf { id -> id.isNotEmpty() } }.toSet()
            } else if (msg.role == Role.TOOL) {
                msg.toolCallId?.takeIf { it.isNotEmpty() }?.let { toolResultIds.add(it) }
            }
        }

        // 第二遍：构建 cleanedMessages
        val result = messages.mapIndexed { idx, msg ->
            when (msg.role) {
                Role.TOOL -> {
                    val id = msg.toolCallId
                    if (id.isNullOrBlank() || id !in toolUseIds) {
                        orphanCount++
                        null
                    } else {
                        msg
                    }
                }
                Role.ASSISTANT -> {
                    val declared = assistantDeclaredByIndex[idx] ?: return@mapIndexed msg
                    // assistant 声明了 tool_use，但 toolResultIds 里完全没有它的对应
                    // 说明这条 assistant 的 tool_use 没完成。**部分**未完成也会触发
                    // strict 提供商（如 MiniMax-M3 / DeepSeek）返 "tool call result
                    // does not follow tool call (2013)" 400——assistant.toolCalls 里
                    // 留有 unfulfilled 的 id = 声明了一个没有 result 的 tool_use。
                    // 修复：之前保守策略只在"全部未完成"时清空 toolCalls，"部分未完成"
                    // 时保留整个 toolCalls，导致 strict provider 2013。子 agent 在
                    // 短 context 下尤其容易撞上（parent 多 turn 时 partial 罕见）。
                    // 保留 content 字段（assistant 可能也写了文本）。
                    val unfulfilled = declared - toolResultIds
                    when {
                        unfulfilled.isEmpty() -> msg  // 全部有 tool_result，OK
                        unfulfilled.size == declared.size -> {
                            // 全部没完成——这条 assistant 整个 toolCalls 都丢
                            orphanCount += unfulfilled.size
                            logger.warn(
                                "[cleanup] Dropping ${unfulfilled.size} unfulfilled tool_use(s) " +
                                        "from assistant message at index=$idx (all unfulfilled): $unfulfilled"
                            )
                            msg.copy(toolCalls = null)
                        }
                        else -> {
                            // 部分没完成 — 只过滤掉 unfulfilled 的 tool_use，保留
                            // 已有 tool_result 的那些。assistant 文本保留不变。
                            val fulfilled = msg.toolCalls!!.filterNot { it.id in unfulfilled }
                            orphanCount += unfulfilled.size
                            logger.warn(
                                "[cleanup] Dropping ${unfulfilled.size} unfulfilled tool_use(s) " +
                                        "from assistant message at index=$idx (partial): $unfulfilled; " +
                                        "keeping ${fulfilled.size} fulfilled tool_use(s): " +
                                        "${fulfilled.mapNotNull { it.id }}"
                            )
                            msg.copy(toolCalls = fulfilled)
                        }
                    }
                }
                else -> msg
            }
        }.filterNotNull()
        return result to orphanCount
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
