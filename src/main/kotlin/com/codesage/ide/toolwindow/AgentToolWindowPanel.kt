package com.codesage.ide.toolwindow

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.AgentSession
import com.codesage.agent.core.AgentState
import com.codesage.ide.ui.web.JCEFChatPanel
import com.codesage.ide.settings.SettingsChangeListener
import com.codesage.plugin.CodeSageProjectService
import com.codesage.shared.config.PluginConfig
import com.codesage.shared.utils.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.swing.JPanel

/**
 * Agent工具窗口面板（JCEF Web UI 重构版）
 *
 * 新架构：
 * - 左侧：SessionSidebarPanel（会话列表）
 * - 右侧：JCEFChatPanel（基于 JCEF 的现代 Web UI）
 * - 支持多会话管理、流式输出、代码块语法高亮、Artifacts
 */
class AgentToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : JPanel() {

    private val logger = Logger.getLogger<AgentToolWindowPanel>()

    private lateinit var chatPanel: JCEFChatPanel
    private var agentCore: AgentCore? = null
    private var scope: CoroutineScope? = null

    init {
        println("[CodeSage] AgentToolWindowPanel init started")
        layout = java.awt.BorderLayout()
        border = JBUI.Borders.empty(0)
        try {
            setupUI()
            println("[CodeSage] setupUI completed")
        } catch (e: Exception) {
            println("[CodeSage] setupUI failed: ${e.message}")
            e.printStackTrace()
        }
        try {
            initializeAgent()
            println("[CodeSage] initializeAgent completed")
        } catch (e: Exception) {
            println("[CodeSage] initializeAgent failed: ${e.message}")
            e.printStackTrace()
        }
        println("[CodeSage] AgentToolWindowPanel init completed")
    }

    private fun setupUI() {
        // 聊天面板 (JCEF Web UI，内部自带会话侧边栏)
        chatPanel = JCEFChatPanel(project)

        add(chatPanel, java.awt.BorderLayout.CENTER)
    }

