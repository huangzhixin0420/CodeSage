package com.codesage.plugin

import com.codesage.shared.config.SettingsRepository
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

        // 强制实例化 SettingsRepository — 触发 init block 在线程池上跑
        // 写出 ~/.codesage/settings.json (含默认 Provider)
        // 之前 SettingsRepository 是懒加载的,如果用户从未打开过设置页就
        // 不会创建文件 — 现在保证安装即生成,首次进设置页就有数据可显示
        try {
            val repo = SettingsRepository.getInstance()
            val path = repo.getPath()
            logger.info(
                "SettingsRepository initialized at startup. settings.json path: $path"
            )
        } catch (e: Exception) {
            logger.error("Failed to initialize SettingsRepository at startup", e)
        }

        logger.info(
            "CodeSage initialized. Models: ${appService.modelRegistry.listProviders().size}, " +
                    "Skills: ${appService.skillRegistry.count()}"
        )
    }
}
