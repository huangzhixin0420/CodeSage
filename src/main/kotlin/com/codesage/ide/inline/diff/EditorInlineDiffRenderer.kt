package com.codesage.ide.inline.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/**
 * 编辑器行内 Diff 渲染器
 *
 * 使用 IntelliJ [Editor.markupModel] 在代码编辑器中直接渲染 Diff 高亮：
 * - 删除行：红色背景 + 删除线
 * - 新增行：绿色背景
 * - 修改行：黄色背景 + 字符级红绿高亮
 *
 * 所有高亮在 dispose 时自动清除。
 */
class EditorInlineDiffRenderer(private val editor: Editor) : Disposable {

    private val markupModel = editor.markupModel
    private val highlighters = mutableListOf<RangeHighlighter>()
    private var disposed = false

    /**
     * 渲染 Diff 结果
     */
    fun renderDiff(diffResult: DiffResult) {
        if (disposed) return
        clearHighlighters()

        for (line in diffResult.lines) {
            when (line.type) {
                DiffType.REMOVED -> highlightRemoved(line)
                DiffType.ADDED -> highlightAdded(line)
                DiffType.MODIFIED -> highlightModified(line)
                DiffType.CONTEXT -> { /* 上下文行不渲染 */
                }
            }
        }
    }

    /**
     * 高亮删除行
     */
    private fun highlightRemoved(line: DiffLine) {
        if (line.lineNumber < 0 || line.lineNumber >= editor.document.lineCount) return

        val startOffset = editor.document.getLineStartOffset(line.lineNumber)
        val endOffset = editor.document.getLineEndOffset(line.lineNumber)

        val attributes = TextAttributes().apply {
            backgroundColor = REMOVED_BG
            effectType = EffectType.STRIKEOUT
            effectColor = REMOVED_FG
            fontType = Font.ITALIC
        }

        addHighlighter(startOffset, endOffset, attributes, HighlighterTargetArea.LINES_IN_RANGE)
    }

    /**
     * 高亮新增行
     */
    private fun highlightAdded(line: DiffLine) {
        if (line.lineNumber < 0 || line.lineNumber >= editor.document.lineCount) return

        val startOffset = editor.document.getLineStartOffset(line.lineNumber)
        val endOffset = editor.document.getLineEndOffset(line.lineNumber)

        val attributes = TextAttributes().apply {
            backgroundColor = ADDED_BG
            foregroundColor = ADDED_FG
        }

        addHighlighter(startOffset, endOffset, attributes, HighlighterTargetArea.LINES_IN_RANGE)
    }

    /**
     * 高亮修改行（整行黄色 + 字符级红绿）
     */
    private fun highlightModified(line: DiffLine) {
        if (line.lineNumber < 0 || line.lineNumber >= editor.document.lineCount) return

        val lineStart = editor.document.getLineStartOffset(line.lineNumber)
        val lineEnd = editor.document.getLineEndOffset(line.lineNumber)

        // 1. 整行黄色背景
        val lineAttributes = TextAttributes().apply {
            backgroundColor = MODIFIED_BG
        }
        addHighlighter(lineStart, lineEnd, lineAttributes, HighlighterTargetArea.LINES_IN_RANGE)

        // 2. 字符级高亮
        for (charDiff in line.charDiffs) {
            val charStart = lineStart + charDiff.start
            val charEnd = lineStart + charDiff.end

            // 确保范围在有效区间内
            if (charStart < lineStart || charEnd > lineEnd || charStart >= charEnd) continue

            val charAttributes = TextAttributes().apply {
                backgroundColor = if (charDiff.isDeletion) REMOVED_BG else ADDED_BG
                if (charDiff.isDeletion) {
                    effectType = EffectType.STRIKEOUT
                    effectColor = REMOVED_FG
                }
            }
            addHighlighter(charStart, charEnd, charAttributes, HighlighterTargetArea.EXACT_RANGE)
        }
    }

    /**
     * 清除所有高亮
     */
    fun clearHighlighters() {
        for (highlighter in highlighters) {
            if (!highlighter.isValid) continue
            try {
                markupModel.removeHighlighter(highlighter)
            } catch (_: Exception) {
                // 忽略已失效的 highlighter
            }
        }
        highlighters.clear()
    }

    /**
     * 检查是否有活跃的高亮
     */
    fun hasHighlighters(): Boolean = highlighters.isNotEmpty()

    /**
     * 获取高亮数量
     */
    fun highlighterCount(): Int = highlighters.size

    override fun dispose() {
        if (disposed) return
        disposed = true
        clearHighlighters()
    }

    private fun addHighlighter(
        start: Int,
        end: Int,
        attributes: TextAttributes,
        targetArea: HighlighterTargetArea
    ) {
        try {
            val highlighter = markupModel.addRangeHighlighter(
                start.coerceAtLeast(0),
                end.coerceAtMost(editor.document.textLength),
                HighlighterLayer.LAST,
                attributes,
                targetArea
            )
            highlighters.add(highlighter)
        } catch (_: Exception) {
            // 忽略范围错误
        }
    }

    companion object {
        // 亮色主题
        private val REMOVED_BG_LIGHT = Color(0xFF_EB_EE)
        private val ADDED_BG_LIGHT = Color(0xE8_F5_E9)
        private val MODIFIED_BG_LIGHT = Color(0xFF_F8_E1)
        private val REMOVED_FG_LIGHT = Color(0xD3_2F_2F)
        private val ADDED_FG_LIGHT = Color(0x2E_7D_32)

        // 暗色主题
        private val REMOVED_BG_DARK = Color(0x4A_1E_1E)
        private val ADDED_BG_DARK = Color(0x1B_3A_1E)
        private val MODIFIED_BG_DARK = Color(0x3D_30_1B)
        private val REMOVED_FG_DARK = Color(0xFF_8A_80)
        private val ADDED_FG_DARK = Color(0x66_BB_6A)

        val REMOVED_BG = JBColor(REMOVED_BG_LIGHT, REMOVED_BG_DARK)
        val ADDED_BG = JBColor(ADDED_BG_LIGHT, ADDED_BG_DARK)
        val MODIFIED_BG = JBColor(MODIFIED_BG_LIGHT, MODIFIED_BG_DARK)
        val REMOVED_FG = JBColor(REMOVED_FG_LIGHT, REMOVED_FG_DARK)
        val ADDED_FG = JBColor(ADDED_FG_LIGHT, ADDED_FG_DARK)
    }
}
