package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.memory.MemoryManager
import com.codesage.agent.memory.MemoryNudger
import com.codesage.agent.planner.AgentCoreStepExecutor
import com.codesage.agent.planner.DagTaskPlan
import com.codesage.agent.planner.Task
import com.codesage.agent.planner.TaskPlanner
import com.codesage.agent.tools.SkillToolAdapter
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import com.codesage.mcp.server.MCPServerManager
import com.codesage.observability.*
import com.codesage.perf.ResponseCache
import com.codesage.persistence.ConversationPersistence
import com.codesage.persistence.SessionRestore
import com.codesage.persistence.toMessage
import com.codesage.prompt.cache.SystemPromptCache
import com.codesage.prompt.engine.PromptAssembler
import com.codesage.prompt.engine.PromptRole
import com.codesage.shared.config.PluginConfig
import com.codesage.shared.utils.Logger
import com.codesage.tools.guardrails.ToolGuardrails
import com.intellij.openapi.project.Project
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Agent会话状态
 */
data class AgentSession(
    val id: String,
    var name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var lastActivityAt: Long = System.currentTimeMillis(),
    var isActive: Boolean = true
) {
    fun displayName(): String = name.ifEmpty { "新会话 ${formatTime(createdAt)}" }

    private fun formatTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}

/**
 * Agent状态
 */
enum class AgentState {
    IDLE,          // 空闲
    THINKING,      // 思考中
    EXECUTING,     // 执行中
    STREAMING,     // 流式响应
    WAITING_TOOL,  // 等待工具结果
    ERROR
}

/**
 * Agent执行结果
 */
sealed class AgentResult {
    data class Success(
        val message: Message,
        val session: AgentSession
    ) : AgentResult()

    data class Failure(
        val error: String,
        val session: AgentSession
    ) : AgentResult()

    data class ToolCalls(
        val toolCalls: List<ToolCall>,
        val session: AgentSession
    ) : AgentResult()
}

/**
 * Agent核心类（门面模式）
 *
 * 负责AI对话循环、任务执行、工具调用和多会话管理。
 * 核心对话循环已委托给 [EnhancedAgentLoop]，本类保持向后兼容的公共 API。
 */
