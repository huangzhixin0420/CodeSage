package com.codesage.ide.ui.components.chat

import com.codesage.agent.core.AgentSession
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

/**
 * Claude Code 风格的会话侧边栏面板
 * - 简洁扁平设计
 * - 无 emoji，纯文字
 */
class SessionSidebarPanel(
    private val onSessionSelected: (String) -> Unit,
    private val onNewSession: () -> Unit,
    private val onRenameSession: (String, String) -> Unit,
    private val onDeleteSession: (String) -> Unit
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<SessionItem>()
    private val sessionList = JBList(listModel)

    data class SessionItem(
        val id: String,
        var name: String,
        val createdAt: Long,
        var lastActivityAt: Long
    ) {
        override fun toString(): String = name.ifEmpty {
            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            "New Session ${sdf.format(java.util.Date(createdAt))}"
        }
    }

    init {
        preferredSize = Dimension(JBUI.scale(200), 0)
        background = JBColor(Color(0xF8_F8_F8), Color(0x22_22_22))
        isOpaque = true
        border = BorderFactory.createMatteBorder(0, 0, 0, 1, JBColor(Color(0xE0_E0_E0), Color(0x33_33_33)))

        // 新建会话按钮
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10)

            val titleLabel = JLabel("Sessions").apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
            }

            val newBtn = JLabel("+ New").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))
                cursor = Cursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        onNewSession()
                    }
                })
            }

            add(titleLabel, BorderLayout.WEST)
            add(newBtn, BorderLayout.EAST)
        }

        // 会话列表
        sessionList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SessionCellRenderer()
            background = JBColor(Color(0xF8_F8_F8), Color(0x22_22_22))
            addListSelectionListener {
                val selected = selectedValue
                if (selected != null && !it.valueIsAdjusting) {
                    onSessionSelected(selected.id)
                }
            }

            // 右键菜单
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        val index = locationToIndex(e.point)
                        if (index >= 0) {
                            selectedIndex = index
                            showContextMenu(e, listModel.getElementAt(index))
                        }
                    }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val index = locationToIndex(e.point)
                        if (index >= 0) {
                            startRename(listModel.getElementAt(index), index)
                        }
                    }
                }
            })
        }

        val scrollPane = JBScrollPane(sessionList).apply {
            border = null
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(headerPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    fun setSessions(sessions: List<AgentSession>) {
        listModel.clear()
        sessions.forEach { session ->
            listModel.addElement(
                SessionItem(
                    id = session.id,
                    name = session.name,
                    createdAt = session.createdAt,
                    lastActivityAt = session.lastActivityAt
                )
            )
        }
    }

    fun addSession(session: AgentSession) {
        listModel.addElement(
            SessionItem(
                id = session.id,
                name = session.name,
                createdAt = session.createdAt,
                lastActivityAt = session.lastActivityAt
            )
        )
        sessionList.selectedIndex = listModel.size - 1
    }

    fun selectSession(sessionId: String) {
        for (i in 0 until listModel.size()) {
            if (listModel.getElementAt(i).id == sessionId) {
                sessionList.selectedIndex = i
                return
            }
        }
    }

    fun updateSession(session: AgentSession) {
        for (i in 0 until listModel.size()) {
            val item = listModel.getElementAt(i)
            if (item.id == session.id) {
                item.name = session.name
                item.lastActivityAt = session.lastActivityAt
                listModel.setElementAt(item, i)
                return
            }
        }
    }

    fun removeSession(sessionId: String) {
        for (i in 0 until listModel.size()) {
            if (listModel.getElementAt(i).id == sessionId) {
                listModel.remove(i)
                return
            }
        }
    }

    private fun showContextMenu(e: MouseEvent, item: SessionItem) {
        val popup = JPopupMenu()

        val renameItem = JMenuItem("Rename").apply {
            addActionListener {
                val index = listModel.indexOf(item)
                if (index >= 0) startRename(item, index)
            }
        }

        val deleteItem = JMenuItem("Delete").apply {
            addActionListener {
                val confirm = JOptionPane.showConfirmDialog(
                    this@SessionSidebarPanel,
                    "确定要删除会话 \"${item}\" 吗？",
                    "删除会话",
                    JOptionPane.YES_NO_OPTION
                )
                if (confirm == JOptionPane.YES_OPTION) {
                    onDeleteSession(item.id)
                }
            }
        }

        popup.add(renameItem)
        popup.add(deleteItem)
        popup.show(e.component, e.x, e.y)
    }

    private fun startRename(item: SessionItem, index: Int) {
        // Use JOptionPane for reliable inline renaming instead of custom renderer
        val newName = JOptionPane.showInputDialog(
            this,
            "Rename session:",
            item.name
        )
        if (newName != null && newName.trim().isNotEmpty() && newName.trim() != item.name) {
            onRenameSession(item.id, newName.trim())
        }
    }
}

/**
 * 会话列表单元格渲染器
 */
private class SessionCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val item = value as? SessionSidebarPanel.SessionItem

        if (item != null) {
            text = item.toString()
            font = JBUI.Fonts.label()
            iconTextGap = 8
            border = JBUI.Borders.empty(8, 12)

            if (!isSelected) {
                background = JBColor(Color(0xF8_F8_F8), Color(0x22_22_22))
                foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
            }
        }
        return component
    }
}

/**
 * 编辑状态下的单元格渲染器
 */
private class EditingSessionCellRenderer(private val editor: JTextField) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        return editor
    }
}
