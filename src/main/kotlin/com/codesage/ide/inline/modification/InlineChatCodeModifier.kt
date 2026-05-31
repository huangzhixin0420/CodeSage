package com.codesage.ide.inline.modification

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project

/**
 * Inline Chat 代码修改器
 *
 * 将 Diff 安全地应用到编辑器文档中，支持 Undo/Redo。
 * 所有修改通过单个 [WriteCommandAction] 执行，用户按一次 Ctrl+Z 即可撤销全部。
 */
class InlineChatCodeModifier(
    private val project: Project,
    private val document: Document
) {

    /**
     * 应用一组代码变更
     *
     * @param changes 变更列表
     * @param onSuccess 应用成功回调
     * @param onError 应用失败回调
     */
    fun applyChanges(
        changes: CodeChanges,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (!changes.isValid()) {
            onError?.invoke("Invalid changes")
            return
        }

        try {
            WriteCommandAction.writeCommandAction(project)
                .withName("Apply CodeSage Inline Chat Changes")
                .withGroupId("CodeSage.InlineChat")
                .run<Throwable> {
                    // 从后往前应用修改，避免行号偏移问题
                    val sortedChanges = changes.changes.sortedByDescending { it.startLine }

                    for (change in sortedChanges) {
                        when (change.type) {
                            ChangeType.REPLACE -> replaceLines(change)
                            ChangeType.INSERT -> insertLines(change)
                            ChangeType.DELETE -> deleteLines(change)
                        }
                    }
                }

            onSuccess?.invoke()
        } catch (e: Exception) {
            onError?.invoke(e.message ?: "Failed to apply changes")
        }
    }

    /**
     * 预览变更（不实际应用，仅返回应用后的文本）
     */
    fun previewChanges(originalText: String, changes: CodeChanges): String {
        if (!changes.isValid()) return originalText

        val lines = originalText.lines().toMutableList()
        val sortedChanges = changes.changes.sortedByDescending { it.startLine }

        for (change in sortedChanges) {
            when (change.type) {
                ChangeType.REPLACE -> {
                    val s = change.startLine.coerceAtLeast(0)
                    val e = (change.endLine + 1).coerceAtMost(lines.size)
                    repeat(e - s) { if (s < lines.size) lines.removeAt(s) }
                    lines.addAll(s, change.newContent.lines())
                }

                ChangeType.INSERT -> {
                    val pos = (change.startLine + 1).coerceAtMost(lines.size)
                    lines.addAll(pos, change.newContent.lines())
                }

                ChangeType.DELETE -> {
                    val s = change.startLine.coerceAtLeast(0)
                    val e = (change.endLine + 1).coerceAtMost(lines.size)
                    repeat(e - s) { if (s < lines.size) lines.removeAt(s) }
                }
            }
        }

        return lines.joinToString("\n")
    }

    private fun replaceLines(change: CodeChange) {
        val lineCount = document.lineCount
        if (change.startLine < 0 || change.startLine >= lineCount) {
            throw IllegalArgumentException(
                "Invalid startLine: ${change.startLine}, document has $lineCount lines"
            )
        }
        val startOffset = document.getLineStartOffset(change.startLine)
        val endOffset = if (change.endLine < lineCount) {
            document.getLineEndOffset(change.endLine)
        } else {
            document.textLength
        }
        document.replaceString(startOffset, endOffset, change.newContent)
    }

    private fun insertLines(change: CodeChange) {
        val lineCount = document.lineCount
        val safeLine = change.startLine.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        val offset = if (safeLine < lineCount) {
            document.getLineEndOffset(safeLine)
        } else {
            document.textLength
        }
        document.insertString(offset, "\n" + change.newContent)
    }

    private fun deleteLines(change: CodeChange) {
        val lineCount = document.lineCount
        if (change.startLine < 0 || change.startLine >= lineCount) return
        val startOffset = document.getLineStartOffset(change.startLine)
        val endOffset = if (change.endLine + 1 < lineCount) {
            document.getLineStartOffset(change.endLine + 1)
        } else {
            document.textLength
        }
        document.deleteString(startOffset, endOffset)
    }
}