    private fun initializeAgent() {
        try {
            println("[CodeSage] initializeAgent started")
            logger.info("Initializing agent...")
            val projectService = CodeSageProjectService.getInstance(project)
            println("[CodeSage] CodeSageProjectService obtained")
            agentCore = projectService.agentCore
            println("[CodeSage] AgentCore obtained: ${agentCore != null}")
            logger.info("AgentCore obtained: ${agentCore != null}")

            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

            // 初始化 JCEF Chat Panel
            val core = agentCore
            if (core == null) {
                logger.error("AgentCore is null, cannot initialize chat panel")
                return
            }
            logger.info("Initializing chatPanel with callbacks...")
            chatPanel.initialize(
                scope = scope!!,
                onSendMessage = { message, images, userLanguage ->
                    logger.info("[AgentToolWindowPanel] onSendMessage called, message length=${message.length}, images=${images.size}, chatMode=${chatPanel.getCurrentChatMode()}, userLanguage=$userLanguage")
                    // T1.5 修复：传用户选中的 chatMode 选区。null = 用户未选 → 后端建议。
                    val chatMode = chatPanel.getCurrentChatMode()
                    // P5.4: 检查当前模型是否支持 vision,不支持则提醒
                    if (images.isNotEmpty() && !isCurrentModelSupportsVision()) {
                        logger.warn("[AgentToolWindowPanel] current model does not support vision, ${images.size} image(s) will still be sent as markdown refs but model may not understand them")
                        // 不报错,继续发送(部分模型即使标记不支持也可能偶然能看图)
                    }
                    if (chatMode != null) {
                        core.chatWithTools(message, mode = chatMode, userExplicit = true)
                    } else {
                        core.chatWithTools(message, userExplicit = false)
                    }
                },
                onStop = { core.interrupt() },
                onSwitchModel = { model ->
                    logger.info("[AgentToolWindowPanel] switch model to $model")
                    core.switchModel(model)
                },
                onSwitchChatMode = { mode ->
                    logger.info("[AgentToolWindowPanel] switch chat mode to $mode")
                    // T1.5 修复：用户在 UI 上点了 mode 按钮时存到 chatPanel 状态里，
                    // 下一次发消息时 onSendMessage 会读取并以 userExplicit=true 传递。
                    chatPanel.setCurrentChatMode(mode)
                },
                onSessionAction = { action, params ->
                    logger.info("[AgentToolWindowPanel] session action: $action, params=$params")
                    when (action) {
                        "new_session" -> createNewSession()
                        "switch_session" -> {
                            val sessionId = params["sessionId"] as? String
                            if (sessionId != null) switchSession(sessionId)
                        }

                        "delete_session" -> {
                            val sessionId = params["sessionId"] as? String
                            if (sessionId != null) deleteSession(sessionId)
                        }

                        "rename_session" -> {
                            val sessionId = params["sessionId"] as? String
                            val name = params["name"] as? String
                            if (sessionId != null && name != null) renameSession(sessionId, name)
                        }

                        "request_sessions" -> refreshSessionList()
                    }
                },
            )

            // Initialize model selector UI
            refreshModelSelector()
            logger.info("chatPanel initialized successfully")

            // 监听 Agent 状态
            scope?.launch {
                agentCore?.state?.collectLatest { state ->
                    updateStatusFromState(state)
                }
            }

            // 检测 IDE 主题并同步
            syncTheme()
            // 订阅 IDE 主题变化（重启 / 切换主题后实时同步到 WebView）
            subscribeToIdeThemeChanges()

            // 刷新会话列表
            refreshSessionList()

            // 如果有当前会话，加载历史消息到前端
            core.getCurrentSession()?.let { session ->
                val history = core.getCurrentHistory().map { msg ->
                    mapOf(
                        "role" to msg.role.name.lowercase(),
                        "content" to msg.content
                    )
                }
                if (history.isNotEmpty()) {
                    chatPanel.loadHistory(history)
                }
            }

            val model = agentCore?.getCurrentModel() ?: "未配置"
            val config = PluginConfig.getInstance()
            val provider = config.getDefaultProvider()?.name ?: ""
            chatPanel.setModelLabel(model, provider)

            // 订阅配置变更事件
            subscribeToSettingsChanges()

            logger.info("AgentToolWindowPanel initialized with JCEF Web UI")
        } catch (e: Exception) {
            logger.error("Failed to initialize agent panel", e)
        }
    }

    private fun sessionToMap(session: AgentSession): Map<String, Any> {
        return mapOf(
            "id" to session.id,
            "name" to (session.name.ifEmpty { "New Session" }),
            "createdAt" to session.createdAt,
            "lastActivityAt" to session.lastActivityAt
        )
    }

    private fun createNewSession() {
        val core = agentCore ?: return
        core.saveCurrentSession()
        core.createSession()
        // v2.0 修复: 旧实现调 chatPanel.notifySessionCreated(单条 session),
        // 协议发的是 "session" 字段(单数),与前端 main.js 期望的 msg.sessions 不一致,
        // → setSessions([]) 整个 sidebar 被清空、新会话永远不显示。
        // 改走 refreshSessionList():它 sendSessions(全量) + notifySessionSwitched(当前)
        // 完全对齐现有 sidebar 协议,零协议漂移。
        refreshSessionList()
        chatPanel.clear()
    }

    private fun switchSession(sessionId: String) {
        val core = agentCore ?: return
        core.saveCurrentSession()
        if (core.switchSession(sessionId)) {
            val history = core.getCurrentHistory().map { msg ->
                mapOf(
                    "role" to msg.role.name.lowercase(),
                    "content" to msg.content
                )
            }
            chatPanel.loadHistory(history)
            chatPanel.notifySessionSwitched(sessionId)
        }
    }

    private fun deleteSession(sessionId: String) {
        val core = agentCore ?: return
        core.deleteSession(sessionId)
        chatPanel.notifySessionDeleted(sessionId)
        val currentId = core.getCurrentSession()?.id
        if (currentId != null) {
            chatPanel.notifySessionSwitched(currentId)
            chatPanel.clear()
        } else {
            chatPanel.clear()
        }
    }

    private fun renameSession(sessionId: String, name: String) {
        val core = agentCore ?: return
        core.renameSession(sessionId, name)
        chatPanel.notifySessionRenamed(sessionId, name)
    }

