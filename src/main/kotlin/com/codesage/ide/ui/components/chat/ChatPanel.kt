package com.codesage.ide.ui.components.chat

import com.codesage.agent.core.AgentResult
import com.codesage.agent.core.AgentSession
import com.codesage.agent.core.AgentStreamEvent
import com.codesage.model.dto.Message
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.AdjustmentEvent
import java.awt.event.AdjustmentListener
import javax.swing.*

/**
 * CodeSage 主聊天面板（重构版）
 *
 * 采用 AgentTurnPanel 组织消息流，每个 Turn 是一个完整的对话轮次：
 * - 用户消息
 * - AI 思考过程（可折叠）
 * - AI 工具调用（内嵌状态）
 * - AI 回复（流式 Markdown）
 * - 操作栏（复制、重新生成）
 *
 * 设计参考：Claude Code、Cursor Composer
 */
class ChatPanel(
    private val project: Project?,
    private val onSendMessage: suspend (String) -> AgentResult,
    private val onSendStream: (String) -> kotlinx.coroutines.flow.Flow<AgentStreamEvent>,
    private val onStopStream: () -> Unit,
    private val onClearSession: () -> Unit
) : JPanel(BorderLayout()) {

    private val messageListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = JBColor(Color(0xFF_FF_FF), Color(0x1E_1E_1E))
        border = JBUI.Borders.empty(0)
        alignmentX = LEFT_ALIGNMENT
    }

    private val scrollPane = JBScrollPane(messageListPanel).apply {
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = null
    }

    private val inputPanel = InputPanel(
        onSend = { message -> sendMessage(message) },
        onStop = { stopStreaming() }
    )

    private val headerPanel = HeaderPanel()

    // 智能滚动控制
    private var isUserScrolling = false
    private val scrollListener = AdjustmentListener { e ->
        if (e.valueIsAdjusting) {
            isUserScrolling = true
        }
        val verticalBar = scrollPane.verticalScrollBar
        val atBottom = verticalBar.value + verticalBar.visibleAmount >= verticalBar.maximum - 30
        if (atBottom) {
            isUserScrolling = false
        }
    }

    private var streamingJob: Job? = null
    private var scope: CoroutineScope? = null

    // Turn 管理
    private val turns = mutableListOf<AgentTurnPanel>()
    private var currentTurn: AgentTurnPanel? = null

    init {
        add(headerPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)

        scrollPane.verticalScrollBar.addAdjustmentListener(scrollListener)
    }

    fun initialize(scope: CoroutineScope) {
        this.scope = scope
    }

    fun setModelLabel(model: String) {
        inputPanel.setModelLabel(model)
        headerPanel.setModelLabel(model)
    }

    // ========== 消息发送 ==========

    private fun sendMessage(message: String) {
        inputPanel.clearInput()
        inputPanel.isProcessing = true

        // 创建新的 Turn
        val turn = AgentTurnPanel(project)
        turn.setUserMessage(message)
        addTurn(turn)
        currentTurn = turn

        // 启动流式输出
        scope?.launch {
            try {
                var turnStarted = false
                var fullResponse = StringBuilder()
                var startTime = System.currentTimeMillis()

                onSendStream(message).collect { event ->
                    withContext(Dispatchers.Main) {
                        when (event) {
                            is AgentStreamEvent.TextDelta -> {
                                if (!turnStarted) {
                                    turnStarted = true
                                    turn.startResponding()
                                }
                                turn.appendResponseDelta(event.delta)
                                fullResponse.append(event.delta)
                                if (!isUserScrolling) scrollToBottom()
                            }

                            is AgentStreamEvent.Thinking -> {
                                if (!turnStarted) {
                                    turnStarted = true
                                    turn.startThinking(event.message)
                                }
                                turn.updateThinking(event.message)
                            }

                            is AgentStreamEvent.ToolCallStart -> {
                                if (!turnStarted) {
                                    turnStarted = true
                                }
                                turn.startToolCall(
                                    toolName = event.toolCall.name,
                                    toolId = event.toolCall.id,
                                    summary = "Running ${event.toolCall.name}..."
                                )
                                if (!isUserScrolling) scrollToBottom()
                            }

                            is AgentStreamEvent.ToolCallResult -> {
                                turn.completeToolCall(
                                    toolId = event.toolCallId,
                                    success = event.success,
                                    result = event.result
                                )
                                if (!isUserScrolling) scrollToBottom()
                            }

                            is AgentStreamEvent.SubAgentStart -> {
                                // 子 Agent 作为特殊工具调用展示
                                turn.startToolCall(
                                    toolName = "subagent",
                                    toolId = event.sessionId,
                                    summary = event.taskDescription
                                )
                                if (!isUserScrolling) scrollToBottom()
                            }

                            is AgentStreamEvent.SubAgentProgress -> {
                                // 子 Agent 进度更新
                                turn.updateThinking("[子Agent] ${event.message}")
                            }

                            is AgentStreamEvent.SubAgentComplete -> {
                                turn.completeToolCall(
                                    toolId = event.sessionId,
                                    success = event.success,
                                    result = event.output ?: ""
                                )
                                if (!isUserScrolling) scrollToBottom()
                            }

                            is AgentStreamEvent.Error -> {
                                turn.setError(event.message)
                                if (!isUserScrolling) scrollToBottom()
                            }

                            AgentStreamEvent.Done -> {
                                // 流式结束，由下方逻辑处理
                            }

                            else -> {
                                // 新事件类型默认处理：忽略
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (turnStarted) {
                        turn.completeThinking(elapsed)
                        turn.finalizeResponse()
                    } else {
                        // 没有任何事件，显示空响应
                        turn.startResponding()
                        turn.appendResponseDelta("(无响应)")
                        turn.finalizeResponse()
                    }
                    inputPanel.isProcessing = false
                    currentTurn = null
                    if (!isUserScrolling) scrollToBottom()
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    currentTurn?.finalizeResponse()
                    inputPanel.isProcessing = false
                    currentTurn = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    currentTurn?.setError(e.message ?: "未知错误")
                    inputPanel.isProcessing = false
                    currentTurn = null
                    if (!isUserScrolling) scrollToBottom()
                }
            }
        }?.let { streamingJob = it }
    }

    private fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
    }

    // ========== 历史消息加载 ==========

    fun loadHistory(messages: List<Message>) {
        clearMessages()

        // 将历史消息按轮次分组（USER + ASSISTANT/TOOL 配对）
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            if (msg.role == com.codesage.model.dto.Role.USER) {
                val turn = AgentTurnPanel(project)
                turn.setUserMessage(msg.content)

                // 收集该轮次的 AI 响应
                var j = i + 1
                val assistantMessages = mutableListOf<String>()
                while (j < messages.size && messages[j].role != com.codesage.model.dto.Role.USER) {
                    if (messages[j].role == com.codesage.model.dto.Role.ASSISTANT) {
                        assistantMessages.add(messages[j].content)
                    }
                    j++
                }

                if (assistantMessages.isNotEmpty()) {
                    val fullResponse = assistantMessages.joinToString("\n")
                    turn.startResponding()
                    turn.appendResponseDelta(fullResponse)
                    turn.finalizeResponse()
                }

                addTurn(turn)
                i = j
            } else {
                // SYSTEM 消息单独显示
                if (msg.role == com.codesage.model.dto.Role.SYSTEM) {
                    addSystemMessage(msg.content)
                }
                i++
            }
        }
    }

    // ========== 消息管理 ==========

    private fun addTurn(turn: AgentTurnPanel) {
        turns.add(turn)
        messageListPanel.add(turn)
        messageListPanel.revalidate()
        if (!isUserScrolling) scrollToBottom()
    }

    fun addSystemMessage(content: String) {
        val label =
            JLabel("<html><body style='width:600px; color:#888888'>${MarkdownRenderer.escapeHtml(content)}</body></html>").apply {
                font = JBUI.Fonts.smallFont()
                border = JBUI.Borders.empty(8, 16)
            }
        messageListPanel.add(label)
        messageListPanel.revalidate()
        if (!isUserScrolling) scrollToBottom()
    }

    fun clearMessages() {
        turns.clear()
        currentTurn = null
        messageListPanel.removeAll()
        messageListPanel.revalidate()
        messageListPanel.repaint()
    }

    // ========== 滚动 ==========

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val verticalBar = scrollPane.verticalScrollBar
            verticalBar.value = verticalBar.maximum
        }
    }

    fun dispose() {
        streamingJob?.cancel()
        scrollPane.verticalScrollBar.removeAdjustmentListener(scrollListener)
    }

    // ========== 顶部标题栏 ==========

    inner class HeaderPanel : JPanel(BorderLayout()) {

        private val titleLabel = JLabel("CodeSage").apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD, 14f)
            foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
        }

        private val modelLabel = JLabel("").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(0x99_99_99), Color(0x66_66_66))
        }

        private val clearLabel = JLabel("清空").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    val confirm = JOptionPane.showConfirmDialog(
                        this@ChatPanel,
                        "确定要清空当前会话的所有消息吗？",
                        "清空会话",
                        JOptionPane.YES_NO_OPTION
                    )
                    if (confirm == JOptionPane.YES_OPTION) {
                        clearMessages()
                        onClearSession()
                    }
                }

                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                    foreground = JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))
                }

                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                    foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
                }
            })
        }

        init {
            isOpaque = true
            background = JBColor(Color(0xFF_FF_FF), Color(0x1E_1E_1E))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(Color(0xE0_E0_E0), Color(0x33_33_33))),
                JBUI.Borders.empty(10, 16)
            )

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(titleLabel)
                add(modelLabel)
            }

            add(leftPanel, BorderLayout.WEST)
            add(clearLabel, BorderLayout.EAST)
        }

        fun setModelLabel(model: String) {
            modelLabel.text = "· $model"
        }
    }
}
