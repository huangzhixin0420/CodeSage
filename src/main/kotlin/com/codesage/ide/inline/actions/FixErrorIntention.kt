package com.codesage.ide.inline.actions

import com.codesage.ide.inline.InlineChatContext
import com.codesage.ide.inline.InlineChatController
import com.codesage.ide.inline.InlineChatMode
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile

/**
 * Fix Error Intention Action
 *
 * 当编辑器当前行有错误时，在灯泡提示中显示 "Fix with CodeSage"。
 */
class FixErrorIntention : IntentionAction, PriorityAction {

    override fun getText(): String = "Fix with CodeSage"
    override fun getFamilyName(): String = "CodeSage"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        val caretLine = editor.caretModel.logicalPosition.line
        if (caretLine < 0 || caretLine >= editor.document.lineCount) return false

        val startOffset = editor.document.getLineStartOffset(caretLine)
        val endOffset = editor.document.getLineEndOffset(caretLine)
        val lineRange = TextRange(startOffset, endOffset)

        // 检查当前行范围内是否有 PsiErrorElement（编译/语法错误）
        var offset = startOffset
        while (offset < endOffset && offset < file.textLength) {
            var element = file.findElementAt(offset)
            while (element != null && element !is PsiFile) {
                if (element is PsiErrorElement && element.textRange.intersects(lineRange)) {
                    return true
                }
                element = element.parent
            }
            offset++
        }

        return false
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val caretLine = editor.caretModel.logicalPosition.line
        val startOffset = editor.document.getLineStartOffset(caretLine)
        val endOffset = editor.document.getLineEndOffset(caretLine)
        val lineText = editor.document.getText(TextRange(startOffset, endOffset))

        val context = InlineChatContext(
            selectedText = lineText,
            startLine = caretLine,
            endLine = caretLine,
            mode = InlineChatMode.FIX,
            filePath = file.virtualFile?.path,
            language = file.virtualFile?.fileType?.name
        )

        val controller = InlineChatController.getInstance(project)
        val session = controller.startSession(editor, context)

        // 自动发送修复请求
        session.sendRequest("修复以下代码中的错误：\n\n```\n$lineText\n```")
    }

    override fun startInWriteAction(): Boolean = false
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.TOP
}