class AgentCore(
    private val gateway: ModelGateway = ModelGateway.getInstance(),
    private val taskPlanner: TaskPlanner = TaskPlanner(),
    project: Project? = null,
    skillToolAdapter: SkillToolAdapter? = null,
    confirmationCallback: ToolGuardrails.ConfirmationCallback? = null
) {
    private val logger = Logger.getLogger<AgentCore>()

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private data class SessionInfo(
        val session: AgentSession,
        val contextManager: ContextManager
    )

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, SessionInfo>()

    @Volatile
    private var currentSessionId: String? = null
    private var currentModel: String = "MiniMax-Text-01"
    private var systemPrompt: String = AgentConfig.DEFAULT_SYSTEM_PROMPT
    private var currentBudgetConfig: TaskBudget.BudgetConfig? = null

    // 工具系统
    private val toolRegistry: ToolRegistry = ToolRegistry.createDefault()
    private val guardrails: ToolGuardrails? = project?.let {
        ToolGuardrails(projectRoot = it.basePath, confirmationCallback = confirmationCallback)
    }
    private val toolExecutor: ToolExecutor = ToolExecutor(project, guardrails)
    private val skillToolAdapter: SkillToolAdapter? = skillToolAdapter

    // 错误恢复
    private val errorRecovery: AgentErrorRecovery = AgentErrorRecovery()

    // 增强型对话循环
    private lateinit var enhancedLoop: EnhancedAgentLoop

    // 记忆系统
    private val memoryManager: MemoryManager = MemoryManager()
    private val memoryNudger: MemoryNudger = MemoryNudger()

    // 子 Agent 执行器
    private val subAgentExecutor: SubAgentExecutor = SubAgentExecutor(
        parentAgent = this,
        gateway = gateway,
        project = project,
        skillToolAdapter = skillToolAdapter
    )

    // Prompt 工程
    private val promptAssembler: PromptAssembler = PromptAssembler(toolRegistry = toolRegistry)

    // MCP 生态
    private val mcpServerManager: MCPServerManager = MCPServerManager()

    // 可观测性
    private val structuredLogger: StructuredLogger = StructuredLogger()
    private val metrics: MetricsCollector = MetricsCollector()
    private val tracer: ExecutionTracer = ExecutionTracer()

    // 性能优化
    private val responseCache: ResponseCache = ResponseCache()
    private val promptCache: SystemPromptCache = SystemPromptCache()

    // 对话持久化
    private val conversationPersistence: ConversationPersistence = ConversationPersistence()
    private lateinit var sessionRestore: SessionRestore

    // 钩子（默认空实现，可通过配置注入）
    private var hooks: AgentHooks = object : AgentHooks {}

    // 协程作用域
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 当前正在运行的 chat Job，用于中断
    private val currentChatJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)

    /**
     * 初始化Agent
     */
    fun initialize(config: AgentConfig) {
        currentModel = config.defaultModel
        currentBudgetConfig = config.budgetConfig

        // 使用 PromptAssembler 动态组装系统提示（带缓存）
        val assembledPrompt = if (config.systemPrompt != AgentConfig.DEFAULT_SYSTEM_PROMPT) {
            config.systemPrompt
        } else {
            promptAssembler.assembleWithTools(
                tools = toolRegistry.getAllTools(),
                context = PromptAssembler.AssemblyContext(
                    role = PromptRole.ASSISTANT,
                    hasMemory = true,
                    hasSubAgent = true,
                    toolCount = toolRegistry.getAllTools().size
                )
            )
        }
        // 尝试命中缓存：相同 hash 的系统提示直接复用
        systemPrompt = promptCache.getCachedPrompt(assembledPrompt) ?: run {
            promptCache.cachePrompt(version = "1.0", systemPrompt = assembledPrompt)
            assembledPrompt
        }

        // 注册技能为工具
        this.skillToolAdapter?.let { adapter ->
            adapter.toTools().forEach { toolRegistry.register(it) }
            logger.info("Registered ${adapter.toTools().size} skills as tools")
        }

        // 注册记忆工具
        memoryManager.getAllToolSchemas().forEach { toolRegistry.register(it) }
        logger.info("Registered ${memoryManager.getAllToolSchemas().size} memory tools")

        // 初始化对话持久化
        sessionRestore = SessionRestore(conversationPersistence, this)

        // 初始化增强型对话循环
        enhancedLoop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            skillToolAdapter = skillToolAdapter,
            errorRecovery = errorRecovery,
            hooks = hooks,
            stateFlow = _state,
            memoryManager = memoryManager,
            memoryNudger = memoryNudger,
            subAgentExecutor = subAgentExecutor,
            agentCore = this
        )

        // 尝试恢复之前的会话
        restoreSessions(SessionRestore.RestoreOptions(strategy = SessionRestore.RestoreStrategy.RESTORE_ALL))

        // 如果没有会话，自动创建一个
        if (sessions.isEmpty()) {
            createSession()
        }

        // 启动自动保存
        sessionRestore.startAutoSave(agentScope)

        // 注册性能指标
        metrics.registerGauge("active_sessions") { sessions.size.toLong() }

        logger.info("AgentCore initialized with model: ${config.defaultModel}, tools: ${toolRegistry.getAllTools().size}")
        structuredLogger.info(
            "agent", "init", "AgentCore initialized", metadata = mapOf(
                "model" to config.defaultModel,
                "toolCount" to toolRegistry.getAllTools().size.toString()
            )
        )
    }

    /**
     * 配置钩子（用于扩展和自定义行为）
     */
    fun setHooks(newHooks: AgentHooks) {
        hooks = newHooks
        if (::enhancedLoop.isInitialized) {
            // 重新创建 enhancedLoop 以应用新 hooks
            enhancedLoop = EnhancedAgentLoop(
                gateway = gateway,
                toolRegistry = toolRegistry,
                toolExecutor = toolExecutor,
                skillToolAdapter = skillToolAdapter,
                errorRecovery = errorRecovery,
                hooks = newHooks,
                stateFlow = _state,
                memoryManager = memoryManager,
                memoryNudger = memoryNudger,
                subAgentExecutor = subAgentExecutor
            )
        }
    }

    /**
     * 创建新会话
     */
    fun createSession(): AgentSession {
        val traceCtx = tracer.startTrace("create_session")
        val session = AgentSession(
            id = "session_${System.currentTimeMillis()}",
            name = "",
            createdAt = System.currentTimeMillis()
        )
        val contextManager = ContextManager(memoryProvider = memoryManager.getBuiltInProvider())
        contextManager.newSession(listOf(Message.systemMessage(systemPrompt)), newSessionId = session.id)

        sessions[session.id] = SessionInfo(session, contextManager)
        currentSessionId = session.id

        // 初始化记忆系统
        val homeDir = File(System.getProperty("user.home"), ".codesage").absolutePath
        memoryManager.initializeAll(session.id, homeDir)
        memoryNudger.reset()

        metrics.incrementCounter("sessions_created")
        traceCtx.end()
        return session
    }

    /**
     * 切换当前会话
     */
    fun switchSession(sessionId: String): Boolean {
        if (sessions.containsKey(sessionId)) {
            currentSessionId = sessionId
            memoryManager.onSessionSwitch(sessionId)
            return true
        }
        return false
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        if (currentSessionId == sessionId) {
            currentSessionId = sessions.keys.firstOrNull()
        }
    }

    /**
     * 关闭AgentCore，释放资源
     */
    fun shutdown() {
        agentScope.cancel()
        sessions.clear()
        currentSessionId = null
        if (::enhancedLoop.isInitialized) {
            enhancedLoop.interrupt()
        }
        logger.info("AgentCore shutdown completed")
    }

    /**
     * 重命名会话
     */
    fun renameSession(sessionId: String, name: String) {
        sessions[sessionId]?.session?.name = name
    }

    /**
     * 保存当前会话（仅当包含用户消息时才保存）
     */
    fun saveCurrentSession() {
        val sessionInfo = currentSessionId?.let { sessions[it] } ?: return
        val context = sessionInfo.contextManager.getContext()
        val hasUserMessage = context.any { it.role == Role.USER }
        if (hasUserMessage) {
            conversationPersistence.saveSession(sessionInfo.session, context)
        }
    }

    /**
     * 从持久化数据恢复单个会话
     */
    fun restoreSession(persisted: com.codesage.persistence.PersistedSession): AgentSession {
        val session = AgentSession(
            id = persisted.id,
            name = persisted.name,
            createdAt = persisted.createdAt,
            lastActivityAt = persisted.lastActivityAt,
            isActive = persisted.isActive
        )
        val contextManager = ContextManager(memoryProvider = memoryManager.getBuiltInProvider())
        var messages = persisted.messages.map { it.toMessage() }

        // 如果 JSON 持久化中消息较少，尝试从 SQLite 记忆数据库补充历史
        if (messages.size < 10) {
            try {
                val sqliteMessages = memoryManager.getBuiltInProvider()
                    .loadSessionHistory(persisted.id, limit = 50)
                if (sqliteMessages.isNotEmpty()) {
                    messages = sqliteMessages
                    logger.info("Restored session ${session.id} with ${sqliteMessages.size} messages from SQLite memory")
                }
            } catch (e: Exception) {
                logger.warn("Failed to load session history from SQLite for ${persisted.id}", e)
            }
        }

        contextManager.addMessages(messages)

        sessions[session.id] = SessionInfo(session, contextManager)
        logger.info("Restored session: ${session.id} with ${messages.size} messages")
        return session
    }

    /**
     * 获取所有会话（按创建时间倒序）
     */
    fun getSessions(): List<AgentSession> {
        return sessions.values.map { it.session }.sortedByDescending { it.createdAt }
    }

    /**
     * 获取当前会话
     */
    fun getCurrentSession(): AgentSession? {
        return currentSessionId?.let { sessions[it]?.session }
    }

    /**
     * 获取当前会话的历史消息
     */
    fun getCurrentHistory(): List<Message> {
        return currentSessionId?.let { sessions[it]?.contextManager?.getContext() } ?: emptyList()
    }

    /**
     * 发送消息并获取回复（非流式，不带工具）
     */
    suspend fun chat(userMessage: String): AgentResult {
        val sessionInfo = getOrCreateSession()
        val session = sessionInfo.session
        val contextManager = sessionInfo.contextManager

        _state.value = AgentState.THINKING
        session.lastActivityAt = System.currentTimeMillis()

        try {
            contextManager.addMessage(Message.userMessage(userMessage))
            maybeAutoName(session, userMessage)

            val request = ChatRequest(
                model = currentModel,
                messages = contextManager.getContext(),
                temperature = 0.7,
                stream = false
            )

            val result = gateway.chat(request)

            return result.fold(
                onSuccess = { response ->
                    val assistantMsg = response.choices.firstOrNull()?.message
                        ?: Message.assistantMessage("")

                    if (!assistantMsg.toolCalls.isNullOrEmpty()) {
                        contextManager.addMessage(assistantMsg)
                        _state.value = AgentState.IDLE
                        AgentResult.ToolCalls(assistantMsg.toolCalls, session)
                    } else {
                        contextManager.addMessage(assistantMsg)
                        _state.value = AgentState.IDLE
                        AgentResult.Success(assistantMsg, session)
                    }
                },
                onFailure = { error ->
                    _state.value = AgentState.ERROR
                    logger.error("Chat failed", error)
                    AgentResult.Failure(error.message ?: "Unknown error", session)
                }
            )
        } catch (e: Exception) {
            _state.value = AgentState.ERROR
            logger.error("Chat exception", e)
            return AgentResult.Failure(e.message ?: "Unknown error", session)
        }
    }

    /**
     * 流式聊天（纯文本，不带工具调用）
     */
    fun chatStream(userMessage: String): Flow<String> {
        val sessionInfo = getOrCreateSession()
        val session = sessionInfo.session
        val contextManager = sessionInfo.contextManager

        _state.value = AgentState.STREAMING
        session.lastActivityAt = System.currentTimeMillis()

        return flow {
            try {
                contextManager.addMessage(Message.userMessage(userMessage))
                maybeAutoName(session, userMessage)

                val request = ChatRequest(
                    model = currentModel,
                    messages = contextManager.getContext(),
                    temperature = 0.7,
                    stream = true
                )

                val fullResponse = StringBuilder()

                gateway.chatStream(request).collect { chunk ->
                    if (!chunk.done) {
                        fullResponse.append(chunk.delta)
                        emit(chunk.delta)
                    }
                }

                val assistantMsg = Message.assistantMessage(fullResponse.toString())
                contextManager.addMessage(assistantMsg)
                _state.value = AgentState.IDLE
            } catch (e: Exception) {
                _state.value = AgentState.ERROR
                logger.error("Stream chat failed", e)
                emit("[ERROR] ${e.message}")
            }
        }
    }

    /**
     * 带工具调用的完整对话闭环（增强版）
     *
     * 已重构为使用 [EnhancedAgentLoop]，具备：
     * - 迭代预算管理
     * - 错误分类与自动恢复
     * - 流式文本输出
     * - Hook 体系支持
     * - 追踪和指标收集
     */
    fun chatWithTools(userMessage: String): Flow<AgentStreamEvent> {
        val sessionInfo = getOrCreateSession()
        val session = sessionInfo.session
        val contextManager = sessionInfo.contextManager
        val traceCtx = tracer.startTrace("chat_with_tools", session.id)

        _state.value = AgentState.THINKING
        session.lastActivityAt = System.currentTimeMillis()

        metrics.incrementCounter("chat_requests")

        // 从配置读取并创建任务级预算（优先使用 AgentConfig 中的配置，否则从 PluginConfig 读取）
        val budgetConfig = currentBudgetConfig ?: PluginConfig.getInstance().let { pluginConfig ->
            TaskBudget.BudgetConfig(
                maxIterations = pluginConfig.maxIterationsPerTask,
                maxTokens = pluginConfig.maxTokensPerTask,
                maxDurationMs = pluginConfig.maxDurationSecondsPerTask * 1000L,
                enableIteration = pluginConfig.enableIterationBudget,
                enableToken = pluginConfig.enableTokenBudget,
                enableTime = pluginConfig.enableTimeBudget,
                warningThresholdPercent = pluginConfig.budgetWarningThreshold
            )
        }
        val taskBudget = TaskBudget(budgetConfig)
        logger.info("[Session ${session.id}] TaskBudget created: ${taskBudget.summary()}")

        // 为每个任务创建独立的 EnhancedAgentLoop（确保预算隔离）
        val loop = EnhancedAgentLoop(
            gateway = gateway,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            skillToolAdapter = skillToolAdapter,
            errorRecovery = errorRecovery,
            hooks = hooks,
            stateFlow = _state,
            memoryManager = memoryManager,
            memoryNudger = memoryNudger,
            subAgentExecutor = subAgentExecutor,
            agentCore = this,
            budget = taskBudget
        )

        traceCtx.event("loop_started", mapOf("model" to currentModel))

        val startTime = System.currentTimeMillis()
        val flow = loop.run(
            userMessage = userMessage,
            session = session,
            contextManager = contextManager,
            currentModel = currentModel,
            systemPrompt = systemPrompt
        )

        return kotlinx.coroutines.flow.flow {
            val job = currentCoroutineContext()[kotlinx.coroutines.Job]
            currentChatJob.set(job)
            var eventCount = 0
            try {
                flow.collect { event ->
                    eventCount++
                    emit(event)
                }
            } finally {
                currentChatJob.compareAndSet(job, null)
                val duration = System.currentTimeMillis() - startTime
                if (eventCount == 0) {
                    logger.warn("[Session ${session.id}] chatWithTools flow completed with ZERO events in ${duration}ms")
                } else {
                    logger.info("[Session ${session.id}] chatWithTools flow completed with $eventCount events in ${duration}ms")
                }
                // 保存会话历史（异步）
                agentScope.launch {
                    conversationPersistence.saveSession(session, contextManager.getContext())
                }

                metrics.recordTimer("chat_duration", duration)
                traceCtx.end()
                structuredLogger.logAgentEvent(
                    event = "chat_complete",
                    sessionId = session.id,
                    traceId = traceCtx.traceId,
                    durationMs = duration,
                    metadata = mapOf("eventCount" to eventCount.toString())
                )
            }
        }
    }

    /**
     * 中断当前对话
     */
    fun interrupt() {
        val job = currentChatJob.getAndSet(null)
        job?.cancel()
        if (::enhancedLoop.isInitialized) {
            enhancedLoop.interrupt()
        }
        _state.value = AgentState.IDLE
        logger.info("Agent conversation interrupted")
    }

    /**
     * 处理工具调用结果（保留用于外部手动调用）
     */
    suspend fun handleToolResult(toolCallId: String, result: String): AgentResult {
        val sessionInfo = currentSessionId?.let { sessions[it] }
            ?: return AgentResult.Failure(
                "No active session",
                AgentSession(id = "invalid")
            )

        val session = sessionInfo.session
        val contextManager = sessionInfo.contextManager
        _state.value = AgentState.THINKING

        try {
            contextManager.addMessage(Message.toolMessage(result, toolCallId))

            val request = ChatRequest(
                model = currentModel,
                messages = contextManager.getContext(),
                tools = toolRegistry.getAllTools(),
                stream = false
            )

            val response = gateway.chat(request)
            return response.fold(
                onSuccess = { chatResponse ->
                    val assistantMsg = chatResponse.choices.firstOrNull()?.message
                        ?: Message.assistantMessage("")
                    contextManager.addMessage(assistantMsg)
                    _state.value = AgentState.IDLE
                    AgentResult.Success(assistantMsg, session)
                },
                onFailure = { error ->
                    _state.value = AgentState.ERROR
                    AgentResult.Failure(error.message ?: "Unknown error", session)
                }
            )
        } catch (e: Exception) {
            _state.value = AgentState.ERROR
            return AgentResult.Failure(e.message ?: "Unknown error", session)
        }
    }

    /**
     * 规划任务
     */
    suspend fun planTask(taskDescription: String): com.codesage.agent.planner.TaskPlan {
        val task = taskPlanner.createTask(taskDescription, taskDescription)
        return taskPlanner.decomposeTask(task, getCurrentHistory())
    }

    /**
     * 执行任务（启用 DAG 并行执行 + 人机协作审批）
     */
    suspend fun executeTask(task: Task): AgentResult {
        val sessionInfo = getOrCreateSession()
        val session = sessionInfo.session
        _state.value = AgentState.EXECUTING

        // 1. 分解为 DAG 计划
        val dagPlan = taskPlanner.decomposeToDagPlan(task, getCurrentHistory())

        // 2. 请求用户审批计划
        val approvalFlow = taskPlanner.requestPlanApproval(dagPlan)
        var approved = false
        var rejectionReason: String? = null
        approvalFlow.collect { event ->
            when (event) {
                is AgentStreamEvent.PlanApproved -> approved = true
                is AgentStreamEvent.PlanRejected -> {
                    rejectionReason = event.reason
                }

                is AgentStreamEvent.PlanModified -> {
                    approved = true
                    // 从修改后的步骤重建计划（简化处理：保持原有结构）
                }

                else -> { /* 忽略其他事件 */
                }
            }
        }

        if (rejectionReason != null) {
            _state.value = AgentState.IDLE
            return AgentResult.Failure("计划已被用户拒绝: $rejectionReason", session)
        }

        if (!approved) {
            _state.value = AgentState.IDLE
            return AgentResult.Failure("计划审批未完成", session)
        }

        // 3. 使用并行执行器执行 DAG 计划
        val result = StringBuilder()
        val stepExecutor = AgentCoreStepExecutor { stepDescription ->
            chatWithTools(stepDescription)
        }

        val executionFlow = taskPlanner.executeDagPlan(dagPlan, stepExecutor)
        executionFlow.collect { event ->
            when (event) {
                is AgentStreamEvent.TextDelta -> result.append(event.delta)
                is AgentStreamEvent.ToolCallStart -> result.appendLine("[调用工具: ${event.toolCall.name}]")
                is AgentStreamEvent.ToolCallDelta -> result.appendLine("[工具进度: ${event.toolName}] ${event.delta}")
                is AgentStreamEvent.ToolCallResult -> result.appendLine("[工具结果: ${event.toolName} ${if (event.success) "成功" else "失败"}]")
                is AgentStreamEvent.ToolCallError -> result.appendLine("[工具错误: ${event.toolCallId}] ${event.error}")
                is AgentStreamEvent.ToolConfirmationNeeded -> result.appendLine("[需要确认: ${event.toolName} - ${event.reason}]")
                is AgentStreamEvent.Error -> result.appendLine("[错误: ${event.message}]")
                is AgentStreamEvent.Thinking -> result.appendLine("[${event.message}]")
                is AgentStreamEvent.SubAgentStart -> result.appendLine("[子Agent启动: ${event.taskDescription}]")
                is AgentStreamEvent.SubAgentProgress -> result.appendLine("[子Agent进度: ${event.message}]")
                is AgentStreamEvent.SubAgentComplete -> result.appendLine("[子Agent完成: ${if (event.success) "成功" else "失败"}]")
                is AgentStreamEvent.BudgetStatus -> result.appendLine("[预算状态: ${event.status}, 剩余${event.remainingIterations}轮]")
                is AgentStreamEvent.BudgetExhausted -> result.appendLine("[预算耗尽: ${event.reason}]")
                is AgentStreamEvent.BudgetExtended -> result.appendLine("[预算已追加: +${event.extraIterations}轮]")
                is AgentStreamEvent.PlanGenerated -> result.appendLine("[计划生成: ${event.description} (${event.steps.size} 步)]")
                is AgentStreamEvent.PlanModified -> result.appendLine("[计划已修改: ${event.planId} (${event.steps.size} 步)]")
                is AgentStreamEvent.PlanApproved -> result.appendLine("[计划已批准: ${event.planId}]")
                is AgentStreamEvent.PlanRejected -> result.appendLine("[计划已拒绝: ${event.planId} - ${event.reason}]")
                is AgentStreamEvent.ContextCompressed -> result.appendLine("[上下文压缩: ${event.originalTokens} → ${event.compressedTokens} tokens]")
                is AgentStreamEvent.SessionMigrated -> result.appendLine("[会话迁移: ${event.oldSessionId} → ${event.newSessionId}]")
                AgentStreamEvent.Done -> {}
            }
        }

        _state.value = AgentState.IDLE
        return AgentResult.Success(
            Message.assistantMessage(result.toString()),
            session
        )
    }

    /**
     * 切换模型
     */
    fun switchModel(model: String) {
        currentModel = model
        logger.info("Switched to model: $model")
    }

    /**
     * 获取当前模型
     */
    fun getCurrentModel(): String = currentModel

    /**
     * 压缩当前会话上下文（用于 CONTEXT_TOO_LONG 错误恢复）
     */
    fun compressContext(): Boolean {
        return currentSessionId?.let { id ->
            sessions[id]?.contextManager?.compressContext()
        } ?: false
    }

    /**
     * Context 压缩后执行 Session 迁移
     * 生成新 session ID，迁移项目信息、用户偏好、重要记忆和历史消息
     */
    fun migrateSessionAfterCompression(): Pair<String, String>? {
        val oldSessionId = currentSessionId ?: return null
        val oldSessionInfo = sessions[oldSessionId] ?: return null
        val oldContext = oldSessionInfo.contextManager.getContext()

        // 生成新 session
        val newSession = AgentSession(
            id = "session_${System.currentTimeMillis()}",
            name = oldSessionInfo.session.name,
            createdAt = System.currentTimeMillis()
        )
        val newContextManager = ContextManager(memoryProvider = memoryManager.getBuiltInProvider())
        // 迁移系统提示 + 压缩后的历史消息
        newContextManager.newSession(
            systemMessages = oldContext.filter { it.role == Role.SYSTEM },
            newSessionId = newSession.id
        )
        // 添加非系统消息（已压缩的上下文）
        val nonSystemMessages = oldContext.filter { it.role != Role.SYSTEM }
        newContextManager.addMessages(nonSystemMessages)

        sessions[newSession.id] = SessionInfo(newSession, newContextManager)
        currentSessionId = newSession.id

        // 初始化新 session 的记忆系统
        val homeDir = File(System.getProperty("user.home"), ".codesage").absolutePath
        memoryManager.initializeAll(newSession.id, homeDir)

        logger.info("Session migrated: $oldSessionId -> ${newSession.id}, messages=${nonSystemMessages.size}")

        // 保存旧会话
        agentScope.launch {
            conversationPersistence.saveSession(oldSessionInfo.session, oldSessionInfo.contextManager.getContext())
        }

        return Pair(oldSessionId, newSession.id)
    }

    /**
     * 清空当前会话
     */
    fun clearSession() {
        currentSessionId?.let { id ->
            sessions[id]?.contextManager?.newSession(listOf(Message.systemMessage(systemPrompt)), newSessionId = id)
            sessions[id]?.session?.lastActivityAt = System.currentTimeMillis()
            // 保存清空后的状态
            sessions[id]?.let { info ->
                conversationPersistence.saveSession(info.session, info.contextManager.getContext())
            }
        }
    }

    // ===== MCP 集成 =====

    fun getMCPServerManager(): MCPServerManager = mcpServerManager

    // ===== 可观测性接口 =====

    fun getMetrics(): MetricsCollector = metrics

    fun getTracer(): ExecutionTracer = tracer

    // ===== 对话持久化接口 =====

    fun getConversationPersistence(): ConversationPersistence = conversationPersistence

    fun restoreSessions(options: SessionRestore.RestoreOptions = SessionRestore.RestoreOptions()): SessionRestore.RestoreResult {
        if (!::sessionRestore.isInitialized) {
            sessionRestore = SessionRestore(conversationPersistence, this)
        }
        val result = sessionRestore.restore(options)
        // 将最近恢复的会话设为当前会话
        if (result.restoredSessions.isNotEmpty()) {
            currentSessionId = result.restoredSessions.maxByOrNull { it.lastActivityAt }?.id
        }
        return result
    }

    fun exportSession(
        sessionId: String,
        format: com.codesage.persistence.ConversationExporter.ExportFormat,
        outputFile: java.io.File
    ): Boolean {
        val session = conversationPersistence.loadSession(sessionId) ?: return false
        val exporter = com.codesage.persistence.ConversationExporter()
        return exporter.export(session, format, outputFile)
    }

    private fun maybeAutoName(session: AgentSession, userMessage: String) {
        if (session.name.isBlank()) {
            session.name = userMessage.trim().take(30).let {
                if (userMessage.trim().length > 30) "$it..." else it
            }
        }
    }

    private fun getOrCreateSession(): SessionInfo {
        val id = currentSessionId
        if (id != null) {
            val existing = sessions[id]
            if (existing != null) {
                return existing
            }
        }
        return createSession().let { sessions[it.id]!! }
    }
}

/**
 * Agent配置
 */
data class AgentConfig(
    val defaultModel: String = "MiniMax-Text-01",
    val systemPrompt: String = AgentConfig.DEFAULT_SYSTEM_PROMPT,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    /** 任务级预算配置 */
    val budgetConfig: TaskBudget.BudgetConfig = TaskBudget.BudgetConfig()
) {
    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            You are CodeSage, an AI coding assistant for IntelliJ IDEA.
            You help developers with:
            - Writing and refactoring code
            - Debugging and fixing issues
            - Code review and optimization
            - Project analysis and documentation
            - Executing development tasks

            You have access to the following tools to interact with the user's project:
            - read_file: Read file contents
            - write_file: Write or modify files
            - list_directory: List files in a directory
            - search_code: Search for code patterns
            - run_command: Execute terminal commands
            - get_project_structure: Get project overview

            When asked to modify code, prefer using write_file with the complete new content.
            When exploring a project, use list_directory and read_file to understand the structure.
            Always provide clear, concise, and actionable responses.
        """.trimIndent()
    }
}
