package com.codesage.agent.core

import com.codesage.agent.context.ContextBudgetManager
import com.codesage.agent.context.ContextManager
import com.codesage.agent.memory.MemoryManager
import com.codesage.agent.memory.MemoryNudger
import com.codesage.observability.OpenTelemetryExporter
import com.codesage.agent.planner.AgentCoreStepExecutor
import com.codesage.agent.planner.DagTaskPlan
import com.codesage.agent.planner.Task
import com.codesage.agent.planner.TaskPlanner
import com.codesage.agent.tools.SkillToolAdapter
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolProvider
import com.codesage.agent.tools.ToolRegistry
import com.codesage.agent.tools.handlers.ContextToolHandlers
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import java.io.File
import java.nio.charset.StandardCharsets
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
        // T0.7 修复：使用 java.time.DateTimeFormatter (线程安全) 替代 SimpleDateFormat (非线程安全)
        // 原实现会出现在 getSessions() / getCurrentSession() 被多线程调用时输出错乱的风险
        return SESSION_DISPLAY_FORMATTER.format(
            java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault())
        )
    }

    companion object {
        // T0.7 修复：使用线程安全的 DateTimeFormatter 作为共享实例
        // DateTimeFormatter 是 immutable + thread-safe，可以作为 companion object 常量复用
        private val SESSION_DISPLAY_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm", java.util.Locale.getDefault())
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
open class AgentCore(
    private val gateway: ModelGateway = ModelGateway.getInstance(),
    private val taskPlanner: TaskPlanner = TaskPlanner(),
    private val project: Project? = null,
    skillToolAdapter: SkillToolAdapter? = null,
    confirmationCallback: ToolGuardrails.ConfirmationCallback? = null,
    /**
     * 可选的外部注入工具注册表。
     * 主要用于子 Agent：由父 Agent 的 [SubAgentExecutor] 根据 toolset
     * 过滤后传入，避免子 Agent 拿到完整工具集。
     * 为 null 时使用 [ToolRegistry.createDefault] 创建默认注册表。
     */
    toolRegistryOverride: ToolRegistry? = null,
    /**
     * 可选的 MCP 服务器管理器注入。
     * 用于注册 `mcp_tool_search` 动态发现工具；为 null 时使用默认空管理器。
     */
    mcpServerManagerOverride: MCPServerManager? = null,
    /**
     * 子 Agent 递归深度。
     * 0 = 顶层 Agent；>0 表示这是第 N 层子 Agent。
     * 用于防止 [delegate_task] 无限递归。
     */
    private val subAgentDepth: Int = 0,
    /**
     * 可选的持久化实例注入。
     * - 为 null 时使用默认 [ConversationPersistence]（生产默认行为，读写 ~/.codesage/conversations/）
     * - 子 Agent 场景：传入独立 tmp 目录的持久化，确保不污染父 Agent 的磁盘
     * - 测试场景：传入 @TempDir 持久化，验证父子隔离
     */
    conversationPersistenceOverride: ConversationPersistence? = null
) {
    private val logger = Logger.getLogger<AgentCore>()

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private data class SessionInfo(
        val session: AgentSession,
        val contextManager: ContextManager
    )

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, SessionInfo>()

    /**
     * 当前会话 ID。
     *
     * 使用 [java.util.concurrent.atomic.AtomicReference] 而非 `@Volatile var` 的原因：
     * 1. 读-改-写操作（如 [getOrCreateSession] 的"先读 currentId，再决定是否创建"）需要复合原子性
     * 2. 多个 chat/chatStream/chatWithTools 调用可能在不同线程并发触发（EDT、协程派发器、IDEA readAction）
     * 3. AtomicReference 的 `get()/set()/compareAndSet()` 提供比 @Volatile 写入更强的 happens-before 保证
     *
     * 注意：sessions map 本身是 ConcurrentHashMap，两者配合使用保证：
     * - map 内的某个 session 一定对应 currentSessionId 的某个有效值
     * - currentSessionId 一定指向 map 内真实存在的 session（除非刚被删除处于极短竞态窗口）
     */
    private val currentSessionId = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private var currentModel: String = ""

    /**
     * 解析最终使用的模型名。
     * 优先级：传入的 config > PluginConfig 持久化配置 > 空字符串（由上层检查）
     */
    private fun resolveDefaultModel(config: AgentConfig): String {
        if (config.defaultModel.isNotBlank()) {
            return config.defaultModel
        }
        return try {
            val pluginDefault = PluginConfig.getInstance().defaultModel
            if (pluginDefault.isNotBlank()) pluginDefault else ""
        } catch (e: Exception) {
            // PluginConfig 在测试环境或非 IDE 环境中可能不可用
            ""
        }
    }

    private var systemPrompt: String = AgentConfig.DEFAULT_SYSTEM_PROMPT

    // 可观测性（T7.2：提前初始化供 ToolExecutor 使用）
    private val tracer: ExecutionTracer = ExecutionTracer()

    // 6.13.2：OpenTelemetry 导出器
    private val openTelemetryExporter: OpenTelemetryExporter = OpenTelemetryExporter(
        settingsProvider = {
            try {
                com.codesage.shared.config.SettingsRepository.getInstance().get()
            } catch (e: Exception) {
                // 测试/无 IDE 环境：返回默认空配置
                com.codesage.shared.config.SettingsFile()
            }
        }
    )

    // Phase 5: 上下文预算管理器（跨会话共享实例，通过 provider 绑定当前会话）
    private val contextBudgetManager: ContextBudgetManager = ContextBudgetManager()

    // 工具系统
    // 注意：override 优先于 createDefault()，供子 Agent 按 toolset 过滤使用。
    // initialize() 仍会向其注册 memory tools / skills / 插件贡献的 tools。
    private val toolRegistry: ToolRegistry = toolRegistryOverride
        ?: ToolRegistry.createDefault(
            project,
            mcpServerManager = mcpServerManagerOverride,
            skillRegistry = skillToolAdapter?.skillRegistry,
            skillExecutor = skillToolAdapter?.skillExecutor
        )
    private val guardrails: ToolGuardrails? = project?.let {
        ToolGuardrails(
            projectRoot = it.basePath,
            confirmationCallback = confirmationCallback,
            contextBudgetManager = contextBudgetManager
        )
    }
    private val toolExecutor: ToolExecutor = ToolExecutor(
        project = project,
        guardrails = guardrails,
        toolRegistry = toolRegistry,
        tracer = tracer,  // T7.2：传入 tracer 以追踪 tool 调用
        contextBudgetManager = contextBudgetManager  // 6.12.2：嵌入 token 预算提示
    )
    private val skillToolAdapter: SkillToolAdapter? = skillToolAdapter

    // 错误恢复
    private val errorRecovery: AgentErrorRecovery = AgentErrorRecovery()

    // 当前正在运行的增强型对话循环（用于中断）
    private val currentLoop = java.util.concurrent.atomic.AtomicReference<EnhancedAgentLoop?>(null)

    // 记忆系统
    private val memoryManager: MemoryManager = MemoryManager()
    private val memoryNudger: MemoryNudger = MemoryNudger()

    // 子 Agent 执行器
    // 深度从构造参数传入，用于在 SubAgentExecutor 内做递归限制检查
    private val subAgentExecutor: SubAgentExecutor = SubAgentExecutor(
        parentAgent = this,
        gateway = gateway,
        project = project,
        skillToolAdapter = skillToolAdapter,
        depth = subAgentDepth
    )

    // Prompt 工程
    private val promptAssembler: PromptAssembler = PromptAssembler(toolRegistry = toolRegistry)

    // MCP 生态
    private val mcpServerManager: MCPServerManager = mcpServerManagerOverride ?: MCPServerManager()

    // 可观测性
    private val structuredLogger: StructuredLogger = StructuredLogger()
    private val metrics: MetricsCollector = MetricsCollector()

    // 性能优化
    private val responseCache: ResponseCache = ResponseCache()
    private val promptCache: SystemPromptCache = SystemPromptCache()

    // 对话持久化
    private val conversationPersistence: ConversationPersistence =
        conversationPersistenceOverride ?: ConversationPersistence()
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
    /**
     * 初始化 AgentCore。
     *
     * @param config Agent 配置
     * @param skipRestore 跳过从磁盘恢复历史会话。子 Agent 场景用：父 Agent 持久化的会话
     *   包含父的 tool_call_id，子 Agent 看到会触发 LLM API 400 (2013) 错误。
     *   详见 SubAgentExecutor.spawn()。
     * @param skipAutoSave 跳过自动保存订阅。子 Agent 场景用：避免子 Agent 的临时 session
     *   写回父 Agent 的磁盘目录。
     */
    fun initialize(
        config: AgentConfig,
        skipRestore: Boolean = false,
        skipAutoSave: Boolean = false
    ) {
        currentModel = resolveDefaultModel(config)
        if (currentModel.isBlank()) {
            logger.warn("AgentCore initialized without a valid default model. Please configure a model in CodeSage settings.")
        }

        // 检测项目语言和框架
        val (projectLanguage, projectFramework, projectRoot) = detectProjectContext()

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
                    toolCount = toolRegistry.getAllTools().size,
                    projectLanguage = projectLanguage,
                    projectFramework = projectFramework,
                    projectRoot = projectRoot
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

        // 加载外部插件贡献的 ToolHandler
        var externalToolCount = 0
        try {
            val toolProviders = ToolProvider.EP_NAME.extensionList
            toolProviders.forEach { provider ->
                try {
                    val handlers = provider.getToolHandlers()
                    handlers.forEach { toolRegistry.register(it) }
                    externalToolCount += handlers.size
                    logger.info("Loaded ${handlers.size} tools from provider: ${provider.providerName}")
                } catch (e: Exception) {
                    logger.error("Failed to load tools from provider: ${provider.providerName}", e)
                }
            }
            if (externalToolCount > 0) {
                logger.info("Total external tools loaded: $externalToolCount")
            }
        } catch (e: IllegalArgumentException) {
            // 测试环境中扩展点不可用，安全跳过
            logger.debug("ToolProvider extension point not available (test environment), skipping external tools")
        }

        // Phase 5: 注册上下文预算自管理工具，并将会话 provider 绑定到预算管理器
        contextBudgetManager.setContextManagerProvider { getCurrentContextManager() }
        toolRegistry.register(ContextToolHandlers.createGetContextRemainingHandler(contextBudgetManager))
        logger.info("Registered get_context_remaining tool")

        // 初始化对话持久化
        sessionRestore = SessionRestore(conversationPersistence, this)

        // 尝试恢复之前的会话（子 Agent 场景跳过：避免父 Agent 的 tool_call_id 污染）
        if (skipRestore) {
            logger.info("[AgentCore] Skipping session restore (sub-agent lightweight init)")
        } else {
            restoreSessions(SessionRestore.RestoreOptions(strategy = SessionRestore.RestoreStrategy.RESTORE_ALL))
        }

        // 6.13.2：注册 OpenTelemetry 导出监听器
        tracer.addListener(openTelemetryExporter)
        logger.info("OpenTelemetry exporter registered")

        // 如果没有会话，自动创建一个
        if (sessions.isEmpty()) {
            createSession()
        }

        // 启动自动保存（子 Agent 场景跳过：避免临时 session 写回父 Agent 的磁盘）
        if (skipAutoSave) {
            logger.info("[AgentCore] Skipping auto-save subscription (sub-agent lightweight init)")
        } else {
            sessionRestore.startAutoSave(agentScope)
        }

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
     * 根据 ChatMode 解析实际使用的模型。
     * 优先级：mode 专用配置 > 当前手动切换的模型 > PluginConfig 默认模型
     */
    fun resolveModelForMode(mode: ChatMode): String {
        if (mode == ChatMode.GENERAL) {
            return currentModel.ifBlank {
                try {
                    PluginConfig.getInstance().defaultModel
                } catch (_: Exception) {
                    ""
                }
            }
        }
        return try {
            val config = PluginConfig.getInstance()
            when (mode) {
                ChatMode.CODING -> config.codingModel
                ChatMode.REASONING -> config.reasoningModel
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }.ifBlank { currentModel }
    }

    /**
     * 根据消息内容自动推断 ChatMode。
     *
     * T1.5 修复：保留方法用于向后兼容，但现在仅作为**建议**。
     * 真正的路由逻辑在 [ChatModeRouter] 中，且 `chat/chatStream/chatWithTools` 已支持
     * `userExplicit: ChatMode?` 形参。`detectChatMode` 的实现也委托给 router。
     *
     * @deprecated 请改用 [ChatModeRouter.suggestChatMode]
     */
    @Deprecated(
        message = "Use ChatModeRouter.suggestChatMode() or ChatModeRouter.resolve() — " +
                "detectChatMode is now only a suggestion, not a forced decision.",
        replaceWith = ReplaceWith("ChatModeRouter.suggestChatMode(message)", "com.codesage.agent.core.ChatModeRouter")
    )
    fun detectChatMode(message: String): ChatMode = ChatModeRouter.suggestChatMode(message)

    /**
     * 配置钩子（用于扩展和自定义行为）
     */
    fun setHooks(newHooks: AgentHooks) {
        hooks = newHooks
        // 注意：不再重新创建成员变量 enhancedLoop。
        // 新的 hooks 会在下一次 chatWithTools 创建新 EnhancedAgentLoop 时自动应用。
    }

    /**
     * 创建新会话
     *
     * 线程安全：使用 [createAndRegisterSession] 内部的 atomic 注册保证并发调用产生不同的 session。
     * 调用方仍可通过返回值拿到会话引用。
     */
    fun createSession(): AgentSession {
        return createAndRegisterSession().session
    }

    /**
     * 原子地创建并注册一个新会话。
     *
     * 关键不变量：
     * 1. 每个 session id 只会创建一次 [SessionInfo]（使用 [ConcurrentHashMap.put] 原子检测）
     * 2. [currentSessionId] 只会指向 [sessions] 中真实存在的 key
     * 3. 即使多个线程同时调用，也只会产生一个 "胜利者" 真正完成 [memoryManager.initializeAll] 等副作用
     *
     * session id 加入随机后缀，避免 `System.currentTimeMillis()` 在同一毫秒内被两个并发调用撞 id。
     */
    private fun createAndRegisterSession(): SessionInfo {
        val traceCtx = tracer.startTrace("create_session")
        val session = AgentSession(
            id = "session_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}",
            name = "",
            createdAt = System.currentTimeMillis()
        )
        val contextManager = ContextManager(memoryProvider = memoryManager.getBuiltInProvider())
        contextManager.newSession(listOf(Message.systemMessage(systemPrompt)), newSessionId = session.id)

        // 原子注册：如果同 id 已存在（理论上极低概率，但随机后缀使得碰撞几乎不可能），
        // 则放弃我们刚创建的 SessionInfo，复用 map 中已有的。
        val candidate = SessionInfo(session, contextManager)
        val winner = sessions.putIfAbsent(session.id, candidate)
        if (winner != null) {
            logger.warn("Session id collision detected for ${session.id}, reusing existing session")
            traceCtx.end()
            return winner
        }

        // 我们是第一个注册此 id 的线程，执行完整副作用
        currentSessionId.set(session.id)

        val homeDir = File(System.getProperty("user.home"), ".codesage").absolutePath
        memoryManager.initializeAll(session.id, homeDir)
        memoryNudger.reset()

        metrics.incrementCounter("sessions_created")
        traceCtx.end()
        return candidate
    }

    /**
     * 切换当前会话
     */
    fun switchSession(sessionId: String): Boolean {
        if (sessions.containsKey(sessionId)) {
            currentSessionId.set(sessionId)
            memoryManager.onSessionSwitch(sessionId)
            return true
        }
        return false
    }

    /**
     * 删除会话
     *
     * 线程安全：使用 [AtomicReference.compareAndSet] 避免"删除后被另一线程恢复"的竞态。
     * 即：仅当我们仍是 currentSessionId 的持有者时，才把 currentSessionId 切到下一个可用会话。
     */
    fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        // CAS 循环：仅当我们仍指向被删除的 session 时，才切换到下一个
        while (currentSessionId.compareAndSet(sessionId, sessions.keys.firstOrNull())) {
            // 成功：要么切到了新 session，要么切到了 null（map 为空）
            return
        }
        // 另一线程已经先一步切换了 currentSessionId，无需操作
    }

    /**
     * 关闭AgentCore，释放资源
     */
    fun shutdown() {
        agentScope.cancel()
        openTelemetryExporter.shutdown()
        sessions.clear()
        currentSessionId.set(null)
        currentLoop.getAndSet(null)?.interrupt()
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
        val sessionInfo = currentSessionId.get()?.let { sessions[it] } ?: return
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
     * O5.2: 获取所有持久化会话(含 previewText 等元数据),供 SessionPopover 展示。
     * 与 [getSessions] 不同:返回的是从磁盘加载的 [PersistedSession],包含消息历史与预览文本。
     */
    fun getAllPersistedSessions(): List<com.codesage.persistence.PersistedSession> {
        return conversationPersistence.loadAllSessions()
            .sortedByDescending { it.lastActivityAt }
    }

    /**
     * 获取当前会话
     */
    fun getCurrentSession(): AgentSession? {
        return currentSessionId.get()?.let { sessions[it]?.session }
    }

    /**
     * 获取当前会话的 ContextManager（供 ContextBudgetManager 动态读取 token 使用）
     */
    internal fun getCurrentContextManager(): ContextManager? {
        return currentSessionId.get()?.let { sessions[it]?.contextManager }
    }

    /**
     * 获取当前会话的历史消息
     */
    fun getCurrentHistory(): List<Message> {
        return currentSessionId.get()?.let { sessions[it]?.contextManager?.getContext() } ?: emptyList()
    }

    /**
     * 发送消息并获取回复（非流式，不带工具）
     *
     * T1.5 修复：增加 `userExplicit: ChatMode?` 形参。
     * - `null` = 用户未显式选择 → 后端用 `ChatModeRouter.suggestChatMode` 推断
     * - 非空 = 严格使用用户值
     *
     * 老调用方仍可只传 `mode: ChatMode = ChatMode.GENERAL`（默认不强制，等价于"用户选了 GENERAL"）。
     */
    suspend fun chat(
        userMessage: String,
        mode: ChatMode = ChatMode.GENERAL,
        userExplicit: Boolean = true
    ): AgentResult {
        val sessionInfo = getOrCreateSession()
        val session = sessionInfo.session
        val contextManager = sessionInfo.contextManager

        _state.value = AgentState.THINKING
        session.lastActivityAt = System.currentTimeMillis()

        try {
            contextManager.addMessage(Message.userMessage(userMessage))
            maybeAutoName(session, userMessage)

            val routing = ChatModeRouter.resolve(
                userExplicit = if (userExplicit) mode else null,
                message = userMessage
            )

            val request = ChatRequest(
                model = resolveModelForMode(routing.effective),
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
     *
     * T1.5 修复：增加 `userExplicit: Boolean` 形参。语义与 [chat] 一致。
     */
    fun chatStream(
        userMessage: String,
        mode: ChatMode = ChatMode.GENERAL,
        userExplicit: Boolean = true
    ): Flow<String> {
        val sessionInfo = getOrCreateSession()
        val session = sessionInfo.session
        val contextManager = sessionInfo.contextManager

        _state.value = AgentState.STREAMING
        session.lastActivityAt = System.currentTimeMillis()

        return flow {
            try {
                contextManager.addMessage(Message.userMessage(userMessage))
                maybeAutoName(session, userMessage)

                val routing = ChatModeRouter.resolve(
                    userExplicit = if (userExplicit) mode else null,
                    message = userMessage
                )

                val request = ChatRequest(
                    model = resolveModelForMode(routing.effective),
                    messages = contextManager.getContext(),
                    temperature = 0.7,
                    stream = true
                )

                val fullResponse = StringBuilder()

                gateway.chatStream(request).collect { event ->
                    if (event is com.codesage.model.adapter.StreamEvent.Content.Text) {
                        fullResponse.append(event.delta)
                        emit(event.delta)
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
     * - ChatMode 模型路由
     *
     * T1.5 修复：增加 `userExplicit: Boolean` 形参。语义与 [chat] / [chatStream] 一致。
     * 当 `userExplicit=false` 时，额外 emit 一个 `AgentStreamEvent.ModeSuggestion` 事件供 UI 展示。
     */
    fun chatWithTools(
        userMessage: String,
        mode: ChatMode = ChatMode.GENERAL,
        userExplicit: Boolean = true
    ): Flow<AgentStreamEvent> {
        val sessionInfo = getOrCreateSession()
        val session = sessionInfo.session
        val contextManager = sessionInfo.contextManager
        val traceCtx = tracer.startTrace("chat_with_tools", session.id)

        _state.value = AgentState.THINKING
        session.lastActivityAt = System.currentTimeMillis()

        metrics.incrementCounter("chat_requests")

        // T1.5 修复：用 router 决策
        val routing = ChatModeRouter.resolve(
            userExplicit = if (userExplicit) mode else null,
            message = userMessage
        )

        // Budget system removed (fixup commit)
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
            contextBudgetManager = contextBudgetManager,
        )

        val effectiveModel = resolveModelForMode(routing.effective)
        traceCtx.event("loop_started", mapOf("model" to effectiveModel, "mode" to routing.effective.name))

        val startTime = System.currentTimeMillis()
        val flow = loop.run(
            userMessage = userMessage,
            session = session,
            contextManager = contextManager,
            currentModel = effectiveModel,
            systemPrompt = systemPrompt
        )

        return kotlinx.coroutines.flow.flow {
            val job = currentCoroutineContext()[kotlinx.coroutines.Job]
            currentChatJob.set(job)
            currentLoop.set(loop)
            var eventCount = 0
            try {
                // T1.5 修复：当用户未显式选择 mode 时，先 emit 一个 ModeSuggestion 事件
                // 让 UI 知道后端对 mode 做了什么决策，提升透明性。
                if (!routing.userExplicit) {
                    emit(
                        AgentStreamEvent.ModeSuggestion(
                            effective = routing.effective,
                            suggestion = routing.suggestion,
                            userExplicit = false
                        )
                    )
                }
                flow.collect { event ->
                    eventCount++
                    emit(event)
                }
            } finally {
                currentChatJob.compareAndSet(job, null)
                currentLoop.compareAndSet(loop, null)
                val duration = System.currentTimeMillis() - startTime
                if (eventCount == 0) {
                    logger.warn("[Session ${session.id}] chatWithTools flow completed with ZERO events in ${duration}ms")
                } else {
                    logger.info("[Session ${session.id}] chatWithTools flow completed with $eventCount events in ${duration}ms")
                }
                // M9 修复：保存会话失败时打 error 日志，不要静默吞所有异常
                agentScope.launch {
                    try {
                        conversationPersistence.saveSession(session, contextManager.getContext())
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e // 取消异常需要传播
                    } catch (e: Exception) {
                        logger.error("[AgentCore] Failed to save session ${session.id} asynchronously", e)
                    }
                }

                metrics.recordTimer("chat_duration", duration)
                traceCtx.end()
                structuredLogger.logAgentEvent(
                    event = "chat_complete",
                    sessionId = session.id,
                    traceId = traceCtx.traceId,
                    durationMs = duration,
                    metadata = mapOf(
                        "eventCount" to eventCount.toString(),
                        "interrupted" to loop.isInterrupted().toString()
                    )
                )
            }
        }
    }

    fun interrupt() {
        // 顺序：先关 in-flight HTTP（让阻塞 IO 立刻抛）→ 再 cancel 协程 → 再设 loop 标志
        // 顺序很重要：必须先关 socket，否则阻塞 IO 等到 coroutine cancel 不会响应
        gateway.cancelCurrentRequest()
        val job = currentChatJob.getAndSet(null)
        job?.cancel()
        currentLoop.getAndSet(null)?.interrupt()
        _state.value = AgentState.IDLE
        logger.info("Agent conversation interrupted (gateway + job + loop)")
    }

    /**
     * 处理工具调用结果（保留用于外部手动调用）
     */
    suspend fun handleToolResult(toolCallId: String, result: String): AgentResult {
        val sessionInfo = currentSessionId.get()?.let { sessions[it] }
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
                is AgentStreamEvent.CommandOutputStream -> result.appendLine("[命令输出: stdout=${event.stdout.length} stderr=${event.stderr.length} done=${event.done}]")
                is AgentStreamEvent.ToolCallResult -> result.appendLine("[工具结果: ${event.toolName} ${if (event.success) "成功" else "失败"}]")
                is AgentStreamEvent.ToolCallError -> result.appendLine("[工具错误: ${event.toolCallId}] ${event.error}")
                is AgentStreamEvent.ToolConfirmationNeeded -> result.appendLine("[需要确认: ${event.toolName} - ${event.reason}]")
                is AgentStreamEvent.Error -> result.appendLine("[错误: ${event.message}]")
                is AgentStreamEvent.Thinking -> result.appendLine("[${event.message}]")
                is AgentStreamEvent.ModelReasoning -> result.appendLine("[推理: ${event.delta}]")
                is AgentStreamEvent.SubAgentStart -> result.appendLine("[子Agent启动: ${event.taskDescription}]")
                is AgentStreamEvent.SubAgentProgress -> result.appendLine("[子Agent进度: ${event.message}]")
                is AgentStreamEvent.SubAgentComplete -> result.appendLine("[子Agent完成: ${if (event.success) "成功" else "失败"}]")
                is AgentStreamEvent.PlanGenerated -> result.appendLine("[计划生成: ${event.description} (${event.steps.size} 步)]")
                is AgentStreamEvent.PlanModified -> result.appendLine("[计划已修改: ${event.planId} (${event.steps.size} 步)]")
                is AgentStreamEvent.PlanApproved -> result.appendLine("[计划已批准: ${event.planId}]")
                is AgentStreamEvent.PlanRejected -> result.appendLine("[计划已拒绝: ${event.planId} - ${event.reason}]")
                is AgentStreamEvent.ContextCompressed -> result.appendLine("[上下文压缩: ${event.originalTokens} → ${event.compressedTokens} tokens]")
                is AgentStreamEvent.SessionMigrated -> result.appendLine("[会话迁移: ${event.oldSessionId} → ${event.newSessionId}]")
                is AgentStreamEvent.ModeSuggestion -> result.appendLine("[ChatMode建议: ${event.effective} (userExplicit=${event.userExplicit})]")
                is AgentStreamEvent.ModelReasoningRoundStart -> {
                    // O5.1: 多轮推理起点,执行流日志忽略
                }
                is AgentStreamEvent.ModelReasoningRoundEnd -> {
                    // O5.1: 多轮推理终点,执行流日志忽略
                }
                // 2026-06: CodeBlock 事件 - 执行流日志忽略(只关心文本和工具结果)
                is AgentStreamEvent.CodeBlockStart -> {}
                is AgentStreamEvent.CodeBlockDelta -> {}
                is AgentStreamEvent.CodeBlockEnd -> {}
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
     * 获取当前 system prompt。
     *
     * 子 Agent 用此作为自己 prompt 的基础（再叠加 sub-agent 专用 section），
     * 让子 Agent 继承父 Agent 的项目上下文、角色、工具说明等。
     */
    fun getSystemPrompt(): String = systemPrompt

    /**
     * 获取当前工具注册表中已注册的工具数量。
     *
     * 主要用于测试验证 [toolRegistryOverride] / memory tools / 插件 tools
     * 是否被正确注册。
     */
    fun getToolRegistrySizeForTest(): Int = toolRegistry.getAllTools().size

    /**
     * 获取当前工具注册表中所有已注册工具的名字列表。
     *
     * 主要用于测试验证；调试场景也可使用。
     */
    fun getToolNamesForTest(): List<String> = toolRegistry.getAllTools().map { it.name }

    /**
     * 压缩当前会话上下文（用于 CONTEXT_TOO_LONG 错误恢复）
     */
    fun compressContext(): Boolean {
        return currentSessionId.get()?.let { id ->
            sessions[id]?.contextManager?.compressContext()
        } ?: false
    }

    /**
     * Context 压缩后执行 Session 迁移
     * 生成新 session ID，迁移项目信息、用户偏好、重要记忆和历史消息
     */
    fun migrateSessionAfterCompression(): Pair<String, String>? {
        val oldSessionId = currentSessionId.get() ?: return null
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
        currentSessionId.set(newSession.id)

        // 初始化新 session 的记忆系统
        val homeDir = File(System.getProperty("user.home"), ".codesage").absolutePath
        memoryManager.initializeAll(newSession.id, homeDir)

        logger.info("Session migrated: $oldSessionId -> ${newSession.id}, messages=${nonSystemMessages.size}")

        // M9 修复：保存旧会话失败时打 error 日志
        agentScope.launch {
            try {
                conversationPersistence.saveSession(oldSessionInfo.session, oldSessionInfo.contextManager.getContext())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("[AgentCore] Failed to save old session $oldSessionId during migration", e)
            }
        }

        return Pair(oldSessionId, newSession.id)
    }

    /**
     * 清空当前会话
     */
    fun clearSession() {
        currentSessionId.get()?.let { id ->
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
            currentSessionId.set(result.restoredSessions.maxByOrNull { it.lastActivityAt }?.id)
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

    /**
     * 检测项目上下文信息（语言、框架、根目录）
     */
    private fun detectProjectContext(): Triple<String?, String?, String?> {
        val proj = project ?: return Triple(null, null, null)
        return ApplicationManager.getApplication().runReadAction(Computable {
            var language: String? = null
            var framework: String? = null
            var root: String? = proj.guessProjectDir()?.path

            val baseDir = proj.guessProjectDir()
            if (baseDir != null) {
                // 通过构建文件检测语言和框架
                when {
                    baseDir.findChild("build.gradle.kts") != null || baseDir.findChild("build.gradle") != null -> {
                        language = "Kotlin/Java"
                        framework = "Gradle"
                    }

                    baseDir.findChild("pom.xml") != null -> {
                        language = "Java"
                        framework = "Maven"
                    }

                    baseDir.findChild("package.json") != null -> {
                        language = "JavaScript/TypeScript"
                        val packageJson = baseDir.findChild("package.json")
                        if (packageJson != null) {
                            try {
                                val content = String(packageJson.contentsToByteArray(), StandardCharsets.UTF_8)
                                framework = when {
                                    content.contains("\"react\"") -> "React"
                                    content.contains("\"vue\"") -> "Vue"
                                    content.contains("\"angular\"") -> "Angular"
                                    content.contains("\"next\"") -> "Next.js"
                                    else -> "Node.js"
                                }
                            } catch (_: Exception) {
                                framework = "Node.js"
                            }
                        }
                    }

                    baseDir.findChild("Cargo.toml") != null -> {
                        language = "Rust"
                        framework = "Cargo"
                    }

                    baseDir.findChild("go.mod") != null -> {
                        language = "Go"
                    }

                    baseDir.findChild("requirements.txt") != null || baseDir.findChild("pyproject.toml") != null -> {
                        language = "Python"
                    }

                    baseDir.findChild("composer.json") != null -> {
                        language = "PHP"
                    }
                }

                // 通过源码根目录进一步确认语言
                if (language == null) {
                    val sourceRoots = ProjectRootManager.getInstance(proj).contentSourceRoots
                    val extensions = sourceRoots.flatMap { root ->
                        root.children?.map { it.extension } ?: emptyList()
                    }.filterNotNull().groupingBy { it }.eachCount()

                    language = when {
                        extensions["kt"] != null || extensions["kts"] != null -> {
                            if (extensions["java"] != null) "Kotlin/Java" else "Kotlin"
                        }

                        extensions["java"] != null -> "Java"
                        extensions["py"] != null -> "Python"
                        extensions["ts"] != null || extensions["tsx"] != null -> "TypeScript"
                        extensions["js"] != null || extensions["jsx"] != null -> "JavaScript"
                        extensions["go"] != null -> "Go"
                        extensions["rs"] != null -> "Rust"
                        extensions["swift"] != null -> "Swift"
                        else -> null
                    }
                }
            }

            Triple(language, framework, root)
        })
    }

    private fun maybeAutoName(session: AgentSession, userMessage: String) {
        if (session.name.isBlank()) {
            session.name = userMessage.trim().take(30).let {
                if (userMessage.trim().length > 30) "$it..." else it
            }
        }
    }

    /**
     * 获取或创建当前会话信息。
     *
     * 线程安全说明（修复 CodeReview #1）：
     * - 旧实现存在竞态：两个线程同时进入此方法时，可能都读到 currentSessionId == null，
     *   各自调用 createSession()，导致创建出两个 session 且后一个覆盖前一个。
     * - 新实现依赖 [createAndRegisterSession] 内部的 `putIfAbsent` 原子注册保证
     *   "同 id 只创建一个 SessionInfo"，并通过 currentSessionId 原子读取避免双重创建。
     *
     * 不变量：返回的 [SessionInfo] 一定是 [sessions] map 中实际存在的条目，
     * 且若 [currentSessionId] 指向有效 id，则下次调用优先返回该 id 对应的 SessionInfo。
     */
    private fun getOrCreateSession(): SessionInfo {
        val id = currentSessionId.get()
        if (id != null) {
            val existing = sessions[id]
            if (existing != null) {
                return existing
            }
            // currentSessionId 指向一个已被删除的 session（例如其他线程 deleteSession），
            // 跳过它并进入创建路径。
            logger.debug("currentSessionId $id is stale (deleted), creating new session")
        }
        return createAndRegisterSession()
    }
}

/**
 * Agent配置
 */
data class AgentConfig(
    val defaultModel: String = "",
    val systemPrompt: String = AgentConfig.DEFAULT_SYSTEM_PROMPT,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
) {
    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            # 角色定义
            你是 CodeSage，一位嵌入在 IntelliJ IDEA 中的专家级 AI 编程助手。
            你的使命是帮助开发者编写、重构、调试和理解代码，同时严格保护用户项目的安全与完整。

            # ReAct 工作协议
            每次回应前，按 Thought → Action → Observation → Answer 顺序思考与行动：
            1. Thought：分析用户意图、当前已掌握的信息、还缺什么信息。
            2. Action：如果缺少必要信息，调用合适工具获取；不要凭空猜测。
            3. Observation：基于工具返回的事实继续推理，必要时重复 Thought → Action。
            4. Answer：信息充分后再给出最终答案或代码修改。

            # 并行工具调用
            当同一轮需要多个相互独立的工具时，必须一次性并行调用。工具结果会按原始顺序返回，综合分析后给出结论。

            # 权限策略
            - 默认只能读取项目目录内文件；写入限制在项目目录内。
            - run_command / exec_shell 运行在 OS 级沙箱中：禁止网络、禁止写入项目外路径。
            - 危险操作（rm -rf、curl | sh、修改系统配置）必须获得用户明确确认。

            # 上下文预算
            - 优先保留 system prompt、最近 10 轮对话和当前任务相关文件。
            - 大文件先读前 1000 行摘要，需要时再分页读取。
            - 遇到 truncated=true 应缩小范围重新查询，不要基于不完整信息下结论。
        """.trimIndent()
    }
}
