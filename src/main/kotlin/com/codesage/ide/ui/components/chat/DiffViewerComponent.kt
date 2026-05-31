package com.codesage.ide.ui.components.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Diff 查看器组件
 *
 * 当 Agent 修改代码时，以统一 diff 格式展示变更：
 * - 删除行：红色背景，左侧带 `-` 标记
 * - 新增行：绿色背景，左侧带 `+` 标记
 * - 上下文行：正常背景
 * - 顶部操作栏：接受 / 拒绝 / 复制
 *
 * 参考 Cursor / GitHub PR diff 展示风格
 */
class DiffViewerComponent(
    private val project: Project?,
    private val filePath: String,
    private val oldCode: String,
    private val newCode: String,
    private val onAccept: (() -> Unit)? = null,
    private val onReject: (() -> Unit)? = null
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 0)

        // 顶部标题栏：文件路径 + 操作按钮
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(Color(0xF0_F0_F0), Color(0x2A_2A_2A))
            border = JBUI.Borders.empty(6, 12)

            val fileLabel = JLabel("📄 $filePath").apply {
                font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
                foreground = JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
            }

            val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false

                // 接受按钮
                val acceptBtn = JLabel("✓ 接受").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))),
                        JBUI.Borders.empty(2, 8)
                    )
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            onAccept?.invoke()
                            isVisible = false
                            revalidate()
                            repaint()
                        }

                        override fun mouseEntered(e: MouseEvent?) {
                            background = JBColor(Color(0xE8_F5_E9), Color(0x1B_5E_20))
                        }
                    })
                }

                // 拒绝按钮
                val rejectBtn = JLabel("✗ 拒绝").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))),
                        JBUI.Borders.empty(2, 8)
                    )
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            onReject?.invoke()
                            isVisible = false
                            revalidate()
                            repaint()
                        }
                    })
                }

                // 复制按钮
                val copyBtn = JLabel("📋 复制").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = JBColor(Color(0x88_88_88), Color(0x77_77_77))
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                StringSelection(newCode), null
                            )
                            text = "已复制"
                            Timer(1500) { text = "📋 复制" }.apply { isRepeats = false; start() }
                        }
                    })
                }

                add(acceptBtn)
                add(rejectBtn)
                add(copyBtn)
            }

            add(fileLabel, BorderLayout.WEST)
            add(actionPanel, BorderLayout.EAST)
        }

        // Diff 内容区域
        val diffLines = computeDiff(oldCode, newCode)
        val diffPanel = createDiffContentPanel(diffLines)

        add(headerPanel, BorderLayout.NORTH)
        add(diffPanel, BorderLayout.CENTER)
    }

    private fun createDiffContentPanel(diffLines: List<DiffLine>): JScrollPane {
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = JBColor(Color(0xF8_F8_F8), Color(0x25_25_25))
        }

        for (line in diffLines) {
            val rowPanel = createDiffRow(line)
            contentPanel.add(rowPanel)
        }

        return JScrollPane(contentPanel).apply {
            border = BorderFactory.createMatteBorder(0, 1, 1, 1, JBColor(Color(0xE0_E0_E0), Color(0x3D_3D_3D)))
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            preferredSize = Dimension(0, (diffLines.size * 20 + 20).coerceIn(60, 400))
        }
    }

    private fun createDiffRow(line: DiffLine): JPanel {
        val isDark = !JBColor.isBright()

        val bgColor = when (line.type) {
            DiffType.REMOVED -> JBColor(Color(0xFF_EB_EE), Color(0x4A_1E_1E))
            DiffType.ADDED -> JBColor(Color(0xE8_F5_E9), Color(0x1B_3A_1E))
            DiffType.CONTEXT -> JBColor(Color(0xF8_F8_F8), Color(0x25_25_25))
        }

        val markerColor = when (line.type) {
            DiffType.REMOVED -> JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))
            DiffType.ADDED -> JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))
            DiffType.CONTEXT -> JBColor(Color(0x99_99_99), Color(0x66_66_66))
        }

        val marker = when (line.type) {
            DiffType.REMOVED -> "-"
            DiffType.ADDED -> "+"
            DiffType.CONTEXT -> " "
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = bgColor
            border = JBUI.Borders.empty(1, 0)
            maximumSize = Dimension(Int.MAX_VALUE, 20)

            val markerLabel = JLabel(marker).apply {
                font = JBUI.Fonts.create("JetBrains Mono", 12)
                foreground = markerColor
                preferredSize = Dimension(20, 18)
                horizontalAlignment = SwingConstants.CENTER
            }

            val codeLabel = JLabel("<html><body style='font-family:JetBrains Mono,monospace;font-size:12px;'>${escapeHtml(line.content)}</body></html>").apply {
                font = JBUI.Fonts.create("JetBrains Mono", 12)
                foreground = when (line.type) {
                    DiffType.REMOVED -> JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))
                    DiffType.ADDED -> JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))
                    DiffType.CONTEXT -> JBColor(Color(0x33_33_33), Color(0xCC_CC_CC))
                }
            }

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(markerLabel)
            }

            add(leftPanel, BorderLayout.WEST)
            add(codeLabel, BorderLayout.CENTER)
        }
    }

    // ========== 简单 Diff 算法 ==========

    private fun computeDiff(oldCode: String, newCode: String): List<DiffLine> {
        val oldLines = oldCode.lines()
        val newLines = newCode.lines()
        val result = mutableListOf<DiffLine>()

        // 使用简单的 LCS（最长公共子序列）算法
        val lcs = computeLCS(oldLines, newLines)

        var oldIdx = 0
        var newIdx = 0

        for (commonLine in lcs) {
            // 输出 old 中在 commonLine 之前的删除行
            while (oldIdx < oldLines.size && oldLines[oldIdx] != commonLine) {
                result.add(DiffLine(DiffType.REMOVED, oldLines[oldIdx]))
                oldIdx++
            }

            // 输出 new 中在 commonLine 之前的增加行
            while (newIdx < newLines.size && newLines[newIdx] != commonLine) {
                result.add(DiffLine(DiffType.ADDED, newLines[newIdx]))
                newIdx++
            }

            // 输出公共行
            if (oldIdx < oldLines.size && newIdx < newLines.size) {
                result.add(DiffLine(DiffType.CONTEXT, commonLine))
                oldIdx++
                newIdx++
            }
        }

        // 剩余的删除行
        while (oldIdx < oldLines.size) {
            result.add(DiffLine(DiffType.REMOVED, oldLines[oldIdx]))
            oldIdx++
        }

        // 剩余的增加行
        while (newIdx < newLines.size) {
            result.add(DiffLine(DiffType.ADDED, newLines[newIdx]))
            newIdx++
        }

        return result
    }

    private fun computeLCS(a: List<String>, b: List<String>): List<String> {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // 回溯
        val lcs = mutableListOf<String>()
        var i = m
        var j = n
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] -> {
                    lcs.add(0, a[i - 1])
                    i--
                    j--
                }

                dp[i - 1][j] > dp[i][j - 1] -> i--
                else -> j--
            }
        }

        return lcs
    }

    // ========== 数据类 ==========

    private enum class DiffType {
        REMOVED, ADDED, CONTEXT
    }

    private data class DiffLine(
        val type: DiffType,
        val content: String
    )

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
