package com.codesage.ide.ui.components.chat

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.*

/**
 * 子 Agent 进度面板
 *
 * 显示子 Agent 的执行状态：任务描述、工具集、进度日志、最终结果
 */
class SubAgentProgressPanel(
    private val sessionId: String,
    private val taskDescription: String,
    private val toolset: String
) : JPanel(BorderLayout()) {

    enum class Status {
        RUNNING, COMPLETED, FAILED
    }

    private val statusLabel = JLabel("🔄 Running").apply {
        font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
        foreground = JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))
    }

    private val taskLabel = JLabel("<html><b>${taskDescription.take(80)}</b></html>").apply {
        font = JBUI.Fonts.label()
        foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
    }

    private val metaLabel = JLabel("Toolset: $toolset | Session: ${sessionId.takeLast(12)}").apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
    }

    private val progressArea = JTextArea(4, 40).apply {
        isEditable = false
        font = JBUI.Fonts.create("JetBrains Mono", 11)
        lineWrap = true
        wrapStyleWord = true
        background = JBColor(Color(0xF5_F5_F5), Color(0x2B_2B_2B))
        border = JBUI.Borders.empty(4)
    }

    private var currentStatus = Status.RUNNING

    init {
        isOpaque = true
        background = JBColor(Color(0xFA_FA_FA), Color(0x25_25_25))
        border = JBUI.Borders.empty(8, 12)

        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.WEST)
            add(metaLabel, BorderLayout.EAST)
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            add(taskLabel, BorderLayout.NORTH)
            add(JBScrollPane(progressArea).apply {
                border = BorderFactory.createLineBorder(JBColor(Color(0xE0_E0_E0), Color(0x33_33_33)))
                preferredSize = Dimension(0, 80)
            }, BorderLayout.CENTER)
        }

        add(headerPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun appendProgress(message: String) {
        SwingUtilities.invokeLater {
            progressArea.append("$message\n")
            progressArea.caretPosition = progressArea.document.length
        }
    }

    fun markComplete(success: Boolean, output: String) {
        SwingUtilities.invokeLater {
            currentStatus = if (success) Status.COMPLETED else Status.FAILED
            statusLabel.text = if (success) "✅ Completed" else "❌ Failed"
            statusLabel.foreground = if (success)
                JBColor(Color(0x00_88_00), Color(0x4C_FF_4C))
            else
                JBColor(Color(0xCC_00_00), Color(0xFF_4C_4C))

            if (output.isNotBlank()) {
                progressArea.append("\n--- Result ---\n${output.take(500)}\n")
            }
            revalidate()
            repaint()
        }
    }

    fun getStatus(): Status = currentStatus
}
