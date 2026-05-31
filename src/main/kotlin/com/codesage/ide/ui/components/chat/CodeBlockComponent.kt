package com.codesage.ide.ui.components.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import com.intellij.ui.components.JBScrollPane
import javax.swing.*

/**
 * 增强版代码块组件
 * - 顶部标题栏：语言标签 + Copy 📋 / Insert 📥 图标按钮
 * - 左侧行号 + 中部代码显示区域
 * - 圆角边框，主题适配
 */
class CodeBlockComponent(
    private val project: Project?,
    language: String,
    code: String
) : JPanel(BorderLayout()) {

    var codeText: String = code
        private set

    private val copyLabel = createIconLabel("📋", "Copy")
    private val insertLabel = createIconLabel("📥", "Insert")

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 0)

        // 标题栏
        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(Color(0xF0_F0_F0), Color(0x2A_2A_2A))
            border = JBUI.Borders.empty(6, 12)

            val langLabel = JBLabel(language.ifEmpty { "text" }).apply {
                font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
                foreground = JBColor(Color(0x66_66_66), Color(0xAA_AA_AA))
            }
            add(langLabel, BorderLayout.WEST)

            val actionPanel = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)

                copyLabel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        copyToClipboard(code)
                    }

                    override fun mouseEntered(e: MouseEvent?) {
                        copyLabel.foreground = JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        copyLabel.foreground = JBColor(Color(0x88_88_88), Color(0x88_88_88))
                    }
                })

                insertLabel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        insertToEditor(code)
                    }

                    override fun mouseEntered(e: MouseEvent?) {
                        insertLabel.foreground = JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        insertLabel.foreground = JBColor(Color(0x88_88_88), Color(0x88_88_88))
                    }
                })

                add(copyLabel)
                add(Box.createHorizontalStrut(12))
                add(insertLabel)
            }
            add(actionPanel, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)

        // 代码区域：行号 + 代码
        val codeBody = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(Color(0xF8_F8_F8), Color(0x25_25_25))
            border = BorderFactory.createMatteBorder(0, 1, 1, 1, JBColor(Color(0xE0_E0_E0), Color(0x3D_3D_3D)))

            val lines = code.split("\n")
            val lineCount = lines.size

            // 行号区域
            val lineNumbersText = (1..lineCount).joinToString("\n") { "$it" }
            val lineNumberArea = JTextArea(lineNumbersText).apply {
                isEditable = false
                font = JBUI.Fonts.create("JetBrains Mono", 12)
                background = JBColor(Color(0xF0_F0_F0), Color(0x22_22_22))
                foreground = JBColor(Color(0x99_99_99), Color(0x66_66_66))
                border = JBUI.Borders.empty(10, 8, 10, 4)
                lineWrap = false
                tabSize = 4
                preferredSize = Dimension(
                    getFontMetrics(font).stringWidth("$lineCount") + 16,
                    preferredSize.height
                )
            }

            // 代码区域
            val codeArea = JTextArea(code).apply {
                isEditable = false
                font = JBUI.Fonts.create("JetBrains Mono", 12)
                background = JBColor(Color(0xF8_F8_F8), Color(0x25_25_25))
                foreground = JBColor(Color(0x22_22_22), Color(0xCC_CC_CC))
                border = JBUI.Borders.empty(10, 8)
                lineWrap = false
                tabSize = 4
            }

            val codeScrollPane = JBScrollPane(codeArea).apply {
                border = null
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            }

            // 同步行号区域和代码区域的滚动
            val lineNumberScrollPane = JBScrollPane(lineNumberArea).apply {
                border = null
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            }

            // 将两个区域放入一个面板
            val splitPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(lineNumberScrollPane, BorderLayout.WEST)
                add(codeScrollPane, BorderLayout.CENTER)
            }

            add(splitPanel, BorderLayout.CENTER)
            preferredSize = Dimension(0, codeArea.preferredSize.height.coerceIn(40, 400))
        }
        add(codeBody, BorderLayout.CENTER)
    }

    private fun createIconLabel(icon: String, tooltip: String): JBLabel {
        return JBLabel(icon).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor(Color(0x88_88_88), Color(0x88_88_88))
            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = tooltip
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        copyLabel.text = "✅"
        copyLabel.toolTipText = "Copied!"
        copyLabel.foreground = JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))

        javax.swing.Timer(1500) {
            copyLabel.text = "📋"
            copyLabel.toolTipText = "Copy"
            copyLabel.foreground = JBColor(Color(0x88_88_88), Color(0x88_88_88))
        }.apply { isRepeats = false; start() }
    }

    private fun insertToEditor(code: String) {
        project ?: return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
            editor?.document?.let { doc ->
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    val offset = editor.caretModel.offset
                    doc.insertString(offset, code)
                }
            }
        }
    }

    fun dispose() {
        // nothing to dispose
    }
}
