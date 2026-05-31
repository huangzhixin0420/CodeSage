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
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Kimi CLI 风格的消息气泡组件（精致版）
 * - 用户消息右对齐蓝色气泡，AI 左对齐灰色气泡
 * - BoxLayout 实现弹性宽度，最大 640px
 * - JEditorPane + HTMLEditorKit.StyleSheet 实现高质量富文本渲染
 * - 圆角 12px，抗锯齿绘制
 */
class ChatMessage(
    private val project: Project?,
    val role: MessageRole,
    private var content: String,
    private val timestamp: String = formatTime()
) : JPanel() {

    enum class MessageRole {
        USER, ASSISTANT, SYSTEM, ERROR, TOOL
    }

    private val contentPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val codeBlockComponents = mutableListOf<CodeBlockComponent>()
    private var isStreaming = false

    private val cursorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        isVisible = false
        add(JLabel("▌").apply {
            font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 14f)
            foreground = getBubbleTextColor()
        })
    }
    private val cursorTimer = Timer(530) {
        cursorPanel.isVisible = !cursorPanel.isVisible
    }.apply { isRepeats = true }

    private var enterAlpha = 0f
    private var enterOffsetY = 12

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(2, 16)

        val bubblePanel = createBubblePanel().apply {
            maximumSize = Dimension(JBUI.scale(640), Int.MAX_VALUE)
        }

        when (role) {
            MessageRole.USER -> {
                add(Box.createHorizontalGlue())
                add(bubblePanel)
            }

            MessageRole.ASSISTANT -> {
                add(bubblePanel)
                add(Box.createHorizontalGlue())
            }

            else -> {
                add(Box.createHorizontalGlue())
                add(bubblePanel)
                add(Box.createHorizontalGlue())
            }
        }

        // 进入动画
        val animStart = System.currentTimeMillis()
        Timer(16) {
            val elapsed = System.currentTimeMillis() - animStart
            val progress = (elapsed / 200f).coerceIn(0f, 1f)
            val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            enterAlpha = eased
            enterOffsetY = (10 * (1f - eased)).toInt()
            repaint()
            if (progress >= 1f) (it.source as Timer).stop()
        }.apply { isRepeats = true; start() }
    }

    private fun createBubblePanel(): JPanel {
        return RoundedPanel(
            backgroundColor = getBubbleBackground(),
            cornerRadius = 12
        ).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(10, 14)

            // 头部
            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 0, 6, 0)

                val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    isOpaque = false
                    add(JLabel(getSenderLabel()).apply {
                        font = JBUI.Fonts.label().deriveFont(Font.BOLD, 11f)
                        foreground = getSenderColor()
                    })
                    add(JLabel(timestamp).apply {
                        font = JBUI.Fonts.smallFont()
                        foreground = JBColor(Color(0x99_99_99), Color(0x66_66_66))
                    })
                }

                val copyLabel = JLabel("Copy").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = JBColor(Color(0x88_88_88), Color(0x88_88_88))
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            val text = getPlainText()
                            if (text.isNotEmpty()) {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                                this@apply.text = "Copied!"
                                foreground = JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))
                                Timer(1500) {
                                    this@apply.text = "Copy"
                                    foreground = JBColor(Color(0x88_88_88), Color(0x88_88_88))
                                }.apply { isRepeats = false; start() }
                            }
                        }

                        override fun mouseEntered(e: MouseEvent?) {
                            foreground = JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))
                        }

                        override fun mouseExited(e: MouseEvent?) {
                            if (text != "Copied!") foreground = JBColor(Color(0x88_88_88), Color(0x88_88_88))
                        }
                    })
                }

                val right = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    isVisible = false
                    add(copyLabel)
                }

                add(left, BorderLayout.WEST)
                add(right, BorderLayout.EAST)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        right.isVisible = true; revalidate(); repaint()
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        right.isVisible = false; revalidate(); repaint()
                    }
                })
            }

            add(header, BorderLayout.NORTH)
            renderContent(content)
            add(contentPanel, BorderLayout.CENTER)
        }
    }

    private fun getBubbleBackground(): JBColor = when (role) {
        MessageRole.USER -> JBColor(Color(0xE3_F2_FD), Color(0x1E_3A_5F))
        MessageRole.ASSISTANT -> JBColor(Color(0xF5_F5_F5), Color(0x2D_2D_2D))
        MessageRole.SYSTEM -> JBColor(Color(0xFF_F8_E1), Color(0x3D_3D_1A))
        MessageRole.ERROR -> JBColor(Color(0xFF_EB_EE), Color(0x3D_1A_1A))
        MessageRole.TOOL -> JBColor(Color(0xF3_E5_F5), Color(0x2D_1A_3D))
    }

    private fun getBubbleTextColor(): JBColor = when (role) {
        MessageRole.USER -> JBColor(Color(0x15_65_C0), Color(0x90_CA_F9))
        MessageRole.ASSISTANT -> JBColor(Color(0x33_33_33), Color(0xE0_E0_E0))
        MessageRole.SYSTEM -> JBColor(Color(0x88_88_88), Color(0xAA_AA_AA))
        MessageRole.ERROR -> JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))
        MessageRole.TOOL -> JBColor(Color(0x7B_1F_A2), Color(0xCE_93_D8))
    }

    private fun getSenderLabel(): String = when (role) {
        MessageRole.USER -> "You"
        MessageRole.ASSISTANT -> "CodeSage"
        MessageRole.SYSTEM -> "System"
        MessageRole.ERROR -> "Error"
        MessageRole.TOOL -> "Tool"
    }

    private fun getSenderColor(): Color = when (role) {
        MessageRole.USER -> JBColor(Color(0x15_65_C0), Color(0x90_CA_F9))
        MessageRole.ASSISTANT -> JBColor(Color(0x66_66_66), Color(0xAA_AA_AA))
        MessageRole.SYSTEM -> JBColor(Color(0x88_88_88), Color(0xAA_AA_AA))
        MessageRole.ERROR -> JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))
        MessageRole.TOOL -> JBColor(Color(0x7B_1F_A2), Color(0xCE_93_D8))
    }

    private fun getPlainText(): String = buildString {
        for (block in contentPanel.components) {
            when (block) {
                is JTextPane -> append(block.text)
                is CodeBlockComponent -> append(block.codeText)
            }
            append("\n")
        }
    }

    fun appendStreamContent(delta: String) {
        if (role != MessageRole.ASSISTANT) return
        isStreaming = true

        val lastComponent = contentPanel.components.lastOrNull()
        if (lastComponent is JTextPane) {
            val doc = lastComponent.document
            val kit = lastComponent.editorKit as HTMLEditorKit
            val html = MarkdownRenderer.escapeHtml(delta)
            kit.insertHTML(doc as javax.swing.text.html.HTMLDocument, doc.length, html, 0, 0, null)
        }

        if (cursorPanel.parent != contentPanel) contentPanel.add(cursorPanel)
        cursorPanel.isVisible = true
        cursorTimer.start()

        revalidate()
        repaint()
    }

    fun finalizeStream() {
        isStreaming = false
        cursorTimer.stop()
        cursorPanel.isVisible = false
        if (cursorPanel.parent == contentPanel) contentPanel.remove(cursorPanel)
        revalidate()
        repaint()
    }

    private fun renderContent(content: String) {
        contentPanel.removeAll()
        codeBlockComponents.forEach { it.dispose() }
        codeBlockComponents.clear()

        if (content.isEmpty() && role == MessageRole.ASSISTANT) {
            contentPanel.add(createRichTextPane(""))
            return
        }

        if (role == MessageRole.SYSTEM || role == MessageRole.ERROR || role == MessageRole.TOOL) {
            contentPanel.add(
                createRichTextPane(
                    "<span style='color:${getSystemColorHex()};'>${MarkdownRenderer.escapeHtml(content)}</span>"
                )
            )
            return
        }

        val blocks = MarkdownRenderer.parse(content)
        for (block in blocks) {
            when (block) {
                is MarkdownRenderer.Block.CodeBlock -> {
                    val cb = CodeBlockComponent(project, block.language, block.code)
                    codeBlockComponents.add(cb)
                    contentPanel.add(cb)
                    contentPanel.add(Box.createVerticalStrut(4))
                }

                is MarkdownRenderer.Block.Paragraph -> {
                    contentPanel.add(createRichTextPane(MarkdownRenderer.segmentsToHtml(block.segments)))
                    contentPanel.add(Box.createVerticalStrut(2))
                }

                else -> {
                    contentPanel.add(createRichTextPane(MarkdownRenderer.blockToHtml(block)))
                    contentPanel.add(Box.createVerticalStrut(2))
                }
            }
        }
    }

    /**
     * 创建配置好 StyleSheet 的富文本渲染器
     */
    private fun createRichTextPane(htmlContent: String): JTextPane {
        val textColor = getBubbleTextColor()
        val textColorHex = String.format("#%06X", textColor.rgb and 0xFFFFFF)
        val inlineCodeBg = if (JBColor.isBright()) "#E8E8E8" else "#3D3D3D"
        val maxWidth = JBUI.scale(600)

        val kit = HTMLEditorKit()
        // 继承默认样式表（保留 <b>, <i>, <h1>~<h6> 等内置标签样式），再叠加自定义规则
        val styleSheet = StyleSheet()
        styleSheet.addStyleSheet(kit.styleSheet)
        // 注意：Swing StyleSheet 只支持极少量 CSS 属性，不支持 border-radius、border-left、font-weight:600 等
        styleSheet.addRule("body { font-family:'Segoe UI',system-ui,sans-serif; font-size:13px; color:$textColorHex; margin:0; padding:0; }")
        styleSheet.addRule("p { margin-top:4px; margin-bottom:4px; }")
        styleSheet.addRule("ul { margin-top:4px; margin-bottom:4px; margin-left:16px; padding-left:8px; }")
        styleSheet.addRule("ol { margin-top:4px; margin-bottom:4px; margin-left:16px; padding-left:8px; }")
        styleSheet.addRule("li { margin-top:2px; margin-bottom:2px; }")
        styleSheet.addRule("code { background-color:$inlineCodeBg; font-family:'JetBrains Mono',monospace; font-size:12px; }")
        styleSheet.addRule("b { font-weight:bold; }")
        styleSheet.addRule("i { font-style:italic; }")
        styleSheet.addRule("h1 { font-size:18px; font-weight:bold; margin-top:14px; margin-bottom:8px; }")
        styleSheet.addRule("h2 { font-size:16px; font-weight:bold; margin-top:12px; margin-bottom:6px; }")
        styleSheet.addRule("h3 { font-size:15px; font-weight:bold; margin-top:10px; margin-bottom:4px; }")
        styleSheet.addRule("h4 { font-size:14px; font-weight:bold; margin-top:8px; margin-bottom:4px; }")
        styleSheet.addRule("h5 { font-size:13px; font-weight:bold; margin-top:6px; margin-bottom:2px; }")
        styleSheet.addRule("h6 { font-size:13px; font-weight:bold; margin-top:6px; margin-bottom:2px; }")
        // blockquote / hr / table 的样式通过 HTML 内联 style 实现（StyleSheet 不支持 border-left、border-top 等）
        kit.styleSheet = styleSheet

        val pane = JTextPane().apply {
            editorKit = kit
            isEditable = false
            isOpaque = false
            putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true)
            border = JBUI.Borders.empty(0)
            text = "<html><body>$htmlContent</body></html>"
        }

        // 关键：在固定宽度下重新计算 preferred size，确保自动换行
        pane.setSize(maxWidth, Int.MAX_VALUE)
        val h = pane.preferredSize.height.coerceAtLeast(JBUI.scale(18))
        pane.preferredSize = Dimension(maxWidth, h)
        pane.maximumSize = Dimension(maxWidth, Int.MAX_VALUE)

        return pane
    }

    private fun getSystemColorHex(): String = when (role) {
        MessageRole.SYSTEM -> if (JBColor.isBright()) "#888888" else "#AAAAAA"
        MessageRole.ERROR -> if (JBColor.isBright()) "#D32F2F" else "#FF8A80"
        MessageRole.TOOL -> if (JBColor.isBright()) "#7B1FA2" else "#CE93D8"
        else -> "#888888"
    }

    fun updateContent(content: String) {
        this.content = content
        contentPanel.removeAll()
        codeBlockComponents.forEach { it.dispose() }
        codeBlockComponents.clear()
        renderContent(content)
        revalidate()
        repaint()
    }

    fun dispose() {
        codeBlockComponents.forEach { it.dispose() }
    }

    override fun paint(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.translate(0, enterOffsetY)
        val oldComposite = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, enterAlpha)
        super.paint(g2)
        g2.composite = oldComposite
        g2.translate(0, -enterOffsetY)
    }

    companion object {
        private fun formatTime(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }
}
