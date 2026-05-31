package com.codesage.ide.inline.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * Inline Chat 输入面板
 *
 * 浮动在编辑器中的 Swing 面板，包含：
 * - 快捷操作栏（Explain / Refactor / Fix / Test）
 * - 多行输入框
 * - 发送按钮
 * - 模型选择下拉框
 */
class InlineChatInputPanel(
    private val onSend: (String) -> Unit,
    private val onClose: () -> Unit
) : JPanel(BorderLayout()) {

    private val quickActions = listOf("Explain", "Refactor", "Fix", "Test")
    private var actionClickListeners = mutableListOf<(String) -> Unit>()

    private val inputArea: JBTextArea
    private val sendButton: JButton
    private val statusLabel: JLabel

    /** 当前是否正在处理请求 */
    var isProcessing: Boolean = false
        set(value) {
            field = value
            sendButton.isEnabled = !value
            inputArea.isEnabled = !value
            statusLabel.text = if (value) "思考中..." else ""
        }

    init {
        isOpaque = true
        background = JBColor(PANEL_BG_LIGHT, PANEL_BG_DARK)
        border = JBUI.Borders.empty(8)

        // 顶部：快捷操作栏
        val topPanel = createQuickActionPanel()

        // 中部：输入框
        inputArea = createInputArea()
        val inputScrollPane = JScrollPane(inputArea).apply {
            border = BorderFactory.createLineBorder(JBColor(BORDER_LIGHT, BORDER_DARK))
            preferredSize = Dimension(0, 80)
        }

        // 底部：状态栏 + 发送按钮
        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false

            statusLabel = JLabel("").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor(Color.GRAY, Color.GRAY)
            }

            sendButton = JButton("发送").apply {
                font = JBUI.Fonts.label()
                addActionListener { doSend() }
            }

            add(statusLabel, BorderLayout.WEST)
            add(sendButton, BorderLayout.EAST)
        }

        // 组装
        add(topPanel, BorderLayout.NORTH)
        add(inputScrollPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    /**
     * 获取输入框内容
     */
    fun getInputText(): String = inputArea.text.trim()

    /**
     * 设置输入框内容
     */
    fun setInputText(text: String) {
        inputArea.text = text
        inputArea.caretPosition = text.length
    }

    /**
     * 清空输入框
     */
    fun clearInput() {
        inputArea.text = ""
    }

    /**
     * 添加快捷操作点击监听
     */
    fun addActionClickListener(listener: (String) -> Unit) {
        actionClickListeners.add(listener)
    }

    /**
     * 聚焦输入框
     */
    fun focusInput() {
        inputArea.requestFocusInWindow()
    }

    private fun createQuickActionPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false

            quickActions.forEach { action ->
                val btn = JButton(action).apply {
                    font = JBUI.Fonts.smallFont()
                    isFocusable = false
                    addActionListener {
                        actionClickListeners.forEach { it(action) }
                    }
                }
                add(btn)
            }
        }
    }

    private fun createInputArea(): JBTextArea {
        return JBTextArea().apply {
            font = JBUI.Fonts.create(Font.MONOSPACED, 13)
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(4)

            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                        e.consume()
                        doSend()
                    }
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        e.consume()
                        onClose()
                    }
                }
            })
        }
    }

    private fun doSend() {
        val text = getInputText()
        if (text.isNotBlank() && !isProcessing) {
            onSend(text)
        }
    }

    companion object {
        private val PANEL_BG_LIGHT = Color(0xF5_F5_F5)
        private val PANEL_BG_DARK = Color(0x2D_2D_2D)
        private val BORDER_LIGHT = Color(0xCC_CC_CC)
        private val BORDER_DARK = Color(0x55_55_55)
    }
}
