package com.codesage.ide.ui.components.chat

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Claude Code 风格的工具调用折叠面板
 * - 默认折叠为一行摘要
 * - 点击展开查看完整输出
 * - 支持嵌套工具调用
 */
class ToolCallPanel(
    private val toolName: String,
    toolStatus: ToolStatus,
    private val summary: String,
    details: String? = null
) : JPanel(BorderLayout()) {

    enum class ToolStatus {
        RUNNING, COMPLETED, FAILED
    }

    private var expanded = false
    private val contentPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
    }
    private var iconLabel: JLabel
    private var textLabel: JLabel
    private var expandLabel: JLabel
    private var summaryPanel: JPanel

    init {
        isOpaque = true
        background = JBColor(Color(0xF7_F7_F7), Color(0x25_25_25))
        border = JBUI.Borders.empty(0)

        // 摘要行（可点击折叠/展开）
        summaryPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 16)
            cursor = Cursor(Cursor.HAND_CURSOR)

            iconLabel = JLabel(getStatusIcon(toolStatus)).apply {
                font = JBUI.Fonts.label()
                foreground = getStatusColor(toolStatus)
            }

            textLabel = JLabel("$toolName: $summary").apply {
                font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 12f)
                foreground = JBColor(Color(0x55_55_55), Color(0xAA_AA_AA))
            }

            expandLabel = JLabel("▶").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor.GRAY
            }

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(iconLabel)
                add(textLabel)
            }

            add(leftPanel, BorderLayout.WEST)
            add(expandLabel, BorderLayout.EAST)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    expanded = !expanded
                    expandLabel.text = if (expanded) "▼" else "▶"
                    contentPanel.isVisible = expanded
                    revalidate()
                    repaint()
                }

                override fun mouseEntered(e: MouseEvent?) {
                    background = JBColor(Color(0xEF_EF_EF), Color(0x2D_2D_2D))
                }

                override fun mouseExited(e: MouseEvent?) {
                    background = JBColor(Color(0xF7_F7_F7), Color(0x25_25_25))
                }
            })
        }

        // 详情内容
        details?.let {
            val detailArea = JTextArea(it).apply {
                isEditable = false
                font = JBUI.Fonts.create("JetBrains Mono", 11)
                background = JBColor(Color(0xFA_FA_FA), Color(0x1E_1E_1E))
                foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
                border = JBUI.Borders.empty(8, 16)
                lineWrap = true
                wrapStyleWord = true
            }
            contentPanel.add(detailArea, BorderLayout.CENTER)
        }

        add(summaryPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun updateStatus(newStatus: ToolStatus, newDetails: String? = null) {
        iconLabel.text = getStatusIcon(newStatus)
        iconLabel.foreground = getStatusColor(newStatus)

        newDetails?.let {
            contentPanel.removeAll()
            val detailArea = JTextArea(it).apply {
                isEditable = false
                font = JBUI.Fonts.create("JetBrains Mono", 11)
                background = JBColor(Color(0xFA_FA_FA), Color(0x1E_1E_1E))
                foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
                border = JBUI.Borders.empty(8, 16)
                lineWrap = true
                wrapStyleWord = true
            }
            contentPanel.add(detailArea, BorderLayout.CENTER)
        }

        revalidate()
        repaint()
    }

    fun updateSummary(summary: String) {
        textLabel.text = "$toolName: $summary"
        revalidate()
        repaint()
    }

    private fun getStatusIcon(status: ToolStatus): String = when (status) {
        ToolStatus.RUNNING -> "◐"
        ToolStatus.COMPLETED -> "✓"
        ToolStatus.FAILED -> "✗"
    }

    private fun getStatusColor(status: ToolStatus): Color = when (status) {
        ToolStatus.RUNNING -> JBColor(Color(0xED_6C_02), Color(0xFF_A0_00))
        ToolStatus.COMPLETED -> JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))
        ToolStatus.FAILED -> JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))
    }
}
