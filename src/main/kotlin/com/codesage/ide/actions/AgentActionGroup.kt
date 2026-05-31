package com.codesage.ide.actions

import com.codesage.agent.core.AgentCore
import com.codesage.plugin.CodeSageProjectService
import com.codesage.shared.config.PluginConfig
import com.codesage.shared.utils.Logger
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.SwingUtilities

/**
 * Agent动作组 - 主菜单
 */
class AgentActionGroup : ActionGroup() {
    private val logger = Logger.getLogger<AgentActionGroup>()

    init {
        templatePresentation.text = "CodeSage"
        templatePresentation.description = "CodeSage AI Assistant"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        return arrayOf(
            OpenAgentWindowAction(),
            ClearSessionAction(),
            Separator.getInstance(),
            SelectModelAction()
        )
    }
}

/**
 * 打开Agent窗口动作
 */
class OpenAgentWindowAction : AnAction() {
    private val logger = Logger.getLogger<OpenAgentWindowAction>()

    init {
        templatePresentation.text = "打开 AI Assistant"
        templatePresentation.description = "打开CodeSage AI助手窗口"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("CodeSage")

        if (toolWindow != null) {
            toolWindow.show()
            focusInputField(toolWindow)
            logger.info("Opened CodeSage tool window")
        } else {
            logger.warn("CodeSage tool window not found")
        }
    }

    private fun focusInputField(toolWindow: ToolWindow) {
        SwingUtilities.invokeLater {
            val content = toolWindow.contentManager.selectedContent
            val panel = content?.component as? com.codesage.ide.toolwindow.AgentToolWindowPanel
        }
    }
}

/**
 * 清空会话动作
 */
class ClearSessionAction : AnAction() {
    private val logger = Logger.getLogger<ClearSessionAction>()

    init {
        templatePresentation.text = "清空会话"
        templatePresentation.description = "清空当前对话历史"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        try {
            val projectService = CodeSageProjectService.getInstance(project)
            projectService.agentCore.clearSession()
            logger.info("Session cleared")
        } catch (ex: Exception) {
            logger.warn("Failed to clear session: ${ex.message}")
        }
    }
}

/**
 * 选择模型动作组 — 动态生成当前配置的所有模型
 */
class SelectModelAction : ActionGroup() {
    init {
        templatePresentation.text = "选择模型"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val config = PluginConfig.getInstance()
        val actions = mutableListOf<AnAction>()

        config.enabledProviders.forEach { provider ->
            if (actions.isNotEmpty()) {
                actions.add(Separator.getInstance())
            }
            provider.models.forEach { model ->
                actions.add(SwitchModelAction(model, provider.name))
            }
        }

        if (actions.isEmpty()) {
            actions.add(object : AnAction("无可用模型") {
                override fun actionPerformed(e: AnActionEvent) {}
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = false
                }
            })
        }

        return actions.toTypedArray()
    }
}

/**
 * 切换模型动作
 */
class SwitchModelAction(
    private val modelId: String,
    private val providerName: String = ""
) : AnAction(if (providerName.isNotEmpty()) "$modelId ($providerName)" else modelId) {

    private val logger = Logger.getLogger<SwitchModelAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        try {
            val projectService = CodeSageProjectService.getInstance(project)
            projectService.agentCore.switchModel(modelId)
            logger.info("Switched to model: $modelId")
        } catch (ex: Exception) {
            logger.warn("Failed to switch model: ${ex.message}")
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        if (project != null) {
            try {
                val currentModel = CodeSageProjectService.getInstance(project).agentCore.getCurrentModel()
                e.presentation.description = if (currentModel == modelId) "✓ 当前模型" else "切换到 $modelId"
                e.presentation.isEnabled = true
            } catch (ex: Exception) {
                e.presentation.description = "切换到 $modelId"
                e.presentation.isEnabled = false
            }
        }
    }
}

/**
 * 编辑器上下文菜单动作 - AI分析代码
 */
class AnalyzeSelectionAction : AnAction() {
    private val logger = Logger.getLogger<AnalyzeSelectionAction>()

    init {
        templatePresentation.text = "AI 分析代码"
        templatePresentation.description = "使用AI分析选中的代码"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel.selectedText

        if (selection.isNullOrBlank()) {
            logger.info("No text selected")
            return
        }

        // 打开工具窗口
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("CodeSage")
        toolWindow?.show()

        // 获取 AgentCore 并发送分析请求
        try {
            val projectService = CodeSageProjectService.getInstance(project)
            val agentCore = projectService.agentCore

            val analysisRequest = "请分析以下代码，解释其功能、潜在问题和改进建议：\n\n```\n$selection\n```"

            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                kotlinx.coroutines.runBlocking {
                    try {
                        val result = agentCore.chat(analysisRequest)
                        logger.info("Analysis request completed: ${result::class.simpleName}")
                    } catch (ex: Exception) {
                        logger.error("Analysis failed", ex)
                    }
                }
            }

            logger.info("Analysis request sent for selection (${selection.length} chars)")
        } catch (ex: Exception) {
            logger.error("Failed to send analysis request", ex)
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }
}
