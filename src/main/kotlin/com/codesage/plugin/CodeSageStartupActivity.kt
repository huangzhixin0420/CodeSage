package com.codesage.plugin

import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * CodeSage 启动活动
 * 在项目打开时执行初始化
 */
class CodeSageStartupActivity : ProjectActivity {
    private val logger = Logger.getLogger<CodeSageStartupActivity>()

    override suspend fun execute(project: Project) {
        logger.info("CodeSage startup activity executing for project: ${project.name}")

        // 触发项目服务的初始化
        val projectService = CodeSageProjectService.getInstance(project)

        // 确保应用服务已初始化
        val appService = CodeSageAppService.getInstance()

        logger.info(
            "CodeSage initialized. Models: ${appService.modelRegistry.listProviders().size}, " +
                    "Skills: ${appService.skillRegistry.count()}"
        )
    }
}
