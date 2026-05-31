package com.codesage.ide.inline.actions

import com.codesage.ide.inline.InlineChatContext
import com.codesage.ide.inline.InlineChatController
import com.codesage.ide.inline.InlineChatMode
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Generate Test Action
 *
 * 为选中代码生成单元测试。
 */
class GenerateTestAction : AnAction("Generate Test with CodeSage") {

    override fun actionPerformed(e: AnActionEvent) {
        startInlineChat(e, InlineChatMode.TEST)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }
}