    private fun refreshSessionList() {
        val core = agentCore ?: return
        val sessions = core.getSessions()
        chatPanel.sendSessions(sessions.map { sessionToMap(it) })
        core.getCurrentSession()?.let {
            chatPanel.notifySessionSwitched(it.id)
        }
    }

    private fun refreshModelSelector() {
        val config = PluginConfig.getInstance()
        val enabledProviders = config.enabledProviders
        if (enabledProviders.isEmpty()) return

        val modelGroups = enabledProviders.map { provider ->
            mapOf(
                "provider" to provider.name,
                "models" to provider.models
            )
        }
        chatPanel.setAvailableModels(modelGroups)

        val defaultModel = config.defaultModel
        val defaultProvider = config.getDefaultProvider()
        if (defaultModel.isNotBlank()) {
            chatPanel.setModelLabel(defaultModel, defaultProvider?.name ?: "")
        }
    }

    private fun syncTheme() {
        val isDark = !com.intellij.ui.JBColor.isBright()
        chatPanel.setTheme(if (isDark) "dark" else "light")
        try {
            val laf = com.intellij.ide.ui.LafManager.getInstance().currentUIThemeLookAndFeel?.id ?: "Default"
            val isIslands = laf.contains("Islands", ignoreCase = true)
            chatPanel.setLaf(laf, isIslands, isDark)
        } catch (e: Exception) {
            logger.warn("[AgentToolWindowPanel] failed to detect LaF: ${e.message}")
        }
    }

    /**
     * 订阅 IDE 主题切换(例如用户从 Darcula 切到 IntelliJ Light)
     * 使用 LafManager 提供的现代 message bus 主题
     */
    private var themeConnection: com.intellij.util.messages.MessageBusConnection? = null
    private fun subscribeToIdeThemeChanges() {
        try {
            themeConnection?.dispose()
            val conn = ApplicationManager.getApplication().messageBus.connect()
            conn.subscribe(
                com.intellij.ide.ui.LafManagerListener.TOPIC,
                com.intellij.ide.ui.LafManagerListener {
                    logger.info("[AgentToolWindowPanel] IDE theme changed, propagating to WebView")
                    syncTheme()
                },
            )
            themeConnection = conn
        } catch (e: Throwable) {
            logger.warn("[AgentToolWindowPanel] failed to subscribe to theme changes: ${e.message}")
        }
    }

    private fun updateStatusFromState(state: AgentState) {
        val text = when (state) {
            AgentState.IDLE -> "就绪"
            AgentState.THINKING -> "思考中..."
            AgentState.EXECUTING -> "执行中..."
            AgentState.STREAMING -> "流式响应..."
            AgentState.WAITING_TOOL -> "等待工具..."
            AgentState.ERROR -> "错误"
        }
    }

    /**
     * 检查当前模型是否支持 vision(图片输入)
     * P5.4: 用于给用户提示
     */
    private fun isCurrentModelSupportsVision(): Boolean {
        val model = agentCore?.getCurrentModel() ?: return false
        val modelLower = model.lowercase()
        return when {
            "vision" in modelLower -> true
            "gpt-4o" in modelLower -> true
            "gpt-4-vision" in modelLower -> true
            "claude-3" in modelLower -> true
            "gemini" in modelLower && ("1.5" in modelLower || "2" in modelLower) -> true
            "kimi-vl" in modelLower -> true
            "minimax-vl" in modelLower -> true
            else -> false
        }
    }

    private fun subscribeToSettingsChanges() {
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(SettingsChangeListener.TOPIC, object : SettingsChangeListener {
                override fun onSettingsApplied() {
                    logger.info("Settings changed, refreshing model selector")
                    refreshModelSelector()
                }

                override fun onDefaultModelChanged(model: String, providerId: String) {
                    logger.info("Default model changed to $model")
                    agentCore?.switchModel(model)
                    val config = PluginConfig.getInstance()
                    val provider = config.getDefaultProvider()?.name ?: ""
                    chatPanel.setModelLabel(model, provider)
                }
            })
    }

    fun dispose() {
        scope?.cancel()
        themeConnection?.dispose()
        themeConnection = null
        chatPanel.dispose()
        agentCore?.shutdown()
    }
}
