package com.codesage.mcp.server

import com.codesage.mcp.client.McpTool
import com.codesage.skill.ExecutionContext
import com.codesage.skill.SkillInput
import com.codesage.skill.SkillResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MCPDelegatingSkillTest {

    @Test
    fun `should delegate execute to server manager`() = runBlocking {
        val manager = MCPServerManager()
        val tool = McpTool(
            "test_tool",
            "A test tool",
            mapOf("param1" to mapOf("type" to "string"))
        )
        val skill = MCPDelegatingSkill(
            id = "mcp_srv_test_tool",
            toolName = "test_tool",
            serverId = "srv",
            tool = tool,
            serverManager = manager
        )

        assertEquals("test_tool", skill.name)
        assertEquals("A test tool", skill.description)
        assertEquals("1.0.0", skill.version)
        assertTrue(skill.canExecute(ExecutionContext()).canExecute)

        // Since the server is not actually connected, execute should return failure
        val result = skill.execute(SkillInput(mapOf("param1" to "value1")), ExecutionContext())
        assertFalse(result.isSuccess)
        val failure = result as SkillResult.Failure
        assertTrue(failure.error.contains("Server not found"))
    }

    @Test
    fun `should have correct schemas`() {
        val manager = MCPServerManager()
        val inputSchema = mapOf(
            "path" to mapOf("type" to "string", "description" to "File path")
        )
        val tool = McpTool("file_reader", "Read file", inputSchema)
        val skill = MCPDelegatingSkill(
            id = "mcp_srv_file_reader",
            toolName = "file_reader",
            serverId = "srv",
            tool = tool,
            serverManager = manager
        )

        assertEquals(inputSchema, skill.inputSchema)
        assertEquals(mapOf("content" to mapOf("type" to "string")), skill.outputSchema)
    }

    @Test
    fun `should format id correctly`() {
        val manager = MCPServerManager()
        val tool = McpTool("my_tool", "My Tool", emptyMap())
        val skill = MCPDelegatingSkill(
            id = "mcp_server1_my_tool",
            toolName = "my_tool",
            serverId = "server1",
            tool = tool,
            serverManager = manager
        )

        assertEquals("mcp_server1_my_tool", skill.id)
        assertEquals(setOf("mcp", "external"), skill.tags)
    }
}
