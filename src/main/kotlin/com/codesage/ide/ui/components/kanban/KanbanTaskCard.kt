package com.codesage.ide.ui.components.kanban

import com.codesage.agent.multiagent.KanbanStatus
import com.codesage.agent.multiagent.KanbanTask
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Kanban 任务卡片
 *
 * 显示单个任务的描述、状态和操作按钮
 */
class KanbanTaskCard(
    private val task: KanbanTask,
    private val onStatusChange: (KanbanStatus) -> Unit = {},
    private val onClick: () -> Unit = {}
) : JPanel(BorderLayout()) {

    init {
        isOpaque = true
        background = JBColor(Color(0xFF_FF_FF), Color(0x2D_2D_2D))
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                when (task.status) {
                    KanbanStatus.BLOCKED -> JBColor(Color(0xFF_44_44), Color(0xCC_33_33))
                    KanbanStatus.DONE -> JBColor(Color(0x00_CC_00), Color(0x33_CC_33))
                    else -> JBColor(Color(0xE0_E0_E0), Color(0x44_44_44))
                },
                1
            ),
            JBUI.Borders.empty(8, 10)
        )
        preferredSize = Dimension(220, 80)
        maximumSize = Dimension(Int.MAX_VALUE, 120)

        // ID 标签
        val idLabel = JLabel("#${task.id.takeLast(6)}").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
        }

        // 描述标签
        val descLabel = JLabel("<html>${task.description.take(100)}</html>").apply {
            font = JBUI.Fonts.label()
            foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
        }

        // 工具集标签
        val toolsetLabel = JLabel(task.toolset.uppercase()).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))
            isOpaque = true
            background = JBColor(Color(0xE6_F0_FF), Color(0x1A_3A_5C))
            border = JBUI.Borders.empty(2, 6)
        }

        // 状态操作区
        val actionPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)

            when (task.status) {
                KanbanStatus.BACKLOG -> {
                    add(makeActionButton("▶ Start") { onStatusChange(KanbanStatus.IN_PROGRESS) })
                }

                KanbanStatus.IN_PROGRESS -> {
                    add(makeActionButton("✓ Done") { onStatusChange(KanbanStatus.DONE) })
                    add(Box.createHorizontalStrut(4))
                    add(makeActionButton("⚠ Block") { onStatusChange(KanbanStatus.BLOCKED) })
                }

                KanbanStatus.BLOCKED -> {
                    add(makeActionButton("▶ Resume") { onStatusChange(KanbanStatus.IN_PROGRESS) })
                }

                KanbanStatus.REVIEW -> {
                    add(makeActionButton("✓ Approve") { onStatusChange(KanbanStatus.DONE) })
                }

                KanbanStatus.DONE -> {
                    add(JLabel("✅ Completed").apply {
                        font = JBUI.Fonts.smallFont()
                        foreground = JBColor(Color(0x00_88_00), Color(0x4C_FF_4C))
                    })
                }
            }
        }

        // 元信息区
        val metaPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(idLabel, BorderLayout.WEST)
            add(toolsetLabel, BorderLayout.EAST)
        }

        // 内容区
        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            add(descLabel, BorderLayout.CENTER)
        }

        // 底部操作区
        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(actionPanel, BorderLayout.EAST)
        }

        // 结果摘要（如果有）
        if (task.result != null) {
            val resultLabel = JLabel("<html><i>${task.result.take(60)}...</i></html>").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor(Color(0x66_66_66), Color(0x99_99_99))
            }
            contentPanel.add(resultLabel, BorderLayout.SOUTH)
        }

        // 阻塞提示（如果有）
        if (task.blocker != null) {
            val blockerLabel = JLabel("⚠ ${task.blocker}").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor(Color(0xCC_33_33), Color(0xFF_66_66))
            }
            contentPanel.add(blockerLabel, BorderLayout.SOUTH)
        }

        add(metaPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        // 点击事件
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                onClick()
            }
        })
    }

    private fun makeActionButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = JBUI.Fonts.smallFont()
            isFocusPainted = false
            isOpaque = false
            setContentAreaFilled(false)
            border = JBUI.Borders.empty(2, 6)
            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { action() }
        }
    }
}
