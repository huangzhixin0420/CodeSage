package com.codesage.mcp.server

import com.codesage.mcp.client.MCPClient
import com.codesage.mcp.client.MCPResource
import com.codesage.mcp.client.McpTool
import com.codesage.mcp.transport.*
import com.codesage.skill.*
import com.codesage.skill.registry.SkillRegistry
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP服务器管理器
 * 管理多个MCP服务器连接
 */
open class MCPServerManager(
    private val skillRegistry: SkillRegistry = SkillRegistry.getInstance()
) {
    private val logger = Logger.getLogger<MCPServerManager>()

    private val servers = ConcurrentHashMap<String, MCPClient>()

    // T2.2 修复：保存 server config 以便健康监控重连
    private val serverConfigs = ConcurrentHashMap<String, MCPServerConfig>()

    // 6.11.1：保存每个 server 返回的完整工具列表，用于动态发现。
    // SkillRegistry 中只注册了按 maxTools/allow/deny 过滤后的子集。
    private val discoveredTools = ConcurrentHashMap<String, List<McpTool>>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 添加MCP服务器
     */
    open suspend fun addServer(config: MCPServerConfig): MCPServerStatus {
        logger.info("Adding MCP server: ${config.name}")
        // T2.2 修复：保存 config 以便重连
        serverConfigs[config.id] = config

        val client = MCPClient(config)
        val connection = client.connect()

        return if (connection != null) {
            servers[config.id] = client

            // 同步工具列表到技能注册中心
            val tools = client.listTools()
            discoveredTools[config.id] = tools
            syncToolsToRegistry(config.id, skillRegistry, tools)

            logger.info("MCP server added: ${config.name}, tools: ${tools.size}, exposed: ${skillRegistry.count()}")
            MCPServerStatus.CONNECTED
        } else {
            MCPServerStatus.ERROR
        }
    }

    /**
     * 移除MCP服务器
     */
    suspend fun removeServer(serverId: String) {
        servers[serverId]?.disconnect()
        servers.remove(serverId)
        serverConfigs.remove(serverId)
        discoveredTools.remove(serverId)

        // 移除该服务器注册的所有技能
        skillRegistry.getAll()
            .filter { it.id.startsWith("mcp_${serverId}_") }
            .forEach { skillRegistry.unregister(it.id) }

        logger.info("MCP server removed: $serverId")
    }

    /**
     * 调用MCP工具
     */
    suspend fun callTool(
        serverId: String,
        toolName: String,
        args: Map<String, Any>
    ): SkillResult {
        val client = servers[serverId]
            ?: return SkillResult.Failure("Server not found: $serverId")

        if (!client.isConnected()) {
            return SkillResult.Failure("Server not connected: $serverId")
        }

        return try {
            val result = client.callTool(toolName, args)
            if (result != null) {
                SkillResult.Success(mapOf("content" to result.content))
            } else {
                SkillResult.Failure("Tool call failed")
            }
        } catch (e: Exception) {
            SkillResult.Failure("Tool call error: ${e.message}", e)
        }
    }

    /**
     * 获取服务器工具列表（实时从连接获取）
     */
    open suspend fun listTools(serverId: String): List<McpTool> {
        return servers[serverId]?.listTools() ?: emptyList()
    }

    /**
     * 6.11.1：获取某个服务器已发现的所有工具（含未暴露给 LLM 的）。
     */
    fun listAllDiscoveredTools(serverId: String): List<McpTool> {
        return discoveredTools[serverId] ?: emptyList()
    }

    /**
     * 6.11.1：跨所有已连接服务器搜索可用工具。
     *
     * @param serverId 为空时搜索所有服务器
     * @param query 按工具名或描述匹配（忽略大小写）
     * @return 工具信息列表，包含是否已向 LLM 暴露
     */
    fun searchAvailableTools(serverId: String?, query: String): List<McpToolAvailability> {
        val lowerQuery = query.lowercase()
        val sourceServers = if (serverId != null) {
            mapOf(serverId to (discoveredTools[serverId] ?: emptyList()))
        } else {
            discoveredTools.toMap()
        }

        return sourceServers.flatMap { (srvId, tools) ->
            tools.filter { tool ->
                lowerQuery.isBlank() ||
                        tool.name.lowercase().contains(lowerQuery) ||
                        tool.description.lowercase().contains(lowerQuery)
            }.map { tool ->
                val skillId = "mcp_${srvId}_${tool.name}"
                McpToolAvailability(
                    serverId = srvId,
                    name = tool.name,
                    description = tool.description,
                    isExposed = skillRegistry.contains(skillId)
                )
            }
        }
    }

    /**
     * 获取所有服务器状态
     */
    open fun getAllServerStatuses(): Map<String, MCPServerStatus> {
        return servers.mapValues { (_, client) ->
            if (client.isConnected()) MCPServerStatus.CONNECTED
            else MCPServerStatus.DISCONNECTED
        }
    }

    /**
     * T2.2 修复：获取 server config（用于健康监控重连）
     */
    open fun serverConfigOf(serverId: String): MCPServerConfig? = serverConfigs[serverId]

    /**
     * 断开所有连接
     */
    suspend fun disconnectAll() {
        logger.info("Disconnecting all MCP servers...")
        servers.values.forEach { it.disconnect() }
        servers.clear()
        scope.cancel()
    }

    /**
     * 将MCP工具同步到指定的技能注册表
     */
    fun syncToolsToRegistry(
        serverId: String,
        registry: SkillRegistry,
        tools: List<McpTool>
    ) {
        val config = serverConfigs[serverId]
        val filteredTools = if (config != null) {
            McpToolFilter.apply(tools, config)
        } else {
            // 无配置时按安全默认值处理：最多 40 个，无额外权限规则
            McpToolFilter.apply(
                tools,
                MCPServerConfig(id = serverId, name = serverId, transportType = TransportType.StdIO("", emptyList()))
            )
        }

        filteredTools.forEach { tool ->
            val skill = MCPDelegatingSkill(
                id = "mcp_${serverId}_${tool.name}",
                toolName = tool.name,
                serverId = serverId,
                tool = tool,
                serverManager = this
            )
            registry.register(skill)
        }
        logger.info("Synced ${filteredTools.size}/${tools.size} MCP tools from $serverId to registry")
    }

    /**
     * 6.11.1：测试/重连用重载，可显式传入配置完成过滤。
     */
    fun syncToolsToRegistry(
        serverId: String,
        registry: SkillRegistry,
        tools: List<McpTool>,
        config: MCPServerConfig
    ) {
        serverConfigs[serverId] = config
        discoveredTools[serverId] = tools
        syncToolsToRegistry(serverId, registry, tools)
    }

    /**
     * 动态发现结果
     */
    data class McpToolAvailability(
        val serverId: String,
        val name: String,
        val description: String,
        val isExposed: Boolean
    )
}
