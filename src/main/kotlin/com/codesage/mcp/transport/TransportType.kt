package com.codesage.mcp.transport

/**
 * 传输类型枚举
 */
sealed class TransportType {
    /**
     * 标准输入输出传输 (用于本地进程)
     */
    data class StdIO(
        val command: String,
        val args: List<String> = emptyList()
    ) : TransportType()

    /**
     * HTTP传输 (用于远程服务器)
     */
    data class HTTP(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    ) : TransportType()

    /**
     * WebSocket传输
     */
    data class WebSocket(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    ) : TransportType()
}

/**
 * MCP服务器配置
 */
data class MCPServerConfig(
    val id: String,
    val name: String,
    val transportType: TransportType,
    val auth: MCPAuthConfig? = null,
    val timeout: Long = 30000,
    val autoReconnect: Boolean = true,
    /**
     * 6.11.1：该服务器最多向 LLM 暴露的工具数量。
     * 超过上限的工具不会注册到 SkillRegistry，但可通过 `mcp_tool_search` 动态发现。
     */
    val maxTools: Int = 40,
    /**
     * 6.11.2：工具白名单（支持 `*` / `?` 通配符）。空列表表示不限制。
     * 命中 deniedTools 的工具优先被拒绝。
     */
    val allowedTools: List<String> = emptyList(),
    /**
     * 6.11.2：工具黑名单（支持 `*` / `?` 通配符）。
     */
    val deniedTools: List<String> = emptyList()
)

/**
 * MCP认证配置
 */
data class MCPAuthConfig(
    val type: AuthType,
    val apiKey: String? = null,
    val token: String? = null
)

enum class AuthType {
    NONE,
    API_KEY,
    BEARER_TOKEN,
    BASIC
}

/**
 * MCP服务器状态
 */
enum class MCPServerStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
