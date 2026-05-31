package com.codesage.ide.inline.actions

import com.codesage.ide.inline.InlineChatContext
import com.codesage.ide.inline.InlineChatController
import com.codesage.ide.inline.InlineChatMode
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Refactor Code Action
 *
 * 重构选中代码，提高可读性和性能。
 */
class RefactorCodeAction : AnAction("Refactor with CodeSage") {

    override fun actionPerformed(e: AnActionEvent) {
        startInlineChat(e, InlineChatMode.REFACTOR)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }
}
