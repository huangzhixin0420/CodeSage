package com.codesage.ide.inline.actions

import com.codesage.ide.inline.InlineChatContext
import com.codesage.ide.inline.InlineChatController
import com.codesage.ide.inline.InlineChatMode
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Action 工具函数
 */
fun startInlineChat(e: AnActionEvent, mode: InlineChatMode) {
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
        mode = mode,
        filePath = virtualFile?.path,
        language = virtualFile?.fileType?.name
    )

    val controller = InlineChatController.getInstance(project)
    controller.startSession(editor, context)
}
