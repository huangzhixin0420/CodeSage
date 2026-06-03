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
import java.awt.Desktop
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
         */
        fun openInOs(dir: File) {
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(dir)
                } else {
                    // Linux fallback
                    val os = System.getProperty("os.name").lowercase()
                    val cmd = when {
                        "mac" in os -> arrayOf("open", dir.absolutePath)
                        "win" in os -> arrayOf("explorer.exe", dir.absolutePath)
                        "nix" in os || "nux" in os -> arrayOf("xdg-open", dir.absolutePath)
                        else -> arrayOf("xdg-open", dir.absolutePath)
                    }
                    Runtime.getRuntime().exec(cmd)
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
