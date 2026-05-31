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
class MCPServerManager(
    private val skillRegistry: SkillRegistry = SkillRegistry.getInstance()
) {
    private val logger = Logger.getLogger<MCPServerManager>()

    private val servers = ConcurrentHashMap<String, MCPClient>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 添加MCP服务器
     */
    suspend fun addServer(config: MCPServerConfig): MCPServerStatus {
        logger.info("Adding MCP server: ${config.name}")

        val client = MCPClient(config)
        val connection = client.connect()

        return if (connection != null) {
            servers[config.id] = client

            // 同步工具列表到技能注册中心
            val tools = client.listTools()
            syncToolsToRegistry(config.id, skillRegistry, tools)

            logger.info("MCP server added: ${config.name}, tools: ${tools.size}")
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
     * 获取服务器工具列表
     */
    suspend fun listTools(serverId: String): List<McpTool> {
        return servers[serverId]?.listTools() ?: emptyList()
    }

    /**
     * 获取所有服务器状态
     */
    fun getAllServerStatuses(): Map<String, MCPServerStatus> {
        return servers.mapValues { (_, client) ->
            if (client.isConnected()) MCPServerStatus.CONNECTED
            else MCPServerStatus.DISCONNECTED
        }
    }

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
        tools.forEach { tool ->
            val skill = MCPDelegatingSkill(
                id = "mcp_${serverId}_${tool.name}",
                toolName = tool.name,
                serverId = serverId,
                tool = tool,
                serverManager = this
            )
            registry.register(skill)
        }
        logger.info("Synced ${tools.size} MCP tools from $serverId to registry")
    }
}
