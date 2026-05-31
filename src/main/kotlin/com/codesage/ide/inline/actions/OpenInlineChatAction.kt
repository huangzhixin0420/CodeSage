package com.codesage.ide.inline.actions

import com.codesage.ide.inline.InlineChatContext
import com.codesage.ide.inline.InlineChatController
import com.codesage.ide.inline.InlineChatMode
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Inline Chat 主 Action
 *
 * 触发方式：
 * - 右键菜单: "CodeSage Inline Chat"
 * - 快捷键: Alt+Enter
 */
class OpenInlineChatAction : AnAction("CodeSage Inline Chat") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText
        val (startLine, endLine) = if (selectionModel.hasSelection()) {
            val start = editor.document.getLineNumber(selectionModel.selectionStart)
            val end = editor.document.getLineNumber(selectionModel.selectionEnd)
            start to end
        } else {
            val caretLine = editor.caretModel.logicalPosition.line
            caretLine to caretLine
        }

        val virtualFile = editor.virtualFile
        val context = InlineChatContext(
            selectedText = selectedText,
            startLine = startLine,
            endLine = endLine,
            mode = InlineChatMode.CHAT,
            filePath = virtualFile?.path,
            language = virtualFile?.fileType?.name
        )

        val controller = InlineChatController.getInstance(project)
        controller.startSession(editor, context)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null
    }
}
