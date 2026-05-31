package com.codesage.ide.ui.components.chat

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * CodeSage 输入面板（增强版）
 *
 * 参考 Cursor Composer / Claude Code 设计：
 * - Agent 模式切换（Ask / Agent / Manual）
 * - 圆角输入框，更好的多行编辑体验
 * - 快捷操作栏（Explain / Refactor / Test / Fix / Doc）
 * - 状态指示器（模型、Token 估算）
 * - 活跃输入：流式输出时输入框保持可编辑，支持排队输入
 */
class InputPanel(
    private val onSend: (String) -> Unit,
    private val onStop: () -> Unit
) : JPanel(BorderLayout()) {

    // ===== Agent 模式 =====
    enum class AgentMode {
        ASK,    // 仅问答，不调用工具
        AGENT,  // 自动规划、调用工具、执行多步任务
        MANUAL  // 每步工具调用需用户确认
    }

    var currentMode: AgentMode = AgentMode.AGENT
        private set

    // ===== UI 组件 =====
    private val modeSelector: ModeSelectorPanel
    private val textArea: JTextArea
    private val sendButton: JLabel
    private val stopButton: JLabel
    private val modelLabel = JLabel("").apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(Color(0x99_99_99), Color(0x66_66_66))
    }
    private val statusLabel = JLabel("就绪").apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(Color(0x99_99_99), Color(0x66_66_66))
    }
    private val tokenLabel = JLabel("").apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(Color(0xBB_BB_BB), Color(0x66_66_66))
    }

    private val shortcutLabel = JLabel("↵ 发送  ·  Shift+↵ 换行  ·  @引用文件").apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(Color(0xBB_BB_BB), Color(0x66_66_66))
    }

    var isProcessing: Boolean = false
        set(value) {
            field = value
            sendButton.isVisible = !value
            stopButton.isVisible = value
            // 增强：流式输出时输入框仍保持可编辑，支持排队输入
            // textArea.isEditable = !value  // 注释掉，保持可编辑
            statusLabel.text = when {
                value && currentMode == AgentMode.AGENT -> "Agent 执行中..."
                value && currentMode == AgentMode.MANUAL -> "等待确认..."
                value -> "思考中..."
                else -> "就绪"
            }
            shortcutLabel.text = if (value) "输入消息将在当前轮次结束后发送" else "↵ 发送  ·  Shift+↵ 换行  ·  @引用文件"
        }

    val inputText: String
        get() = textArea.text.trim()

    init {
        isOpaque = true
        background = JBColor(Color(0xFF_FF_FF), Color(0x1E_1E_1E))
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor(Color(0xE0_E0_E0), Color(0x33_33_33))),
            JBUI.Borders.empty(8)
        )

        // 模式选择器
        modeSelector = ModeSelectorPanel { mode ->
            currentMode = mode
            statusLabel.text = when (mode) {
                AgentMode.ASK -> "问答模式"
                AgentMode.AGENT -> "Agent 模式"
                AgentMode.MANUAL -> "手动模式"
            }
        }

        // 快捷操作栏
        val quickActionBar = QuickActionBar { prompt ->
            textArea.text = prompt
            textArea.requestFocus()
        }

        // 输入框
        textArea = JTextArea().apply {
            font = JBUI.Fonts.label()
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(10)
            // 占位符效果
            foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
        }

        // 发送按钮
        sendButton = JLabel("▶").apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD, 16f)
            foreground = JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))
            cursor = Cursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(8, 10)
            toolTipText = "发送 (Enter)"
        }

        // 停止按钮
        stopButton = JLabel("■").apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD, 14f)
            foreground = JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))
            cursor = Cursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(8, 10)
            toolTipText = "停止"
            isVisible = false
        }

        // 圆角输入区域容器
        val inputContainer = RoundedPanel(
            backgroundColor = JBColor(Color(0xF8_F8_F8), Color(0x25_25_25)),
            cornerRadius = 12,
            borderColor = JBColor(Color(0xD8_D8_D8), Color(0x3D_3D_3D)),
            borderWidth = 1f
        ).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(2)

            // 输入框滚动
            val inputScrollPane = JBScrollPane(textArea).apply {
                preferredSize = Dimension(0, JBUI.scale(60))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(200))
                border = null
                isOpaque = false
                viewport.isOpaque = false
            }

            // 右侧按钮栏
            val buttonBar = JPanel(GridBagLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 4, 0, 4)

                val gbc = GridBagConstraints().apply {
                    gridx = 0
                    gridy = GridBagConstraints.RELATIVE
                    fill = GridBagConstraints.NONE
                    insets = Insets(0, 0, 4, 0)
                }
                add(sendButton, gbc)
                add(stopButton, gbc)
            }

            // 底部栏
            val bottomBar = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 10)

                val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(statusLabel)
                    add(modelLabel)
                    add(tokenLabel)
                }

                add(leftPanel, BorderLayout.WEST)
                add(shortcutLabel, BorderLayout.EAST)
            }

            val centerPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(inputScrollPane, BorderLayout.CENTER)
                add(buttonBar, BorderLayout.EAST)
            }

            add(centerPanel, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }

        // 组装
        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(modeSelector, BorderLayout.WEST)
            add(quickActionBar, BorderLayout.CENTER)
        }

        add(topPanel, BorderLayout.NORTH)
        add(inputContainer, BorderLayout.CENTER)

        // 事件绑定
        sendButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) { doSend() }
        })

        stopButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) { onStop() }
        })

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown) {
                        // Shift+Enter 换行（默认行为）
                    } else {
                        e.consume()
                        doSend()
                    }
                }
            }
        })
    }

    private fun doSend() {
        val text = inputText
        if (text.isEmpty()) return
        onSend(text)
    }

    fun clearInput() {
        textArea.text = ""
    }

    fun setModelLabel(model: String) {
        modelLabel.text = "· $model"
    }

    fun appendText(text: String) {
        textArea.append(text)
        textArea.requestFocus()
    }

    fun setTokenEstimate(tokens: Int) {
        tokenLabel.text = "· ~${tokens} tokens"
    }

    // ========== 模式选择器 ==========

    inner class ModeSelectorPanel(private val onModeChange: (AgentMode) -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {

        private val modeButtons = mutableMapOf<AgentMode, JLabel>()
        private var selectedMode = AgentMode.AGENT

        init {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4, 4, 4)

            AgentMode.values().forEach { mode ->
                val btn = createModeButton(mode)
                modeButtons[mode] = btn
                add(btn)
                add(Box.createHorizontalStrut(4))
            }

            updateSelection()
        }

        private fun createModeButton(mode: AgentMode): JLabel {
            val (label, tooltip) = when (mode) {
                AgentMode.ASK -> "Ask" to "仅问答，不自动调用工具"
                AgentMode.AGENT -> "Agent" to "自动规划、调用工具、执行多步任务"
                AgentMode.MANUAL -> "Manual" to "每步工具调用需手动确认"
            }

            return JLabel(label).apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
                cursor = Cursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2, 8)
                toolTipText = tooltip

                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        selectedMode = mode
                        updateSelection()
                        onModeChange(mode)
                    }

                    override fun mouseEntered(e: MouseEvent?) {
                        if (selectedMode != mode) {
                            background = JBColor(Color(0xF0_F0_F0), Color(0x33_33_33))
                            foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
                        }
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        if (selectedMode != mode) {
                            foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
                        }
                    }
                })
            }
        }

        private fun updateSelection() {
            modeButtons.forEach { (mode, btn) ->
                if (mode == selectedMode) {
                    btn.foreground = JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))
                    btn.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
                    btn.border = BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 2, 0, JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))),
                        JBUI.Borders.empty(2, 8)
                    )
                } else {
                    btn.foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
                    btn.font = JBUI.Fonts.smallFont().deriveFont(Font.PLAIN)
                    btn.border = JBUI.Borders.empty(2, 8)
                }
            }
        }
    }
}

/**
 * 快捷操作栏
 */
class QuickActionBar(private val onAction: (String) -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)) {

    private val actions = listOf(
        "Explain" to "请解释这段代码的含义和作用",
        "Refactor" to "请重构这段代码，使其更简洁、更高效",
        "Test" to "请为这段代码生成单元测试",
        "Fix" to "请分析这段代码中的潜在错误并修复",
        "Doc" to "请为这段代码生成详细的文档注释"
    )

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 12, 0, 12)

        for ((label, prompt) in actions) {
            val btn = createActionButton(label, prompt)
            add(btn)
        }
    }

    private fun createActionButton(text: String, prompt: String): JComponent {
        return JLabel(text).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
            cursor = Cursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(2, 8)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    onAction(prompt)
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
