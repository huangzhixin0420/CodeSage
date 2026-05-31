package com.codesage.ide.inline.actions

import com.codesage.ide.inline.InlineChatContext
import com.codesage.ide.inline.InlineChatController
import com.codesage.ide.inline.InlineChatMode
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Explain Code Action
 *
 * 解释选中代码的功能、潜在问题和优化建议。
 */
class ExplainCodeAction : AnAction("Explain with CodeSage") {

    override fun actionPerformed(e: AnActionEvent) {
        startInlineChat(e, InlineChatMode.EXPLAIN)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }
}
