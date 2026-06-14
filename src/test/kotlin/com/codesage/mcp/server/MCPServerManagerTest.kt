package com.codesage.mcp.server

import com.codesage.mcp.client.McpTool
import com.codesage.mcp.transport.MCPServerConfig
import com.codesage.mcp.transport.TransportType
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

    @Test
    fun `syncToolsToRegistry should respect allow deny and maxTools`() {
        val registry = DynamicSkillRegistry()
        val manager = MCPServerManager(registry)
        val tools = listOf(
            McpTool("read_file", "Read", emptyMap()),
            McpTool("read_secret", "Read secret", emptyMap()),
            McpTool("write_file", "Write", emptyMap()),
            McpTool("list_dir", "List", emptyMap()),
            McpTool("delete_file", "Delete", emptyMap())
        )
        val config = MCPServerConfig(
            id = "srv",
            name = "srv",
            transportType = com.codesage.mcp.transport.TransportType.StdIO("", emptyList()),
            maxTools = 3,
            allowedTools = listOf("read_*", "write_*", "list_dir", "delete_file"),
            deniedTools = listOf("*_secret")
        )

        manager.syncToolsToRegistry("srv", registry, tools, config)

        assertNotNull(registry.get("mcp_srv_read_file"))
        assertNotNull(registry.get("mcp_srv_write_file"))
        assertNotNull(registry.get("mcp_srv_list_dir"))
        assertNull(registry.get("mcp_srv_read_secret"))
        assertNull(registry.get("mcp_srv_delete_file"))
        assertEquals(3, registry.count())
    }

    @Test
    fun `searchAvailableTools should return all discovered tools with exposure flags`() {
        val registry = DynamicSkillRegistry()
        val manager = MCPServerManager(registry)
        val tools = listOf(
            McpTool("alpha", "Alpha tool", emptyMap()),
            McpTool("beta", "Beta tool", emptyMap()),
            McpTool("gamma", "Gamma tool", emptyMap())
        )
        val config = MCPServerConfig(
            id = "srv",
            name = "srv",
            transportType = com.codesage.mcp.transport.TransportType.StdIO("", emptyList()),
            maxTools = 1
        )

        manager.syncToolsToRegistry("srv", registry, tools, config)

        val all = manager.searchAvailableTools("srv", "")
        assertEquals(3, all.size)
        assertEquals(1, all.count { it.isExposed })
        assertTrue(all.any { it.name == "alpha" && it.isExposed })
        assertTrue(all.any { it.name == "beta" && !it.isExposed })

        val filtered = manager.searchAvailableTools("srv", "gamma")
        assertEquals(1, filtered.size)
        assertEquals("gamma", filtered[0].name)
    }

    @Test
    fun `searchAvailableTools without serverId should search all servers`() {
        val registry = DynamicSkillRegistry()
        val manager = MCPServerManager(registry)

        val toolsA = listOf(McpTool("a1", "A1", emptyMap()))
        val toolsB = listOf(McpTool("b1", "B1", emptyMap()))
        val config = MCPServerConfig(
            id = "x",
            name = "x",
            transportType = com.codesage.mcp.transport.TransportType.StdIO("", emptyList())
        )

        manager.syncToolsToRegistry("srvA", registry, toolsA, config)
        manager.syncToolsToRegistry("srvB", registry, toolsB, config)

        val result = manager.searchAvailableTools(null, "")
        assertEquals(2, result.size)
    }
}
