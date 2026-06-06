package com.codesage.ide.ui.web

import com.codesage.shared.config.MigrationPreview
import com.codesage.shared.config.PluginConfig
import com.codesage.shared.config.SettingsMigrations
import com.codesage.shared.config.SettingsRepository
import com.codesage.shared.utils.Logger

/**
 * 旧 IDE Configurable 迁移 Bridge Handler
 *
 * 协议:
 *   - legacy_migration_check { }              → 返回 migration_preview 或 { hasData: false }
 *   - legacy_migration_run { }                → 执行迁移,settings.json 写入
 *   - legacy_migration_skip { }               → 标记跳过(不写入文件,前端记 localStorage)
 *
 * 数据流:
 *   1. UI 启动 1.5s 后调 legacy_migration_check
 *   2. 如果 PluginConfig 有非默认 providers(enabledProviders 不空),返回预览
 *   3. UI 显示 wizard,用户点 Migrate → legacy_migration_run
 *   4. 写 settings.json,推送 settings_data,UI 刷新
 */
class MigrationBridgeHandler(
    private val onMessage: (Map<String, Any?>) -> Unit,
) {
    private val logger = Logger.getLogger<MigrationBridgeHandler>()

    fun handle(type: String, data: Map<String, Any?>): Boolean {
        if (!type.startsWith("legacy_migration_")) return false
        try {
            when (type) {
                "legacy_migration_check" -> handleCheck(data)
                "legacy_migration_run" -> handleRun(data)
                "legacy_migration_skip" -> handleSkip(data)
            }
        } catch (e: Exception) {
            logger.error("Migration bridge handler error for $type", e)
            onMessage(
                mapOf(
                    "type" to "legacy_migration_error",
                    "requestId" to (data["requestId"] ?: ""),
                    "message" to (e.message ?: "unknown"),
                )
            )
        }
        return true
    }

    private fun handleCheck(data: Map<String, Any?>) {
        val config = PluginConfig.getInstance()
        val oldProviders = config.providers
        val hasData = SettingsMigrations.needsMigration(
            oldProviderCount = oldProviders.size,
            oldDefaultProviderId = config.defaultProviderId,
            oldDefaultModel = config.defaultModel,
        )
        if (!hasData) {
            onMessage(
                mapOf(
                    "type" to "legacy_migration_preview",
                    "requestId" to (data["requestId"] ?: ""),
                    "hasData" to false,
                )
            )
            return
        }
        // 转换成新 ProviderEntry 列表
        val providerEntries = oldProviders.map { p ->
            SettingsMigrations.migrateProvider(
                id = p.id,
                name = p.name,
                type = p.providerType,
                baseUrl = p.baseUrl,
                enabled = p.isEnabled,
                models = p.models.toList(),
                defaultModel = if (config.defaultProviderId == p.id) config.defaultModel else null,
            )
        }
        // 构建完整 settings
        val newSettings = SettingsMigrations.buildMigratedSettings(
            oldProviders = providerEntries,
            oldDefaultProviderId = config.defaultProviderId,
            oldDefaultModel = config.defaultModel,
            oldCodingModel = config.codingModel,
            oldReasoningModel = config.reasoningModel,
            oldEnableStreaming = config.enableStreaming,
            oldMaxContextMessages = config.maxContextMessages,
            oldTruncationStrategy = config.truncationStrategy,
            oldPromptRole = config.promptRole,
            oldMcpServers = config.mcpServerConfigs.map { srv ->
                com.codesage.shared.config.McpServerEntry(
                    id = srv.id,
                    name = srv.name,
                    transport = srv.transportType,
                    command = srv.command,
                    args = srv.args.toList(),
                    url = srv.url,
                    enabled = srv.enabled,
                )
            },
        )
        val preview: MigrationPreview = SettingsMigrations.buildMigrationPreview(
            oldProviders = providerEntries,
            newSettings = newSettings,
        )
        // 同时计算每个 provider 是否有 key(PasswordSafe)
        val providersWithKeyStatus = oldProviders.map { p ->
            mapOf(
                "id" to p.id,
                "name" to p.name,
                "hasKey" to !config.getProviderApiKey(p.id).isNullOrBlank(),
            )
        }
        logger.info("[MigrationBridge] check: ${oldProviders.size} providers, ${preview.mcpServerCount} mcp, ${preview.providersWithKeys} keys")
        onMessage(
            mapOf(
                "type" to "legacy_migration_preview",
                "requestId" to (data["requestId"] ?: ""),
                "hasData" to true,
                "preview" to mapOf(
                    "providerCount" to preview.providerCount,
                    "mcpServerCount" to preview.mcpServerCount,
                    "providersWithKeys" to preview.providersWithKeys,
                    "warnings" to preview.warnings,
                ),
                "providers" to providersWithKeyStatus,
                "newSettings" to mapOf(
                    "providers" to newSettings.providers.map { p ->
                        mapOf(
                            "id" to p.id,
                            "name" to p.name,
                            "type" to p.type,
                            "baseUrl" to p.baseUrl,
                            "enabled" to p.enabled,
                            "models" to p.models.map { m -> mapOf("id" to m.id, "label" to m.label) },
                        )
                    },
                    "defaults" to mapOf(
                        "providerId" to newSettings.defaults.providerId,
                        "model" to newSettings.defaults.model,
                    ),
                    "mcpServerCount" to newSettings.mcp.servers.size,
                ),
            )
        )
    }

    private fun handleRun(data: Map<String, Any?>) {
        val config = PluginConfig.getInstance()
        val oldProviders = config.providers
        if (oldProviders.isEmpty() && config.defaultProviderId.isBlank()) {
            onMessage(
                mapOf(
                    "type" to "legacy_migration_error",
                    "requestId" to (data["requestId"] ?: ""),
                    "message" to "无旧配置可迁移",
                )
            )
            return
        }
        val providerEntries = oldProviders.map { p ->
            SettingsMigrations.migrateProvider(
                id = p.id,
                name = p.name,
                type = p.providerType,
                baseUrl = p.baseUrl,
                enabled = p.isEnabled,
                models = p.models.toList(),
                defaultModel = if (config.defaultProviderId == p.id) config.defaultModel else null,
            )
        }
        val newSettings = SettingsMigrations.buildMigratedSettings(
            oldProviders = providerEntries,
            oldDefaultProviderId = config.defaultProviderId,
            oldDefaultModel = config.defaultModel,
            oldCodingModel = config.codingModel,
            oldReasoningModel = config.reasoningModel,
            oldEnableStreaming = config.enableStreaming,
            oldMaxContextMessages = config.maxContextMessages,
            oldTruncationStrategy = config.truncationStrategy,
            oldPromptRole = config.promptRole,
            oldMcpServers = config.mcpServerConfigs.map { srv ->
                com.codesage.shared.config.McpServerEntry(
                    id = srv.id,
                    name = srv.name,
                    transport = srv.transportType,
                    command = srv.command,
                    args = srv.args.toList(),
                    url = srv.url,
                    enabled = srv.enabled,
                )
            },
        )
        val ok = SettingsRepository.getInstance().save(newSettings)
        logger.info("[MigrationBridge] run: ok=$ok, ${newSettings.providers.size} providers")
        if (ok) {
            onMessage(
                mapOf(
                    "type" to "legacy_migration_done",
                    "requestId" to (data["requestId"] ?: ""),
                    "success" to true,
                    "providerCount" to newSettings.providers.size,
                )
            )
            // 触发 settings_data 推送(让 settings 视图立即更新)
            com.codesage.ide.ui.web.SettingsBridgeHandlerBridge.pushSettingsUpdate(onMessage)
        } else {
            onMessage(
                mapOf(
                    "type" to "legacy_migration_error",
                    "requestId" to (data["requestId"] ?: ""),
                    "message" to "保存 settings.json 失败,请检查文件权限",
                )
            )
        }
    }

    private fun handleSkip(data: Map<String, Any?>) {
        logger.info("[MigrationBridge] user skipped migration")
        onMessage(
            mapOf(
                "type" to "legacy_migration_skipped",
                "requestId" to (data["requestId"] ?: ""),
            )
        )
    }
}

/**
 * 内部 helper:迁移完成后立即推送 settings_data 给前端
 */
internal object SettingsBridgeHandlerBridge {
    fun pushSettingsUpdate(onMessage: (Map<String, Any?>) -> Unit) {
        val settings = SettingsRepository.getInstance().get()
        val map = mutableMapOf<String, Any?>(
            "type" to "settings_data",
            "settings" to SettingsBridgeHandler.settingsToMap(settings),
        )
        map["path"] = SettingsRepository.getInstance().getPath()?.toString() ?: ""
        onMessage(map)
    }
}
