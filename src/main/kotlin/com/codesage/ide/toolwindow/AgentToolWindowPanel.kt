package com.codesage.ide.toolwindow

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.AgentSession
import com.codesage.agent.core.AgentState
import com.codesage.agent.core.SubAgentExecutor
import com.codesage.agent.multiagent.KanbanOrchestrator
import com.codesage.ide.ui.components.kanban.KanbanBoardPanel
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
import javax.swing.JTabbedPane

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
    private lateinit var kanbanPanel: KanbanBoardPanel

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

        // Kanban 面板
        kanbanPanel = KanbanBoardPanel(null)

        // 标签页面板
        val tabbedPane = JTabbedPane().apply {
            addTab("💬 Chat", chatPanel)
            addTab("📋 Kanban", kanbanPanel)
            font = JBUI.Fonts.label()
        }

        add(tabbedPane, java.awt.BorderLayout.CENTER)
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
                onSendMessage = { message ->
                    logger.info("[AgentToolWindowPanel] onSendMessage called, message length=${message.length}, chatMode=${chatPanel.getCurrentChatMode()}")
                    // T1.5 修复：传用户选中的 chatMode 选区。null = 用户未选 → 后端建议。
                    val chatMode = chatPanel.getCurrentChatMode()
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
                onContinueBudget = { extraIterations ->
                    logger.info("[AgentToolWindowPanel] continue budget invoked, extraIterations=$extraIterations")
                    core.continueConversation(extraIterations)
                        ?: kotlinx.coroutines.flow.flow {
                            emit(com.codesage.agent.core.AgentStreamEvent.Error("无法继续：没有可恢复的已暂停任务"))
                            emit(com.codesage.agent.core.AgentStreamEvent.Done)
                        }
                }
            )

            // Initialize model selector UI
            refreshModelSelector()
            logger.info("chatPanel initialized successfully")

            // 初始化 Kanban 面板（现在agentCore已可用）
            val kanbanOrchestrator = KanbanOrchestrator(core, SubAgentExecutor(core))
            kanbanPanel.setOrchestrator(kanbanOrchestrator)

            // 监听 Agent 状态
            scope?.launch {
                agentCore?.state?.collectLatest { state ->
                    updateStatusFromState(state)
                }
            }

            // 检测 IDE 主题并同步
            syncTheme()

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
        val session = core.createSession()
        chatPanel.notifySessionCreated(sessionToMap(session))
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
        chatPanel.dispose()
        agentCore?.shutdown()
    }
}
