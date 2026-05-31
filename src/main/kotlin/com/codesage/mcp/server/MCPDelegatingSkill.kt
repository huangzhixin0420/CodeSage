package com.codesage.mcp.server

import com.codesage.mcp.client.McpTool
import com.codesage.skill.*

/**
 * MCP委托技能
 * 将MCP工具包装为技能接口
 */
class MCPDelegatingSkill(
    override val id: String,
    private val toolName: String,
    private val serverId: String,
    private val tool: McpTool,
    private val serverManager: MCPServerManager
) : Skill {

    override val name: String = tool.name
    override val description: String = tool.description
    override val version: String = "1.0.0"
    override val category: SkillCategory = SkillCategory.CUSTOM
    override val tags: Set<String> = setOf("mcp", "external")
    override val inputSchema: Map<String, Any> = tool.inputSchema
    override val outputSchema: Map<String, Any> = mapOf(
        "content" to mapOf("type" to "string")
    )

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        return CanExecuteResult(true)
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return serverManager.callTool(serverId, toolName, input.arguments)
    }
}
