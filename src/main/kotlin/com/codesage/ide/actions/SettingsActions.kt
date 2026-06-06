package com.codesage.ide.actions

import com.codesage.shared.config.SettingsRepository
import com.codesage.shared.utils.Logger
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * 在 OS 文件管理器中打开 settings.json 所在目录
 */
class OpenSettingsFolderAction : AnAction(), DumbAware {

    private val logger = Logger.getLogger<OpenSettingsFolderAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val path = SettingsRepository.getInstance().getPath()?.parent?.toFile() ?: return
        openInOs(path)
    }

    companion object {
        /**
         * 在 OS 文件管理器中打开目录
         * 跨平台支持:macOS / Windows / Linux
         *
         * 之前默认走 Desktop.getDesktop().open(dir), 但在 macOS 上
         * 有时 .isSupported(OPEN) 返回 false 或静默失败 — 改为按平台
         * 直接调用对应的 shell 命令 (open / explorer / xdg-open) 更可控
         */
        fun openInOs(dir: File) {
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val absolute = dir.absolutePath
                val os = System.getProperty("os.name").lowercase()
                val cmd = when {
                    "mac" in os -> arrayOf("open", absolute)
                    "win" in os -> arrayOf("explorer.exe", absolute)
                    else -> arrayOf("xdg-open", absolute)
                }
                val proc = ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .start()
                // 等待命令结束,获取 exit code 用来判断是否成功
                val exitCode = try {
                    proc.waitFor()
                } catch (e: Exception) {
                    -1
                }
                if (exitCode != 0) {
                    Logger.getLogger<OpenSettingsFolderAction>().warn(
                        "open command returned exitCode=$exitCode for dir=$absolute"
                    )
                }
            } catch (e: Exception) {
                Logger.getLogger<OpenSettingsFolderAction>().error("Failed to open settings folder", e)
            }
        }
    }
}

/**
 * 从磁盘重载 settings.json
 */
class ReloadSettingsAction : AnAction(), DumbAware {

    private val logger = Logger.getLogger<ReloadSettingsAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val repo = SettingsRepository.getInstance()
        val settings = repo.reload()
        logger.info("Settings reloaded via menu action: ${settings.providers.size} providers")
        // 通过 Notifications 提示用户
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeSage")
            .createNotification(
                "设置已重载",
                "${settings.providers.size} providers, ${settings.mcp.servers.size} mcp servers",
                com.intellij.notification.NotificationType.INFORMATION,
            )
            .notify(e.project)
    }
}

/**
 * 在 IDE 编辑器中打开 settings.json
 */
class OpenSettingsFileAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val file = SettingsRepository.getInstance().getPath()?.toFile() ?: return
        val vf = LocalFileSystem.getInstance().findFileByIoFile(file)
        val project = e.project ?: return
        if (vf != null) {
            FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(project, vf, 0),
                false
            )
        }
    }
}
