package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.FunctionalToolHandler
import com.codesage.agent.tools.ToolHandler
import com.codesage.mcp.server.MCPServerManager
import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import kotlinx.serialization.json.*

/**
 * 6.11.1 MCP 工具动态发现 Handler
 *
 * 当 MCP 服务器返回的工具数超过 maxTools 上限时，被隐藏的工具不会进入 LLM 的 tools 列表。
 * 模型可通过 `mcp_tool_search` 按 serverId / query 查询所有可用工具，并选择是否要求用户
 * 临时暴露某个工具。
 */
object McpToolHandlers {

    fun createMcpToolSearchHandler(serverManager: MCPServerManager?): ToolHandler {
        return FunctionalToolHandler(mcpToolSearchTool()) { args ->
            if (serverManager == null) {
                return@FunctionalToolHandler com.codesage.agent.tools.ToolResult.Error(
                    "MCP server manager is not available"
                )
            }

            val serverId = args["server_id"]?.jsonPrimitive?.content
            val query = args["query"]?.jsonPrimitive?.content ?: ""

            val results = serverManager.searchAvailableTools(serverId, query)

            com.codesage.agent.tools.ToolResult.Success(
                JsonObject(
                    mapOf(
                        "server_id" to JsonPrimitive(serverId ?: ""),
                        "query" to JsonPrimitive(query),
                        "total" to JsonPrimitive(results.size),
                        "tools" to JsonArray(
                            results.map {
                                JsonObject(
                                    mapOf(
                                        "server_id" to JsonPrimitive(it.serverId),
                                        "name" to JsonPrimitive(it.name),
                                        "description" to JsonPrimitive(it.description),
                                        "is_exposed" to JsonPrimitive(it.isExposed)
                                    )
                                )
                            }
                        )
                    )
                )
            )
        }
    }

    internal fun mcpToolSearchTool(): Tool = Tool(
        name = "mcp_tool_search",
        description = """
            Summary: 动态搜索 MCP 服务器上可用的工具，特别是因数量上限未暴露给 LLM 的工具。
            Use: 当你怀疑某个 MCP server 还有隐藏能力、或 `mcp_tool_search` 本身被暴露时使用。
            Args:
              - server_id (string, optional): 指定 MCP server id；不传则搜索所有已连接 server。
              - query (string, optional): 按工具名或描述过滤的关键词。
            Returns: 工具列表（含 server_id / name / description / is_exposed）。
        """.trimIndent(),
        parameters = ToolParameters(
            type = "object",
            properties = mapOf(
                "server_id" to ToolProperty(
                    type = "string",
                    description = "MCP server id; omit to search all connected servers"
                ),
                "query" to ToolProperty(
                    type = "string",
                    description = "Keyword to filter tools by name or description"
                )
            ),
            required = emptyList()
        )
    )
}
