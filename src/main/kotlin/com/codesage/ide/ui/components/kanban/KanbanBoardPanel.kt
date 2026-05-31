package com.codesage.ide.ui.components.kanban

import com.codesage.agent.multiagent.KanbanOrchestrator
import com.codesage.agent.multiagent.KanbanStatus
import com.codesage.agent.multiagent.KanbanTask
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * Kanban 看板面板
 *
 * 三列布局：BACKLOG / IN_PROGRESS / DONE
 * 支持任务状态切换、看板渲染、操作按钮
 */
class KanbanBoardPanel(
    private var orchestrator: KanbanOrchestrator?
) : JPanel(BorderLayout()) {

    private val columnPanels = mutableMapOf<KanbanStatus, JPanel>()
    private val countLabels = mutableMapOf<KanbanStatus, JLabel>()

    private val toolbarPanel = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = JBColor(Color(0xF5_F5_F5), Color(0x2B_2B_2B))
        border = JBUI.Borders.empty(8, 12)

        val titleLabel = JLabel("📋 Kanban Board").apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD, 14f)
            foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
        }

        val actionPanel = JPanel().apply {
            isOpaque = false
            add(JButton("➕ New Task").apply {
                font = JBUI.Fonts.label()
                addActionListener { showNewTaskDialog() }
            })
            add(JButton("🔄 Refresh").apply {
                font = JBUI.Fonts.label()
                addActionListener { refreshBoard() }
            })
            add(JButton("🧹 Clear Done").apply {
                font = JBUI.Fonts.label()
                addActionListener { clearDoneTasks() }
            })
        }

        add(titleLabel, BorderLayout.WEST)
        add(actionPanel, BorderLayout.EAST)
    }

    init {
        isOpaque = true
        background = JBColor(Color(0xFA_FA_FA), Color(0x1E_1E_1E))

        add(toolbarPanel, BorderLayout.NORTH)
        add(createColumnsPanel(), BorderLayout.CENTER)
        refreshBoard()
    }

    private fun createColumnsPanel(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8)
        }

        KanbanStatus.values().forEach { status ->
            if (status == KanbanStatus.REVIEW) return@forEach // REVIEW 列暂时不显示

            val columnPanel = createColumn(status)
            columnPanels[status] = columnPanel
            panel.add(columnPanel)
            panel.add(Box.createHorizontalStrut(8))
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBScrollPane(panel).apply {
                border = null
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
    }

    private fun createColumn(status: KanbanStatus): JPanel {
        val headerColor = when (status) {
            KanbanStatus.BACKLOG -> JBColor(Color(0xE8_E8_E8), Color(0x3D_3D_3D))
            KanbanStatus.IN_PROGRESS -> JBColor(Color(0xFF_F0_C0), Color(0x5C_4A_1A))
            KanbanStatus.BLOCKED -> JBColor(Color(0xFF_D0_D0), Color(0x5C_1A_1A))
            KanbanStatus.DONE -> JBColor(Color(0xD0_FF_D0), Color(0x1A_5C_1A))
            else -> JBColor(Color(0xE8_E8_E8), Color(0x3D_3D_3D))
        }

        val title = when (status) {
            KanbanStatus.BACKLOG -> "📥 Backlog"
            KanbanStatus.IN_PROGRESS -> "🔨 In Progress"
            KanbanStatus.BLOCKED -> "🚫 Blocked"
            KanbanStatus.DONE -> "✅ Done"
            else -> status.name
        }

        val countLabel = JLabel("(0)").apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD)
            foreground = JBColor(Color(0x66_66_66), Color(0x99_99_99))
        }
        countLabels[status] = countLabel

        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = headerColor
            border = JBUI.Borders.empty(8, 12)
            add(JLabel(title).apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
                foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
            }, BorderLayout.WEST)
            add(countLabel, BorderLayout.EAST)
        }

        val cardsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = JBColor(Color(0xF0_F0_F0), Color(0x28_28_28))
            border = JBUI.Borders.empty(6)
        }

        val scrollPane = JBScrollPane(cardsPanel).apply {
            border = BorderFactory.createLineBorder(JBColor(Color(0xD0_D0_D0), Color(0x44_44_44)))
            preferredSize = Dimension(260, 0)
            verticalScrollBar.unitIncrement = 16
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(280, 0)
            add(headerPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    fun setOrchestrator(newOrchestrator: KanbanOrchestrator) {
        this.orchestrator = newOrchestrator
        refreshBoard()
    }

    fun refreshBoard() {
        val orchestrator = this.orchestrator ?: return
        val allTasks = orchestrator.getAllTasks()

        // 清空各列
        columnPanels.forEach { (_, panel) ->
            val scrollPane = panel.getComponent(1) as JBScrollPane
            val cardsPanel = scrollPane.viewport.view as JPanel
            cardsPanel.removeAll()
        }

        // 按状态分组并填充
        val grouped = allTasks.groupBy { it.status }

        grouped.forEach { (status, tasks) ->
            val columnPanel = columnPanels[status] ?: return@forEach
            val scrollPane = columnPanel.getComponent(1) as JBScrollPane
            val cardsPanel = scrollPane.viewport.view as JPanel

            countLabels[status]?.text = "(${tasks.size})"

            tasks.forEach { task ->
                val card = KanbanTaskCard(
                    task = task,
                    onStatusChange = { newStatus ->
                        this.orchestrator?.updateTaskStatus(task.id, newStatus)
                        refreshBoard()
                    },
                    onClick = {
                        JOptionPane.showMessageDialog(
                            this,
                            "Task: ${task.description}\nStatus: ${task.status}\nToolset: ${task.toolset}",
                            "Task Details",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                )
                cardsPanel.add(card)
                cardsPanel.add(Box.createVerticalStrut(6))
            }

            cardsPanel.revalidate()
            cardsPanel.repaint()
        }

        revalidate()
        repaint()
    }

    private fun showNewTaskDialog() {
        val description = JOptionPane.showInputDialog(
            this,
            "Enter task description:",
            "New Kanban Task",
            JOptionPane.QUESTION_MESSAGE
        )

        if (!description.isNullOrBlank()) {
            val toolsets = arrayOf("dev", "research", "test", "browser")
            val toolset = JOptionPane.showInputDialog(
                this,
                "Select toolset:",
                "Task Toolset",
                JOptionPane.QUESTION_MESSAGE,
                null,
                toolsets,
                "dev"
            ) as? String ?: "dev"

            this.orchestrator?.createTask(description, toolset)
            refreshBoard()
        }
    }

    private fun clearDoneTasks() {
        val doneTasks = this.orchestrator?.getDone() ?: return
        if (doneTasks.isEmpty()) return

        val confirm = JOptionPane.showConfirmDialog(
            this,
            "Clear ${doneTasks.size} completed tasks?",
            "Clear Done",
            JOptionPane.YES_NO_OPTION
        )

        if (confirm == JOptionPane.YES_OPTION) {
            doneTasks.forEach { task ->
                // 从 orchestrator 中移除（需要添加移除方法）
                // 暂时只刷新
            }
            refreshBoard()
        }
    }
}
