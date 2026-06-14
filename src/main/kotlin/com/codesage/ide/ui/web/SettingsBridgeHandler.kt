package com.codesage.ide.ui.web

import com.codesage.ide.actions.OpenSettingsFolderAction
import com.codesage.ide.actions.ReloadSettingsAction
import com.codesage.shared.config.SettingsFile
import com.codesage.shared.config.SettingsRepository
import com.codesage.shared.utils.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Settings Bridge Handler — 处理来自 Web UI 的 settings_* 消息
 *
 * 协议:
 *   - settings_get → 推送 settings_data(settings)
 *   - settings_update { settings } → 保存并广播
 *   - settings_reload → 重新从磁盘读
 *   - settings_open_folder → 在 OS 文件管理器打开 settings.json 所在目录
 */
class SettingsBridgeHandler(
    private val onMessage: (Map<String, Any?>) -> Unit,
    private val project: com.intellij.openapi.project.Project? = null,
) {
    private val logger = Logger.getLogger<SettingsBridgeHandler>()

    /**
     * 处理来自 Web UI 的 settings_* 消息
     * @return true 表示消息已处理
     */
    fun handle(type: String, data: Map<String, Any?>): Boolean {
        if (!type.startsWith("settings_")) return false
        try {
            when (type) {
                "settings_get" -> sendData()
                "settings_update" -> {
                    val raw = data["settings"]
                    if (raw == null) {
                        sendError("missing settings field")
                        return true
                    }
                    updateSettings(raw)
                }

                "settings_reload" -> {
                    val repo = SettingsRepository.getInstance()
                    repo.reload()
                    sendData()
                }

                "settings_open_folder" -> openFolder()
                "settings_open_file" -> openFile()
                // v2.0 修复:对 "open_settings" 这类以前走不通的入口,JCEFChatPanel 改用本 type 转给本 handler,
                // 本 handler 再通过 onMessage 通道(同 sendToJS)把 open_settings_view 事件回投前端,
                // 触发 in-web 设置视图。发送通道共享 JCEFChatPanel 的 sendToJS 引用,行为一致。
                "settings_open_view" -> onMessage(mapOf("type" to "open_settings_view"))
                else -> logger.debug("Unknown settings message: $type")
            }
        } catch (e: Exception) {
            logger.error("Settings bridge handler error for $type", e)
            sendError(e.message ?: "unknown")
        }
        return true
    }

    private fun sendData() {
        val repo = SettingsRepository.getInstance()
        val settings = repo.get()
        val map = settingsToMap(settings).toMutableMap()
        map["path"] = repo.getPath()?.toString() ?: ""
        onMessage(mapOf("type" to "settings_data", "settings" to map))
    }

    private fun sendError(message: String) {
        onMessage(mapOf("type" to "settings_error", "message" to message))
    }

    private fun updateSettings(raw: Any) {
        val repo = SettingsRepository.getInstance()
        val parsed = try {
            when (raw) {
                is kotlinx.serialization.json.JsonElement ->
                    DefaultSettingsJson.decodeFromJsonElement(SettingsFile.serializer(), raw)

                else -> {
                    val json = DefaultSettingsJson.encodeToString(toJsonElement(raw))
                    DefaultSettingsJson.decodeFromString(SettingsFile.serializer(), json)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse settings update", e)
            sendError("解析失败: ${e.message}")
            return
        }
        val ok = repo.save(parsed)
        if (ok) {
            onMessage(mapOf("type" to "settings_saved"))
            sendData()
        } else {
            sendError("保存失败,请检查文件权限")
        }
    }

    private fun openFolder() {
        ApplicationManager.getApplication().invokeLater {
            val repo = SettingsRepository.getInstance()
            // 防御:如果 repo 还没初始化完 (getPath() == null),主动触发一次 reload 让它写盘并设置 path
            if (repo.getPath() == null) {
                logger.warn("settings_open_folder: SettingsRepository.getPath() == null, forcing reload")
                repo.reload()
            }
            val path = repo.getPath()?.parent
            if (path == null) {
                logger.error("settings_open_folder: settings path unavailable after reload; cannot open folder")
                onMessage(mapOf("type" to "settings_error", "message" to "无法定位 settings.json 所在目录"))
                return@invokeLater
            }
            OpenSettingsFolderAction.openInOs(path.toFile())
        }
    }

    private fun openFile() {
        ApplicationManager.getApplication().invokeLater {
            val path = SettingsRepository.getInstance().getPath()?.toFile() ?: return@invokeLater
            val vf = LocalFileSystem.getInstance().findFileByIoFile(path)
            val targetProject =
                project ?: com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
            if (vf != null && targetProject != null) {
                FileEditorManager.getInstance(targetProject).openTextEditor(
                    OpenFileDescriptor(targetProject, vf, 0),
                    false
                )
            } else {
                logger.warn("settings.json not found in VFS: $path (project=${targetProject != null}, vf=${vf != null})")
            }
        }
    }

    /**
     * 任意对象转 JsonElement
     *
     * 关键:当 value 已经是 JsonElement(从 JBCefJSQuery 来的 `data["settings"]` 就是
     * `JsonElement`,而非裸 Map)时,直接原样返回。否则走 `else` 分支用 `value.toString()`
     * 重建会出问题:`JsonPrimitive("en-US").toString()` 对 string 类型返回 `"en-US"`(带引号
     * 的 JSON 表示),再用这个字符串建 `JsonPrimitive` 编码后变成 `"\"en-US\""`,解码回
     * 来 language 就成了带引号的 `"en-US"`,跟 select option 的 `value="en-US"`(不带引号)
     * 严格比较不匹配,select 落到默认第一项,UI 看上去就是“恢复成简体中文”。
     */
    private fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement {
        return when (value) {
            is kotlinx.serialization.json.JsonElement -> value
            is Map<*, *> -> kotlinx.serialization.json.JsonObject(
                value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) }
            )

            is List<*> -> kotlinx.serialization.json.JsonArray(value.map { toJsonElement(it) })
            is String -> kotlinx.serialization.json.JsonPrimitive(value)
            is Number -> kotlinx.serialization.json.JsonPrimitive(value)
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
            null -> kotlinx.serialization.json.JsonNull
            else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
        }
    }

    companion object {
        // 镜像 com.codesage.shared.config.DefaultSettings.JSON
        private val DefaultSettingsJson = kotlinx.serialization.json.Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            isLenient = true
        }

        /**
         * 把 SettingsFile 转换为 Map(供 JS 端消费)
         * 提取为 companion internal，供 MigrationBridgeHandlerBridge 复用
         */
        internal fun settingsToMap(s: SettingsFile): Map<String, Any?> = mapOf(
            "\$schema" to s.schema,
            "version" to s.version,
            "providers" to s.providers.map { p ->
                mapOf(
                    "id" to p.id,
                    "name" to p.name,
                    "type" to p.type,
                    "baseUrl" to p.baseUrl,
                    "enabled" to p.enabled,
                    "apiKeyRef" to p.apiKeyRef,
                    "models" to p.models.map { m ->
                        mapOf(
                            "id" to m.id,
                            "label" to m.label,
                            "contextSize" to m.contextSize,
                            "supportsTools" to m.supportsTools,
                            "supportsVision" to m.supportsVision,
                            "isDefault" to m.isDefault,
                        )
                    },
                    "metadata" to p.metadata,
                )
            },
            "defaults" to mapOf(
                "providerId" to s.defaults.providerId,
                "model" to s.defaults.model,
                "mode" to s.defaults.mode,
                "codingModel" to s.defaults.codingModel,
                "reasoningModel" to s.defaults.reasoningModel,
                "visionModel" to s.defaults.visionModel,
            ),
            "agent" to mapOf(
                "enablePlanning" to s.agent.enablePlanning,
                "enableParallelSubAgents" to s.agent.enableParallelSubAgents,
                "maxParallelSubAgents" to s.agent.maxParallelSubAgents,
                "enableStreaming" to s.agent.enableStreaming,
                "maxContextMessages" to s.agent.maxContextMessages,
                "truncationStrategy" to s.agent.truncationStrategy,
                "promptRole" to s.agent.promptRole,
                "autoSaveEnabled" to s.agent.autoSaveEnabled,
            ),
            "ui" to mapOf(
                "theme" to s.ui.theme,
                "showThinking" to s.ui.showThinking,
                "compactMode" to s.ui.compactMode,
                "fontSize" to s.ui.fontSize,
                "codeBlockTheme" to s.ui.codeBlockTheme,
                "streamMarkdownLive" to s.ui.streamMarkdownLive,
                "animationSpeed" to s.ui.animationSpeed,
                "sidebarCollapsed" to s.ui.sidebarCollapsed,
                "language" to s.ui.language,
            ),
            "editor" to mapOf(
                "autoAttachSelection" to s.editor.autoAttachSelection,
                "autoAttachFileContext" to s.editor.autoAttachFileContext,
                "maxContextFiles" to s.editor.maxContextFiles,
                "autoSaveOnSend" to s.editor.autoSaveOnSend,
            ),
            "shortcuts" to mapOf(
                "send" to s.shortcuts.send,
                "newLine" to s.shortcuts.newLine,
                "stop" to s.shortcuts.stop,
                "commandPalette" to s.shortcuts.commandPalette,
                "toggleThinking" to s.shortcuts.toggleThinking,
                "switchModel" to s.shortcuts.switchModel,
                "toggleSidebar" to s.shortcuts.toggleSidebar,
                "newSession" to s.shortcuts.newSession,
            ),
            "mcp" to mapOf(
                "servers" to s.mcp.servers.map { srv ->
                    mapOf(
                        "id" to srv.id,
                        "name" to srv.name,
                        "transport" to srv.transport,
                        "command" to srv.command,
                        "args" to srv.args,
                        "url" to srv.url,
                        "enabled" to srv.enabled,
                        "env" to srv.env,
                    )
                }
            ),
            "acp" to mapOf(
                "enabled" to s.acp.enabled,
                "serverPort" to s.acp.serverPort,
                "externalAgents" to s.acp.externalAgents.map { agent ->
                    mapOf(
                        "id" to agent.id,
                        "name" to agent.name,
                        "command" to agent.command,
                        "args" to agent.args,
                        "env" to agent.env,
                        "workingDir" to agent.workingDir,
                        "enabled" to agent.enabled,
                    )
                }
            ),
            "network" to mapOf(
                "proxy" to mapOf(
                    "mode" to s.network.proxy.mode,
                    "type" to s.network.proxy.type,
                    "host" to s.network.proxy.host,
                    "port" to s.network.proxy.port,
                    "username" to s.network.proxy.username,
                    "passwordRef" to s.network.proxy.passwordRef,
                    "noProxy" to s.network.proxy.noProxy,
                ),
            ),
            "advanced" to mapOf(
                "enableTelemetry" to s.advanced.enableTelemetry,
                "telemetryEndpoint" to s.advanced.telemetryEndpoint,
                "logLevel" to s.advanced.logLevel,
                "autoUpdate" to s.advanced.autoUpdate,
                "checkUpdateIntervalHours" to s.advanced.checkUpdateIntervalHours,
                "experimentalFeatures" to s.advanced.experimentalFeatures,
                "customCss" to s.advanced.customCss,
            ),
        )
    }
}
