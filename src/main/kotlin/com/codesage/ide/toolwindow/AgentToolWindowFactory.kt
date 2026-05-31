package com.codesage.ide.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Agent工具窗口工厂
 */
class AgentToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        println("[CodeSage] AgentToolWindowFactory.createToolWindowContent called")
        val panel = AgentToolWindowPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "CodeSage", false)
        toolWindow.contentManager.addContent(content)
        println("[CodeSage] AgentToolWindowFactory content added")
    }
}
