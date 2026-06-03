package com.codesage.shared.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * settings.json v1 Schema
 *
 * 文件位置:`~/.codesage/settings.json`(JSON5 / JSON 兼容)
 * 设计目标:可被 git 跟踪、人类可读、单一 source of truth
 *
 * 顶层字段:
 *   - $schema: schema URL
 *   - version: schema 版本
 *   - providers: LLM Provider 列表
 *   - defaults: 默认 Provider / Model / Mode
 *   - agent: 预算 / Plan / SubAgent
 *   - ui: 主题 / 字号 / 动画
 *   - editor: 编辑器集成
 *   - shortcuts: 快捷键
 *   - mcp: MCP 服务器
 *   - advanced: 高级 / 调试
 */

@Serializable
data class SettingsFile(
    @SerialName("\$schema")
    val schema: String = "https://codesage.dev/schemas/settings/v1.json",
    val version: Int = 1,
    val providers: List<ProviderEntry> = emptyList(),
    val defaults: DefaultsSection = DefaultsSection(),
    val agent: AgentSection = AgentSection(),
    val ui: UiSection = UiSection(),
    val editor: EditorSection = EditorSection(),
    val shortcuts: ShortcutsSection = ShortcutsSection(),
    val mcp: McpSection = McpSection(),
    val advanced: AdvancedSection = AdvancedSection(),
)

@Serializable
data class ProviderEntry(
    val id: String,
    val name: String,
    val type: String,                       // minimax / kimi / openai / openai-compatible / anthropic / google
    val baseUrl: String = "",
    val enabled: Boolean = true,
    val models: List<ModelEntry> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    @SerialName("apiKeyRef")
    val apiKeyRef: String = "",              // e.g. "keychain:providerId" or "env:OPENAI_API_KEY"
)

@Serializable
data class ModelEntry(
    val id: String,
    val label: String = "",
    val contextSize: Int = 0,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val isDefault: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class DefaultsSection(
    val providerId: String = "",
    val model: String = "",
    val mode: String = "agent",             // agent / ask / manual
    val codingModel: String = "",
    val reasoningModel: String = "",
    val visionModel: String = "",
)

@Serializable
data class AgentSection(
    val maxIterations: Int = 30,
    val maxTokens: Int = 0,
    val maxDurationSeconds: Int = 600,
    val budgetWarningThreshold: Int = 70,
    val subAgentBudgetRatio: Double = 0.5,
    val allowContinueOnExhaustion: Boolean = true,
    val enablePlanning: Boolean = true,
    val enableParallelSubAgents: Boolean = false,
    val maxParallelSubAgents: Int = 3,
    val enableStreaming: Boolean = true,
    val maxContextMessages: Int = 50,
    val truncationStrategy: String = "HYBRID",
    val promptRole: String = "ASSISTANT",
    val autoSaveEnabled: Boolean = true,
)

@Serializable
data class UiSection(
    val theme: String = "auto",             // light / dark / auto
    val showThinking: Boolean = true,
    val compactMode: Boolean = false,
    val fontSize: Int = 14,
    val codeBlockTheme: String = "auto",
    val streamMarkdownLive: Boolean = true,
    val animationSpeed: Double = 1.0,       // 0 = off, 0.5 = slow, 1 = normal, 2 = fast
    val sidebarCollapsed: Boolean = true,
    val language: String = "zh-CN",         // i18n
)

@Serializable
data class EditorSection(
    val autoAttachSelection: Boolean = true,
    val autoAttachFileContext: Boolean = true,
    val maxContextFiles: Int = 10,
    val autoSaveOnSend: Boolean = false,
)

@Serializable
data class ShortcutsSection(
    val send: String = "Enter",
    val newLine: String = "Shift+Enter",
    val stop: String = "Escape",
    val commandPalette: String = "Cmd+K",
    val toggleThinking: String = "Cmd+Shift+T",
    val switchModel: String = "Cmd+/",
    val toggleSidebar: String = "Cmd+B",
    val newSession: String = "Cmd+N",
)

@Serializable
data class McpServerEntry(
    val id: String,
    val name: String,
    val transport: String = "stdio",         // stdio / http / websocket
    val command: String = "",                // for stdio
    val args: List<String> = emptyList(),
    val url: String = "",                    // for http/websocket
    val enabled: Boolean = true,
    val env: Map<String, String> = emptyMap(),
)

@Serializable
data class McpSection(
    val servers: List<McpServerEntry> = emptyList(),
)

@Serializable
data class AdvancedSection(
    val enableTelemetry: Boolean = false,
    val telemetryEndpoint: String = "",
    val logLevel: String = "INFO",           // TRACE / DEBUG / INFO / WARN / ERROR
    val autoUpdate: Boolean = true,
    val checkUpdateIntervalHours: Int = 24,
    val experimentalFeatures: List<String> = emptyList(),
    val customCss: String = "",
)

/**
 * 默认 settings.json 内容
 */
object DefaultSettings {
    fun create(): SettingsFile = SettingsFile()

    /**
     * 预置 Provider 模板(只在新文件首次创建时写入)
     */
    val DEFAULT_PROVIDERS = listOf(
        ProviderEntry(
            id = "minimax-default",
            name = "MiniMax",
            type = "minimax",
            baseUrl = "https://api.minimaxi.com",
            enabled = true,
            apiKeyRef = "keychain:minimax-default",
            models = listOf(
                ModelEntry(
                    id = "MiniMax-M2.7",
                    label = "M2.7",
                    contextSize = 128000,
                    supportsTools = true,
                    isDefault = true
                ),
                ModelEntry(
                    id = "MiniMax-M2.7-highspeed",
                    label = "M2.7 (高速)",
                    contextSize = 128000,
                    supportsTools = true
                ),
                ModelEntry(id = "MiniMax-M2.5", label = "M2.5", contextSize = 128000, supportsTools = true),
            ),
        ),
    )

    /**
     * JSON 序列化/反序列化配置
     */
    val JSON = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }
}
