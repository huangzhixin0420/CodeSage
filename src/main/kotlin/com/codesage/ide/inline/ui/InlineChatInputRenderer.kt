package com.codesage.ide.inline.ui

import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Graphics
import java.awt.Rectangle

/**
 * Inline Chat 输入面板 Inlay 渲染器
 *
 * 将 [InlineChatInputPanel] 渲染到编辑器行间。
 */
class InlineChatInputRenderer(
    private val panel: InlineChatInputPanel
) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        // 占满编辑器宽度减去左右边距
        return (inlay.editor.contentComponent.width - 40).coerceAtLeast(200)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val width = calcWidthInPixels(inlay)
        // 让面板在目标宽度下重新布局，以获取正确的高度（处理 JTextArea 换行等）
        panel.setSize(width, Short.MAX_VALUE.toInt())
        panel.doLayout()
        return panel.preferredSize.height.coerceAtLeast(120)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        panel.bounds = targetRegion
        panel.doLayout()
        g.translate(targetRegion.x, targetRegion.y)
        panel.paint(g)
        g.translate(-targetRegion.x, -targetRegion.y)
    }
}

/**
 * Inlay 工具类
 */
object InlineChatInlayUtil {

    /**
     * 在指定行下方插入 Inline Chat 输入面板
     */
    fun insertInputPanel(
        editor: com.intellij.openapi.editor.Editor,
        lineNumber: Int,
        panel: InlineChatInputPanel
    ): Inlay<*>? {
        val inlayModel = editor.inlayModel
        val offset = if (lineNumber < editor.document.lineCount) {
            editor.document.getLineEndOffset(lineNumber)
        } else {
            editor.document.textLength
        }

        val properties = InlayProperties()
            .relatesToPrecedingText(false)

        return try {
            inlayModel.addBlockElement(offset, properties, InlineChatInputRenderer(panel))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 移除 Inlay
     */
    fun removeInlay(inlay: Inlay<*>?) {
        inlay?.dispose()
    }
}
