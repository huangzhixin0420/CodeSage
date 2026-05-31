package com.codesage.mcp.server

import com.codesage.mcp.client.McpTool
import com.codesage.skill.SkillCategory
import com.codesage.skill.SkillResult
import com.codesage.skill.registry.DynamicSkillRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MCPServerManagerTest {

    @Test
    fun `should sync tools to registry`() {
        val registry = DynamicSkillRegistry()
        val manager = MCPServerManager(registry)

        val tools = listOf(
            McpTool(
                "read_file",
                "Read a file",
                mapOf("path" to mapOf("type" to "string"))
            ),
            McpTool(
                "write_file",
                "Write a file",
                mapOf(
                    "path" to mapOf("type" to "string"),
                    "content" to mapOf("type" to "string")
                )
            )
        )

        manager.syncToolsToRegistry("test_server", registry, tools)

        val skill1 = registry.get("mcp_test_server_read_file")
        assertNotNull(skill1)
        assertEquals("read_file", skill1?.name)
        assertEquals("Read a file", skill1?.description)
        assertEquals(SkillCategory.CUSTOM, skill1?.category)
        assertTrue(skill1?.tags?.contains("mcp") == true)

        val skill2 = registry.get("mcp_test_server_write_file")
        assertNotNull(skill2)
        assertEquals("write_file", skill2?.name)

        assertEquals(2, registry.count())
    }

    @Test
    fun `should sync tools to external registry without affecting manager default registry`() {
        val managerRegistry = DynamicSkillRegistry()
        val externalRegistry = DynamicSkillRegistry()
        val manager = MCPServerManager(managerRegistry)

        val tools = listOf(
            McpTool("tool_a", "Tool A", emptyMap())
        )

        manager.syncToolsToRegistry("srv", externalRegistry, tools)

        assertNotNull(externalRegistry.get("mcp_srv_tool_a"))
        assertEquals(0, managerRegistry.count())
    }

    @Test
    fun `should remove server and unregister skills`() = runBlocking {
        val registry = DynamicSkillRegistry()
        val manager = MCPServerManager(registry)

        val tools = listOf(
            McpTool("tool1", "Tool 1", emptyMap()),
            McpTool("tool2", "Tool 2", emptyMap())
        )

        manager.syncToolsToRegistry("srv1", registry, tools)
        assertEquals(2, registry.count())

        manager.removeServer("srv1")

        assertNull(registry.get("mcp_srv1_tool1"))
        assertNull(registry.get("mcp_srv1_tool2"))
        assertEquals(0, registry.count())
    }

    @Test
    fun `callTool should return failure for unknown server`() = runBlocking {
        val manager = MCPServerManager()
        val result = manager.callTool("unknown", "tool", emptyMap())
        assertFalse(result.isSuccess)
        val failure = result as SkillResult.Failure
        assertTrue(failure.error.contains("Server not found"))
    }
}
