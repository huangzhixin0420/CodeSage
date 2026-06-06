package com.codesage.ide.ui.components.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.Timer

/**
 * Agent 对话轮次面板（Claude Code / Cursor 风格）
 *
 * 一个 Turn 包含：
 * - 用户消息区域（顶部，简洁展示）
 * - AI 响应区域（下方，包含思考、工具调用、回复内容、操作栏）
 *
 * 采用全宽扁平布局，非气泡式，更符合编程 Agent 的审美。
 */
class AgentTurnPanel(
    private val project: Project?,
    val turnId: String = "turn_${System.currentTimeMillis()}"
) : JPanel(BorderLayout()) {

    // ===== 状态 =====
    private var userContent: String = ""
    private var agentContentBuilder = StringBuilder()
    private var isStreaming = false
    private var turnState = TurnState.THINKING

    enum class TurnState {
        THINKING,      // AI 正在思考
        TOOL_CALLING,  // AI 正在调用工具
        RESPONDING,    // AI 正在生成回复
        COMPLETED,     // 本轮完成
        ERROR          // 发生错误
    }

    // ===== 子组件 =====
    private val userSection: UserSectionPanel
    private val agentSection: AgentResponseArea

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0)

        userSection = UserSectionPanel()
        agentSection = AgentResponseArea(project)

        val contentPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(userSection)
            add(agentSection)
        }

        add(contentPanel, BorderLayout.CENTER)

        // 分隔线
        add(
            JPanel().apply {
                isOpaque = true
                background = JBColor(Color(0xE8_E8_E8), Color(0x2D_2D_2D))
                preferredSize = Dimension(0, 1)
                maximumSize = Dimension(Int.MAX_VALUE, 1)
            },
            BorderLayout.SOUTH
        )
    }

    // ========== 用户消息 ==========

    fun setUserMessage(content: String) {
        userContent = content
        userSection.setContent(content)
    }

    // ========== AI 响应流式控制 ==========

    fun startThinking(statusText: String = "思考中...") {
        turnState = TurnState.THINKING
        agentSection.showThinking(statusText)
    }

    fun updateThinking(text: String) {
        agentSection.appendThinkingDetail(text)
    }

    fun completeThinking(elapsedMs: Long = 0) {
        agentSection.markThinkingComplete(elapsedMs)
    }

    fun startToolCall(toolName: String, toolId: String, summary: String) {
        turnState = TurnState.TOOL_CALLING
        agentSection.addToolCall(toolName, toolId, summary)
    }

    fun completeToolCall(toolId: String, success: Boolean, result: String) {
        agentSection.completeToolCall(toolId, success, result)
    }

    fun startResponding() {
        turnState = TurnState.RESPONDING
        isStreaming = true
        agentSection.startContentStream()
    }

    fun appendResponseDelta(delta: String) {
        agentContentBuilder.append(delta)
        agentSection.appendStreamDelta(delta)
    }

    fun finalizeResponse() {
        turnState = TurnState.COMPLETED
        isStreaming = false
        val fullContent = agentContentBuilder.toString()
        agentSection.finalizeContent(fullContent)
    }

    fun setError(message: String) {
        turnState = TurnState.ERROR
        agentSection.showError(message)
    }

    fun setModelLabel(model: String) {
        agentSection.setModelLabel(model)
    }

    fun getFullResponse(): String = agentContentBuilder.toString()

    // ========== 内部类：用户消息区域 ==========

    inner class UserSectionPanel : JPanel(BorderLayout()) {

        private val contentLabel = JLabel().apply {
            font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 13f)
            foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
        }

        init {
            isOpaque = false
            border = JBUI.Borders.empty(12, 16, 8, 16)

            val iconLabel = JLabel("👤").apply {
                font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 14f)
            }

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(iconLabel)
                add(contentLabel)
            }

            add(leftPanel, BorderLayout.WEST)
        }

        fun setContent(content: String) {
            // 对于长消息，显示前 200 字符，支持换行
            val display = if (content.length > 200) content.take(200) + "..." else content
            contentLabel.text = "<html><body style='width:600px'>${
                MarkdownRenderer.escapeHtml(display).replace("\n", "<br>")
            }</body></html>"
        }
    }

    // ========== 内部类：AI 响应区域 ==========

    inner class AgentResponseArea(private val project: Project?) : JPanel(BorderLayout()) {

        private val thinkingPanel: CollapsibleThinkingPanel
        private val toolCallsContainer: JPanel
        private val contentPanel: ContentStreamPanel
        private val actionBar: ActionBarPanel
        private val errorPanel: ErrorPanel

        private val modelLabel = JLabel("CodeSage").apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD, 12f)
            foreground = JBColor(Color(0x66_66_66), Color(0xAA_AA_AA))
        }

        init {
            isOpaque = false
            border = JBUI.Borders.empty(8, 16, 12, 16)

            // 头部：图标 + Agent 名称 + 预算标签
            val headerPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 0, 8, 0)

                val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    val iconLabel = JLabel("🤖").apply {
                        font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 14f)
                    }
                    add(iconLabel)
                    add(modelLabel)
                }

                add(leftPanel, BorderLayout.WEST)
            }

            // 思考面板（初始隐藏）
            thinkingPanel = CollapsibleThinkingPanel()
            thinkingPanel.isVisible = false

            // 工具调用容器（垂直排列）
            toolCallsContainer = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = LEFT_ALIGNMENT
            }

            // 内容面板（流式输出）
            contentPanel = ContentStreamPanel(project)

            // 操作栏（复制、重新生成）
            actionBar = ActionBarPanel()
            actionBar.isVisible = false

            // 错误面板（初始隐藏）
            errorPanel = ErrorPanel()
            errorPanel.isVisible = false

            // 中间内容区域
            val centerPanel = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = LEFT_ALIGNMENT
                add(thinkingPanel)
                add(toolCallsContainer)
                add(contentPanel)
                add(errorPanel)
                add(actionBar)
            }

            add(headerPanel, BorderLayout.NORTH)
            add(centerPanel, BorderLayout.CENTER)
        }

        fun setModelLabel(model: String) {
            modelLabel.text = "CodeSage · $model"
        }

        // --- 思考过程 ---

        fun showThinking(statusText: String) {
            thinkingPanel.reset(statusText)
            thinkingPanel.isVisible = true
            revalidate()
            repaint()
        }

        fun appendThinkingDetail(text: String) {
            thinkingPanel.appendDetail(text)
        }

        fun markThinkingComplete(elapsedMs: Long) {
            thinkingPanel.markComplete(elapsedMs)
        }

        // --- 工具调用 ---

        private val toolCallPanels = mutableMapOf<String, EmbeddedToolCallPanel>()

        fun addToolCall(toolName: String, toolId: String, summary: String) {
            val panel = EmbeddedToolCallPanel(toolName, summary)
            toolCallPanels[toolId] = panel
            toolCallsContainer.add(panel)
            toolCallsContainer.revalidate()
            toolCallsContainer.repaint()
        }

        fun completeToolCall(toolId: String, success: Boolean, result: String) {
            toolCallPanels[toolId]?.markComplete(success, result)
            toolCallsContainer.revalidate()
            toolCallsContainer.repaint()
        }

        // --- 内容输出 ---

        fun startContentStream() {
            contentPanel.startStream()
        }

        fun appendStreamDelta(delta: String) {
            contentPanel.appendDelta(delta)
        }

        fun finalizeContent(fullContent: String) {
            contentPanel.finalizeContent(fullContent)
            actionBar.isVisible = true
            actionBar.setContent(fullContent)
            revalidate()
            repaint()
        }

        fun showError(message: String) {
            errorPanel.setMessage(message)
            errorPanel.isVisible = true
            revalidate()
            repaint()
        }
    }

    // ========== 可折叠思考面板 ==========

    inner class CollapsibleThinkingPanel : JPanel(BorderLayout()) {

        private val headerPanel: JPanel
        private val detailArea: JTextArea
        private var expanded = false
        private var isComplete = false
        private val startTime = System.currentTimeMillis()

        private val statusLabel = JLabel("思考中...").apply {
            font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(Color(0x88_88_88), Color(0x99_99_99))
        }

        private val expandLabel = JLabel("▶").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
            cursor = Cursor(Cursor.HAND_CURSOR)
        }

        private val dotLabel = JLabel("●").apply {
            font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 10f)
            foreground = JBColor(Color(0xED_6C_02), Color(0xFF_A0_00))
        }

        private val timerLabel = JLabel("0.0s").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(0x99_99_99), Color(0x66_66_66))
        }

        private val timer: Timer

        init {
            isOpaque = true
            background = JBColor(Color(0xFA_FA_FA), Color(0x25_25_25))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(0xE8_E8_E8), Color(0x3D_3D_3D))),
                JBUI.Borders.empty(0)
            )

            headerPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 10)
                cursor = Cursor(Cursor.HAND_CURSOR)

                val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(dotLabel)
                    add(statusLabel)
                    add(timerLabel)
                }

                add(left, BorderLayout.WEST)
                add(expandLabel, BorderLayout.EAST)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        toggleExpand()
                    }
                })
            }

            detailArea = JTextArea().apply {
                isEditable = false
                font = JBUI.Fonts.create("JetBrains Mono", 11)
                background = JBColor(Color(0xFA_FA_FA), Color(0x25_25_25))
                foreground = JBColor(Color(0x66_66_66), Color(0x99_99_99))
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(4, 10, 8, 10)
                isVisible = false
            }

            add(headerPanel, BorderLayout.NORTH)
            add(detailArea, BorderLayout.CENTER)

            // 计时器
            timer = Timer(100) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                timerLabel.text = String.format("%.1fs", elapsed)
            }.apply { isRepeats = true; start() }
        }

        fun reset(statusText: String) {
            isComplete = false
            expanded = false
            expandLabel.text = "▶"
            statusLabel.text = statusText
            statusLabel.foreground = JBColor(Color(0x88_88_88), Color(0x99_99_99))
            dotLabel.isVisible = true
            detailArea.text = ""
            detailArea.isVisible = false
            timer.start()
        }

        fun appendDetail(text: String) {
            detailArea.append("$text\n")
        }

        fun markComplete(elapsedMs: Long) {
            if (isComplete) return
            isComplete = true
            timer.stop()
            dotLabel.isVisible = false
            val elapsedSec = elapsedMs / 1000.0
            statusLabel.text = "思考完成 · ${String.format("%.1f", elapsedSec)}s"
            statusLabel.foreground = JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))
            timerLabel.isVisible = false
            revalidate()
            repaint()
        }

        private fun toggleExpand() {
            expanded = !expanded
            expandLabel.text = if (expanded) "▼" else "▶"
            detailArea.isVisible = expanded
            revalidate()
            repaint()
        }

        override fun removeNotify() {
            super.removeNotify()
            timer.stop()
        }
    }

    // ========== 内嵌工具调用面板 ==========

    inner class EmbeddedToolCallPanel(
        private val toolName: String,
        private var summary: String
    ) : JPanel(BorderLayout()) {

        private val iconLabel = JLabel("◐").apply {
            font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(Color(0xED_6C_02), Color(0xFF_A0_00))
        }

        private val textLabel = JLabel("$toolName · $summary").apply {
            font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(Color(0x55_55_55), Color(0xAA_AA_AA))
        }

        private val detailArea = JTextArea().apply {
            isEditable = false
            font = JBUI.Fonts.create("JetBrains Mono", 11)
            background = JBColor(Color(0xFA_FA_FA), Color(0x1E_1E_1E))
            foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(4, 10)
            isVisible = false
        }

        private var expanded = false
        private var isComplete = false

        init {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)

            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 10)
                cursor = Cursor(Cursor.HAND_CURSOR)

                val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(iconLabel)
                    add(textLabel)
                }

                val expandLabel = JLabel("▶").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
                }

                add(left, BorderLayout.WEST)
                add(expandLabel, BorderLayout.EAST)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        if (isComplete) {
                            expanded = !expanded
                            expandLabel.text = if (expanded) "▼" else "▶"
                            detailArea.isVisible = expanded
                            revalidate()
                            repaint()
                        }
                    }
                })
            }

            add(header, BorderLayout.NORTH)
            add(detailArea, BorderLayout.CENTER)
        }

        fun markComplete(success: Boolean, result: String) {
            isComplete = true
            iconLabel.text = if (success) "✓" else "✗"
            iconLabel.foreground = if (success)
                JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))
            else
                JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))

            textLabel.text = "$toolName · ${if (success) "完成" else "失败"}"
            detailArea.text = result.take(2000)
            revalidate()
            repaint()
        }
    }

    // ========== 内容流式面板 ==========

    inner class ContentStreamPanel(private val project: Project?) : JPanel(BorderLayout()) {

        private val contentContainer = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }

        private val cursorLabel = JLabel("▌").apply {
            font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 14f)
            foreground = JBColor(Color(0x33_33_33), Color(0xE0_E0_E0))
            isVisible = false
        }

        private val cursorTimer = Timer(530) {
            cursorLabel.isVisible = !cursorLabel.isVisible
        }.apply { isRepeats = true }

        private var currentRichTextPane: JTextPane? = null
        private var isInStream = false
        private var streamBuffer = StringBuilder()
        private val codeBlockComponents = mutableListOf<CodeBlockComponent>()

        init {
            isOpaque = false
            add(contentContainer, BorderLayout.CENTER)
        }

        fun startStream() {
            isInStream = true
            streamBuffer.clear()
            contentContainer.removeAll()
            codeBlockComponents.forEach { it.dispose() }
            codeBlockComponents.clear()

            // 初始创建一个空的文本面板用于流式追加
            currentRichTextPane = createRichTextPane("")
            contentContainer.add(currentRichTextPane)
            contentContainer.add(cursorLabel)
            cursorLabel.isVisible = true
            cursorTimer.start()

            revalidate()
            repaint()
        }

        fun appendDelta(delta: String) {
            if (!isInStream) return
            streamBuffer.append(delta)

            // 简单的流式追加：直接追加到当前文本面板
            // 更精细的做法是实时解析 Markdown，但为了性能和简单，这里做简单追加
            val pane = currentRichTextPane ?: return
            val doc = pane.document
            val kit = pane.editorKit as javax.swing.text.html.HTMLEditorKit
            try {
                val html = MarkdownRenderer.escapeHtml(delta)
                    .replace("\n", "<br>")
                kit.insertHTML(doc as javax.swing.text.html.HTMLDocument, doc.length, html, 0, 0, null)
            } catch (_: Exception) {
                // 忽略插入错误
            }

            revalidate()
            repaint()
        }

        fun finalizeContent(fullContent: String) {
            isInStream = false
            cursorTimer.stop()
            cursorLabel.isVisible = false

            // 重新渲染完整 Markdown
            contentContainer.removeAll()
            codeBlockComponents.forEach { it.dispose() }
            codeBlockComponents.clear()

            renderMarkdown(fullContent)

            revalidate()
            repaint()
        }

        private fun renderMarkdown(content: String) {
            if (content.isBlank()) return

            val blocks = MarkdownRenderer.parse(content)
            for (block in blocks) {
                when (block) {
                    is MarkdownRenderer.Block.CodeBlock -> {
                        val cb = CodeBlockComponent(project, block.language, block.code)
                        codeBlockComponents.add(cb)
                        contentContainer.add(cb)
                        contentContainer.add(Box.createVerticalStrut(4))
                    }

                    is MarkdownRenderer.Block.Paragraph -> {
                        val html = MarkdownRenderer.segmentsToHtml(block.segments)
                        contentContainer.add(createRichTextPane(html))
                        contentContainer.add(Box.createVerticalStrut(2))
                    }

                    else -> {
                        val html = MarkdownRenderer.blockToHtml(block)
                        contentContainer.add(createRichTextPane(html))
                        contentContainer.add(Box.createVerticalStrut(2))
                    }
                }
            }
        }

        private fun createRichTextPane(htmlContent: String): JTextPane {
            val textColor = JBColor(Color(0x33_33_33), Color(0xE0_E0_E0))
            val textColorHex = String.format("#%06X", textColor.rgb and 0xFFFFFF)
            val inlineCodeBg = if (JBColor.isBright()) "#E8E8E8" else "#3D3D3D"
            val maxWidth = JBUI.scale(640)

            val kit = javax.swing.text.html.HTMLEditorKit()
            val styleSheet = javax.swing.text.html.StyleSheet()
            styleSheet.addStyleSheet(kit.styleSheet)
            styleSheet.addRule("body { font-family:'Segoe UI',system-ui,sans-serif; font-size:13px; color:$textColorHex; margin:0; padding:0; }")
            styleSheet.addRule("p { margin-top:4px; margin-bottom:4px; }")
            styleSheet.addRule("ul { margin-top:4px; margin-bottom:4px; margin-left:16px; padding-left:8px; }")
            styleSheet.addRule("ol { margin-top:4px; margin-bottom:4px; margin-left:16px; padding-left:8px; }")
            styleSheet.addRule("li { margin-top:2px; margin-bottom:2px; }")
            styleSheet.addRule("code { background-color:$inlineCodeBg; font-family:'JetBrains Mono',monospace; font-size:12px; padding:1px 3px; }")
            styleSheet.addRule("pre { background-color:${if (JBColor.isBright()) "#F5F5F5" else "#2D2D2D"}; padding:8px; }")
            styleSheet.addRule("b { font-weight:bold; }")
            styleSheet.addRule("i { font-style:italic; }")
            styleSheet.addRule("h1 { font-size:18px; font-weight:bold; margin-top:14px; margin-bottom:8px; }")
            styleSheet.addRule("h2 { font-size:16px; font-weight:bold; margin-top:12px; margin-bottom:6px; }")
            styleSheet.addRule("h3 { font-size:15px; font-weight:bold; margin-top:10px; margin-bottom:4px; }")
            kit.styleSheet = styleSheet

            val pane = JTextPane().apply {
                editorKit = kit
                isEditable = false
                isOpaque = false
                putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true)
                border = JBUI.Borders.empty(0)
                text = "<html><body>$htmlContent</body></html>"
            }

            pane.setSize(maxWidth, Int.MAX_VALUE)
            val h = pane.preferredSize.height.coerceAtLeast(JBUI.scale(18))
            pane.preferredSize = Dimension(maxWidth, h)
            pane.maximumSize = Dimension(maxWidth, Int.MAX_VALUE)

            return pane
        }
    }

    // ========== 操作栏 ==========

    inner class ActionBarPanel : JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)) {

        private var content = ""

        init {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 0, 0)

            val copyLink = createActionLink("📋 复制") {
                if (content.isNotEmpty()) {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(content), null)
                }
            }

            val regenerateLink = createActionLink("🔄 重新生成") {
                // TODO: 触发重新生成
            }

            add(copyLink)
            add(regenerateLink)
        }

        fun setContent(text: String) {
            content = text
        }

        private fun createActionLink(text: String, onClick: () -> Unit): JLabel {
            return JLabel(text).apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
                cursor = Cursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        onClick()
                    }

                    override fun mouseEntered(e: MouseEvent?) {
                        foreground = JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
                    }
                })
            }
        }
    }

    // ========== 错误面板 ==========

    inner class ErrorPanel : JPanel(BorderLayout()) {

        private val messageLabel = JLabel().apply {
            font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))
        }

        init {
            isOpaque = true
            background = JBColor(Color(0xFF_EB_EE), Color(0x3D_1A_1A))
            border = JBUI.Borders.empty(8, 12)
            add(messageLabel, BorderLayout.CENTER)
        }

        fun setMessage(message: String) {
            messageLabel.text =
                "<html><body style='width:500px'>⚠️ ${MarkdownRenderer.escapeHtml(message)}</body></html>"
        }
    }
}
