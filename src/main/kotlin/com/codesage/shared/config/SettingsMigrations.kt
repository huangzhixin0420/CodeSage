package com.codesage.shared.config

import com.codesage.shared.utils.Logger

/**
 * 旧 IDE 配置(CodeSagePlugin.xml) → 新 settings.json 迁移
 *
 * 流程:
 *   1. 检测旧 PluginConfig 是否有非默认 providers / settings
 *   2. 用户在 UI 上点 "Migrate" 时调用 [migrate]
 *   3. 生成新 settings,保留原 API Key 引用
 *   4. 旧 IDE 配置标记 deprecated 但保留(可回退)
 *
 * 注意:API Key 不在 IDE 旧 XML 中,而是存在 PasswordSafe。
 * 迁移时只需保留 providerId 引用 keychain:XXX,key 仍由 PasswordSafe 管理。
 */
object SettingsMigrations {

    private val logger = Logger.getLogger<SettingsMigrations>()

    /**
     * 检测是否需要迁移
     * 条件:旧 PluginConfig 有 enabled providers 或非默认设置
     */
    fun needsMigration(
        oldProviderCount: Int,
        oldDefaultProviderId: String,
        oldDefaultModel: String,
    ): Boolean {
        return oldProviderCount > 0 ||
                oldDefaultProviderId.isNotBlank() ||
                oldDefaultModel.isNotBlank()
    }

    /**
     * 把旧 ProviderConfig 转换成新 ProviderEntry
     */
    fun migrateProvider(
        id: String,
        name: String,
        type: String,
        baseUrl: String,
        enabled: Boolean,
        models: List<String>,
        defaultModel: String? = null,
    ): ProviderEntry {
        return ProviderEntry(
            id = id,
            name = name,
            type = type,
            baseUrl = baseUrl,
            enabled = enabled,
            apiKeyRef = "keychain:$id",
            models = models.mapIndexed { index, modelId ->
                ModelEntry(
                    id = modelId,
                    label = modelId,
                    contextSize = guessContextSize(modelId),
                    supportsTools = true,
                    isDefault = modelId == defaultModel,
                )
            },
        )
    }

    /**
     * 推测模型 context size(粗略启发式,无法精确)
     */
    private fun guessContextSize(modelId: String): Int {
        val m = modelId.lowercase()
        return when {
            "128k" in m -> 128000
            "32k" in m -> 32000
            "8k" in m -> 8000
            "200k" in m || "200000" in m -> 200000
            "1m" in m -> 1000000
            "gpt-4o" in m -> 128000
            "gpt-4-turbo" in m -> 128000
            "gpt-3.5" in m -> 16000
            "kimi-k2" in m -> 200000
            "claude-3" in m -> 200000
            "gemini" in m && "1.5" in m -> 1000000
            "minimax" in m -> 128000
            else -> 32000  // 保守默认
        }
    }

    /**
     * 构建迁移后的 settings
     * 合并:
     *   - 旧 providers → 新 providers
     *   - 旧 defaultProviderId / defaultModel → 新 defaults
     *   - 旧 budget / agent 设置 → 新 agent
     *   - 旧 MCP servers → 新 mcp.servers
     */
    fun buildMigratedSettings(
        oldProviders: List<ProviderEntry>,
        oldDefaultProviderId: String = "",
        oldDefaultModel: String = "",
        oldCodingModel: String = "",
        oldReasoningModel: String = "",
        oldMaxIterations: Int = 30,
        oldMaxTokens: Int = 0,
        oldMaxDuration: Int = 600,
        oldBudgetWarning: Int = 70,
        oldSubAgentRatio: Double = 0.5,
        oldAllowContinue: Boolean = true,
        oldEnableStreaming: Boolean = true,
        oldMaxContextMessages: Int = 50,
        oldTruncationStrategy: String = "HYBRID",
        oldPromptRole: String = "ASSISTANT",
        oldMcpServers: List<McpServerEntry> = emptyList(),
    ): SettingsFile {
        val baseDefaults = DefaultSettings.create()
        val newAgent = baseDefaults.agent.copy(
            maxIterations = oldMaxIterations.coerceAtLeast(1),
            maxTokens = oldMaxTokens,
            maxDurationSeconds = oldMaxDuration,
            budgetWarningThreshold = oldBudgetWarning.coerceIn(10, 90),
            subAgentBudgetRatio = oldSubAgentRatio.coerceIn(0.1, 1.0),
            allowContinueOnExhaustion = oldAllowContinue,
            enableStreaming = oldEnableStreaming,
            maxContextMessages = oldMaxContextMessages,
            truncationStrategy = oldTruncationStrategy,
            promptRole = oldPromptRole,
        )
        val newDefaults = baseDefaults.defaults.copy(
            providerId = oldDefaultProviderId,
            model = oldDefaultModel,
            codingModel = oldCodingModel,
            reasoningModel = oldReasoningModel,
        )
        val newMcp = baseDefaults.mcp.copy(servers = oldMcpServers)
        // 合并 providers:旧的优先
        val providers = if (oldProviders.isNotEmpty()) oldProviders else baseDefaults.providers

        val migrated = baseDefaults.copy(
            providers = providers,
            defaults = newDefaults,
            agent = newAgent,
            mcp = newMcp,
        )
        logger.info("Migration built: ${providers.size} providers, ${oldMcpServers.size} mcp servers")
        return migrated
    }

    /**
     * 生成迁移报告(显示给用户预览)
     */
    fun buildMigrationPreview(
        oldProviders: List<ProviderEntry>,
        newSettings: SettingsFile,
    ): MigrationPreview {
        return MigrationPreview(
            providerCount = oldProviders.size,
            mcpServerCount = newSettings.mcp.servers.size,
            providersWithKeys = oldProviders.count { it.apiKeyRef.isNotBlank() },
            warnings = buildList {
                if (oldProviders.isEmpty()) add("未检测到旧 Provider,新文件将使用预置默认 Provider")
                if (newSettings.mcp.servers.isEmpty()) add("MCP 服务器配置将使用默认空列表")
            },
        )
    }
}

data class MigrationPreview(
    val providerCount: Int,
    val mcpServerCount: Int,
    val providersWithKeys: Int,
    val warnings: List<String> = emptyList(),
)
