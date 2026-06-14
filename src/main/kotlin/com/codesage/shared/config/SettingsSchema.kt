package com.codesage.shared.config

import com.codesage.model.dto.AcpSection
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
    val acp: AcpSection = AcpSection(),
    val advanced: AdvancedSection = AdvancedSection(),
    val network: NetworkSection = NetworkSection(),
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
    /**
     * 6.11.1：覆盖全局的每服务器工具数量上限。null 表示使用 McpSection.maxToolsPerServer。
     */
    val maxTools: Int? = null,
    /**
     * 6.11.2：该服务器允许暴露的工具白名单（支持 `*` / `?` 通配符）。空列表表示不限制。
     */
    val allowedTools: List<String> = emptyList(),
    /**
     * 6.11.2：该服务器拒绝暴露的工具黑名单（支持 `*` / `?` 通配符）。优先于 allowedTools。
     */
    val deniedTools: List<String> = emptyList(),
)

@Serializable
data class McpSection(
    val servers: List<McpServerEntry> = emptyList(),
    /**
     * 6.11.1：每个 MCP 服务器默认向 LLM 暴露的最大工具数。
     */
    val maxToolsPerServer: Int = 40,
    /**
     * 6.11.1：是否启用 `mcp_tool_search` 动态工具发现。
     */
    val enableDynamicDiscovery: Boolean = true,
)

/**
 * 网络设置 v1
 *
 * 当前只有代理配置;后续要加 CA 证书、HTTP/2 设置等可以在这里扩展。
 *
 * 代理模式 (mode):
 *   - "system" : 走 IntelliJ HTTP Proxy 设置(默认,JVM ProxySelector)
 *   - "manual" : 用本节手动配置的代理
 *   - "direct" : 完全直连,不走任何代理
 *
 * 手动配置下:
 *   - type = "http" : HTTP 代理(RFC 7230,CONNECT 走 http tunnel)
 *   - type = "socks" : SOCKS5 代理
 *   - host/port : 代理主机端口
 *   - username/passwordRef : 代理认证;
 *       passwordRef 形如 "passwordsafe:codesage.network.proxy" —
 *       实际密码存在 IntelliJ PasswordSafe,settings.json 只存引用。
 *   - noProxy : 命中规则(域名/IP)列表,这些 URL 走直连,跳过代理
 *
 * 优先级:JVM/IntelliJ 代理 < 本节配置 < (未来)按请求覆盖
 */
@Serializable
data class NetworkSection(
    val proxy: ProxyConfig = ProxyConfig(),
)

@Serializable
data class ProxyConfig(
    val mode: String = "system",             // system / manual / direct
    val type: String = "http",               // http / socks(仅 manual 模式有意义)
    val host: String = "",
    val port: Int = 0,
    val username: String = "",               // 空 = 不需要认证
    val passwordRef: String = "",            // passwordsafe:codesage.network.proxy
    val noProxy: List<String> = emptyList(), // 主机名/IP 列表,匹配则直连
)

@Serializable
data class AdvancedSection(
    val enableTelemetry: Boolean = false,
    val telemetryEndpoint: String = "http://localhost:4318/v1/traces",
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
