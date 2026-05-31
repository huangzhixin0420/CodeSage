package com.codesage.ide.ui.components.chat

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.Timer

/**
 * Kimi CLI 风格的可折叠思考面板
 * - 圆角边框，轻量设计
 * - 显示思考计时器
 * - 可展开查看详细状态日志
 * - 完成后自动展开显示总结
 */
class ThinkingIndicator : JPanel(BorderLayout()) {

    private val dotPanel = DotAnimationPanel()
    private val statusLabel = JLabel("思考中...").apply {
        font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 12f)
        foreground = JBColor(Color(0x66_66_66), Color(0xAA_AA_AA))
    }
    private val timerLabel = JLabel("0.0s").apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(Color(0x99_99_99), Color(0x66_66_66))
    }
    private val expandLabel = JLabel("▶").apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
        cursor = Cursor(Cursor.HAND_CURSOR)
    }

    private val detailArea = JTextArea().apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(Color(0x66_66_66), Color(0x99_99_99))
        background = JBColor(Color(0xFA_FA_FA), Color(0x25_25_25))
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 8)
    }

    private val detailPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
        add(detailArea, BorderLayout.CENTER)
    }

    private var isExpanded = false
    private var isCompleted = false
    private var startTime = System.currentTimeMillis()
    private var customStatusSet = false

    private val statusTexts = listOf(
        "思考中...",
        "仍在思考...",
        "深入思考中...",
        "即将完成..."
    )
    private var phase = 0

    private val statusCycleTimer: Timer
    private val elapsedTimer: Timer

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 12)

        val bubble = RoundedPanel(
            backgroundColor = JBColor(Color(0xF5_F5_F5), Color(0x2A_2A_2A)),
            cornerRadius = 8,
            borderColor = JBColor(Color(0xE0_E0_E0), Color(0x3D_3D_3D)),
            borderWidth = 1f
        ).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8, 12)

            // 标题栏
            val headerPanel = JPanel(BorderLayout()).apply {
                isOpaque = false

                val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(dotPanel)
                    add(statusLabel)
                    add(timerLabel)
                }

                val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(expandLabel)
                }

                add(leftPanel, BorderLayout.WEST)
                add(rightPanel, BorderLayout.EAST)

                expandLabel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        toggleExpand()
                    }
                })
            }

            add(headerPanel, BorderLayout.NORTH)
            add(detailPanel, BorderLayout.CENTER)
        }

        add(bubble, BorderLayout.CENTER)

        // 状态循环：每 2 秒切换一次
        statusCycleTimer = Timer(2000) {
            if (!customStatusSet && !isCompleted) {
                phase++
                val text = statusTexts[phase % statusTexts.size]
                statusLabel.text = text
                appendDetail(text)
            }
        }.apply { isRepeats = true; start() }

        // 计时器：每 100ms 更新一次显示
        elapsedTimer = Timer(100) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            timerLabel.text = String.format("%.1fs", elapsed)
        }.apply { isRepeats = true; start() }
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        expandLabel.text = if (isExpanded) "▼" else "▶"
        detailPanel.isVisible = isExpanded
        revalidate()
        repaint()
    }

    private fun appendDetail(text: String) {
        detailArea.append(text + "\n")
    }

    /**
     * 设置自定义状态文本（如"正在搜索代码..."）
     */
    fun setStatusText(text: String) {
        customStatusSet = true
        statusLabel.text = text
        appendDetail(text)
    }

    /**
     * 标记思考完成 — 停止动画、展开面板、显示耗时
     */
    fun markCompleted() {
        if (isCompleted) return
        isCompleted = true
        dotPanel.stop()
        statusCycleTimer.stop()
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        statusLabel.text = "思考完成 · ${String.format("%.1f", elapsed)}s"
        statusLabel.foreground = JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))
        timerLabel.isVisible = false
        if (!isExpanded) {
            toggleExpand()
        }
        revalidate()
        repaint()
    }

    fun stop() {
        statusCycleTimer.stop()
        elapsedTimer.stop()
        dotPanel.stop()
    }

    override fun removeNotify() {
        super.removeNotify()
        stop()
    }

    /**
     * 三点旋转动画
     */
    inner class DotAnimationPanel : JPanel() {
        private var dotOffset = 0
        private var dotTimer: Timer? = null

        init {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(20), JBUI.scale(16))

            dotTimer = Timer(300) {
                dotOffset = (dotOffset + 1) % 4
                repaint()
            }
            dotTimer?.start()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val dotColor = JBColor(Color(0x66_66_66), Color(0xAA_AA_AA))
            val dimColor = JBColor(Color(0xCC_CC_CC), Color(0x55_55_55))

            for (i in 0..2) {
                g2.color = if (i < dotOffset) dotColor else dimColor
                val x = i * 6 + 3
                val y = height / 2
                g2.fillOval(x - 2, y - 2, 4, 4)
            }
        }

        fun stop() {
            dotTimer?.stop()
        }
    }
}
