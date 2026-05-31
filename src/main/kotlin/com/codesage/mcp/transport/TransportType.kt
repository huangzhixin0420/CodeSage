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
    val autoReconnect: Boolean = true
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