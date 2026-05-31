package com.codesage.ide.inline

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.AgentConfig
import com.codesage.ide.inline.prompt.InlineChatPrompts
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.codesage.shared.config.PluginConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Inline Chat 全局控制器
 *
 * 每个 Project 一个实例，管理该项目中所有 Editor 的 Inline Chat 会话。
 * 核心职责：
 * 1. 防止同一 Editor 同时存在多个 Inline Chat
 * 2. 协调会话生命周期（创建、关闭、清理）
 * 3. 项目关闭时自动清理所有资源
 */
@Service(Service.Level.PROJECT)
class InlineChatController(private val project: Project) : Disposable {

    /** Editor -> Session 映射 */
    private val activeSessions = ConcurrentHashMap<Editor, InlineChatSession>()

    /** 用于生成会话唯一 ID */
    private var sessionCounter = 0

    /** Inline Chat 专用 AgentCore（与 sidebar chat 完全隔离，避免上下文污染） */
    private val agentCore: AgentCore = AgentCore(
        project = project,
        confirmationCallback = InlineChatGuardrails().asConfirmationCallback()
    )

    init {
        val config = PluginConfig.getInstance()
        agentCore.initialize(
            AgentConfig(
                defaultModel = config.defaultModel,
                systemPrompt = InlineChatPrompts.INLINE_SYSTEM_PROMPT,
                temperature = 0.3
            )
        )
    }

    /**
     * 获取 Inline Chat 专用 AgentCore
     */
    fun getAgentCore(): AgentCore = agentCore

    /**
     * 启动新的 Inline Chat 会话
     *
     * 如果该 Editor 已有活跃会话，先关闭旧的（保留 Diff 高亮直到用户操作）。
     *
     * @param editor 目标编辑器
     * @param context Inline Chat 上下文
     * @return 新创建的会话
     */
    fun startSession(editor: Editor, context: InlineChatContext): InlineChatSession {
        // 关闭同一 Editor 的已有会话
        activeSessions[editor]?.let { existingSession ->
            existingSession.dispose()
        }

        val sessionId = generateSessionId()
        val session = InlineChatSession(
            sessionId = sessionId,
            project = project,
            editor = editor,
            context = context,
            onDispose = { activeSessions.remove(editor) }
        )

        // 监听编辑器关闭，防止 Editor 内存泄漏
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {}
                override fun editorReleased(event: EditorFactoryEvent) {
                    if (event.editor == editor) {
                        closeSession(editor)
                    }
                }
            },
            session
        )

        activeSessions[editor] = session
        return session
    }

    /**
     * 获取指定 Editor 的活跃会话
     */
    fun getActiveSession(editor: Editor): InlineChatSession? {
        return activeSessions[editor]?.takeIf { it.isActive() }
    }

    /**
     * 获取所有活跃会话
     */
    fun getAllActiveSessions(): List<InlineChatSession> {
        return activeSessions.values.filter { it.isActive() }.toList()
    }

    /**
     * 关闭指定 Editor 的 Inline Chat 会话
     */
    fun closeSession(editor: Editor) {
        activeSessions[editor]?.dispose()
    }

    /**
     * 检查指定 Editor 是否有活跃会话
     */
    fun hasActiveSession(editor: Editor): Boolean {
        return activeSessions[editor]?.isActive() == true
    }

    /**
     * 活跃会话数量
     */
    fun activeSessionCount(): Int {
        return activeSessions.count { it.value.isActive() }
    }

    /**
     * 关闭所有 Inline Chat 会话（项目关闭时调用）
     */
    fun closeAllSessions() {
        val sessions = activeSessions.values.toList()
        activeSessions.clear()
        sessions.forEach { it.dispose() }
    }

    override fun dispose() {
        closeAllSessions()
        agentCore.shutdown()
    }

    private fun generateSessionId(): String {
        return "inline_${System.currentTimeMillis()}_${sessionCounter++}"
    }

    companion object {
        fun getInstance(project: Project): InlineChatController =
            project.getService(InlineChatController::class.java)
    }
}
