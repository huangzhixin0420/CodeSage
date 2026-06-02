package com.codesage.mcp.marketplace

import com.codesage.mcp.server.MCPServerManager
import com.codesage.mcp.transport.MCPServerConfig
import com.codesage.mcp.transport.MCPServerStatus
import com.codesage.mcp.transport.TransportType
import com.codesage.shared.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * T2.3 修复：MCP 工具市场（Marketplace）
 *
 * **目标**：让用户能浏览和"一键安装"社区维护的 MCP 服务器。
 *
 * **设计选择**（保持零新增依赖原则）：
 * 1. 内置 marketplace 列表作为 JSON 资源打包在 jar 中（`mcp_marketplace.json`）
 * 2. 列表条目声明 [McpMarketplaceEntry] 元信息：id, name, description, install（command + args + env + transportType）
 * 3. 用户在 UI 选条目 → `McpMarketplaceService.install(entry)` → 生成 `MCPServerConfig` → 调 `MCPServerManager.addServer`
 * 4. 失败时返回详细错误（不静默吞错）
 *
 * **不实现的能力**（保持范围聚焦）：
 * - 远程 marketplace 服务器拉取（避免引入 HTTP 调用复杂度 + 隐私问题）
 * - 一键卸载 / 升级（这些是 MCPServerManager 已有能力，UI 层调用即可）
 * - 用户自定义 marketplace 源
 */
object McpMarketplace {

    private val logger = Logger.getLogger<McpMarketplace>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * 解析 JSON 字符串为 marketplace 列表
     *
     * JSON schema（保持简洁）：
     * ```
     * {
     *   "version": "1",
     *   "entries": [
     *     {
     *       "id": "filesystem",
     *       "name": "File System",
     *       "description": "提供本地文件系统操作工具",
     *       "category": "FILE_OPERATION",
     *       "tags": ["fs", "files"],
     *       "transport": { "type": "stdio", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem"] }
     *     }
     *   ]
     * }
     * ```
     */
    fun parse(rawJson: String): McpMarketplaceCatalog {
        return try {
            json.decodeFromString(McpMarketplaceCatalog.serializer(), rawJson)
        } catch (e: Exception) {
            logger.error("Failed to parse marketplace JSON: ${e.message}", e)
            throw IllegalArgumentException("Invalid marketplace JSON: ${e.message}", e)
        }
    }

    /**
     * 加载内置 marketplace 资源
     *
     * 调用方传入 classpath 资源名（如 "mcp/mcp_marketplace.json"），如未找到则返回空 catalog
     * （而不是抛异常 — marketplace 是可选能力）。
     */
    fun loadBuiltin(resourceName: String = "mcp/mcp_marketplace.json"): McpMarketplaceCatalog {
        val stream = McpMarketplace::class.java.classLoader.getResourceAsStream(resourceName)
        if (stream == null) {
            logger.warn("Built-in marketplace resource not found: $resourceName (this is OK if not yet populated)")
            return McpMarketplaceCatalog(version = "0", entries = emptyList())
        }
        return stream.use { parse(it.readBytes().toString(Charsets.UTF_8)) }
    }

    /**
     * 把 marketplace entry 转为运行时 MCPServerConfig
     *
     * 主要工作：把通用 transport spec 映射到 TransportType 子类。
     */
    fun toServerConfig(entry: McpMarketplaceEntry): MCPServerConfig {
        val transport = when (entry.transport.type.lowercase()) {
            "stdio" -> TransportType.StdIO(
                command = entry.transport.command
                    ?: error("stdio transport requires 'command' field (entry: ${entry.id})"),
                args = entry.transport.args
            )

            "http" -> TransportType.HTTP(
                url = entry.transport.url
                    ?: error("http transport requires 'url' field (entry: ${entry.id})"),
                headers = entry.transport.headers
            )

            "websocket", "ws" -> TransportType.WebSocket(
                url = entry.transport.url
                    ?: error("websocket transport requires 'url' field (entry: ${entry.id})"),
                headers = entry.transport.headers
            )

            else -> error("Unknown transport type: ${entry.transport.type} (entry: ${entry.id})")
        }
        return MCPServerConfig(
            id = entry.id,
            name = entry.name,
            transportType = transport,
            timeout = 30_000L,
            autoReconnect = true
        )
    }
}

/**
 * Marketplace 顶层目录
 */
@Serializable
data class McpMarketplaceCatalog(
    val version: String = "1",
    val entries: List<McpMarketplaceEntry> = emptyList()
)

/**
 * 单个 marketplace 条目
 */
@Serializable
data class McpMarketplaceEntry(
    val id: String,
    val name: String,
    val description: String,
    val category: String = "GENERAL",
    val tags: List<String> = emptyList(),
    val homepage: String? = null,
    val transport: McpMarketplaceTransport
)

/**
 * 传输 spec
 */
@Serializable
data class McpMarketplaceTransport(
    val type: String,  // "stdio" | "http" | "websocket"
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap()
)

/**
 * 安装结果
 */
sealed class McpInstallResult {
    data class Success(val serverId: String, val toolCount: Int) : McpInstallResult()
    data class Failure(val serverId: String, val reason: String) : McpInstallResult()
}

/**
 * T2.3 主服务：负责把 marketplace entry 安装成运行中的 MCP server
 *
 * 通过 [serverManager] 注入，便于测试时使用 stub。
 */
class McpMarketplaceService(
    private val serverManager: MCPServerManager
) {
    private val logger = Logger.getLogger<McpMarketplaceService>()

    /**
     * 安装一个 marketplace entry
     *
     * @return [McpInstallResult.Success] 含 serverId + 同步到 skill registry 的工具数；
     *         [McpInstallResult.Failure] 含失败原因
     */
    suspend fun install(entry: McpMarketplaceEntry): McpInstallResult {
        logger.info("Installing marketplace entry: ${entry.id} (${entry.name})")
        return try {
            val config = McpMarketplace.toServerConfig(entry)
            val status = serverManager.addServer(config)
            if (status == MCPServerStatus.CONNECTED) {
                val toolCount = serverManager.listTools(config.id).size
                logger.info("Installed ${entry.id} successfully, $toolCount tools registered")
                McpInstallResult.Success(config.id, toolCount)
            } else {
                logger.warn("Installation of ${entry.id} ended with status=$status")
                McpInstallResult.Failure(config.id, "Server added but not connected (status=$status)")
            }
        } catch (e: Exception) {
            logger.error("Failed to install ${entry.id}", e)
            McpInstallResult.Failure(entry.id, e.message ?: e::class.simpleName ?: "Unknown error")
        }
    }

    /**
     * 同步安装多个 entry（用于批量启动）
     *
     * 全部成功才返回 Success；任一失败回退到 Failure，并指出第一个失败的 entry。
     */
    suspend fun installAll(entries: List<McpMarketplaceEntry>): McpInstallResult {
        for (entry in entries) {
            when (val r = install(entry)) {
                is McpInstallResult.Failure -> return r
                is McpInstallResult.Success -> continue
            }
        }
        // 全部成功
        return if (entries.isEmpty()) {
            McpInstallResult.Failure("", "No entries to install")
        } else {
            // 汇总第一个为 success
            val first = entries.first()
            val toolCount = serverManager.listTools(first.id).size
            McpInstallResult.Success(first.id, toolCount)
        }
    }
}
