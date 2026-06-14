package com.codesage.mcp.server

import com.codesage.mcp.client.McpTool
import com.codesage.mcp.transport.MCPServerConfig
import com.codesage.mcp.transport.TransportType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpToolFilterTest {

    private fun dummyConfig(
        maxTools: Int = 40,
        allowed: List<String> = emptyList(),
        denied: List<String> = emptyList()
    ) = MCPServerConfig(
        id = "srv",
        name = "srv",
        transportType = TransportType.StdIO("", emptyList()),
        maxTools = maxTools,
        allowedTools = allowed,
        deniedTools = denied
    )

    @Test
    fun `wildcard matching should work for asterisk and question mark`() {
        assertTrue(McpToolFilter.matches("read_file", "read_*"))
        assertTrue(McpToolFilter.matches("read_secret", "read_*"))
        assertFalse(McpToolFilter.matches("write_file", "read_*"))

        assertTrue(McpToolFilter.matches("tool_a", "tool_?"))
        assertFalse(McpToolFilter.matches("tool_ab", "tool_?"))

        assertTrue(McpToolFilter.matches("FETCH Weather", "*weather*"))
    }

    @Test
    fun `deny rules should override allow rules`() {
        val allowed = McpToolFilter.isAllowed(
            "read_secret",
            allowed = listOf("read_*"),
            denied = listOf("*_secret")
        )
        assertFalse(allowed)
    }

    @Test
    fun `allowed list empty means allow all except denied`() {
        assertTrue(McpToolFilter.isAllowed("anything", emptyList(), emptyList()))
        assertFalse(McpToolFilter.isAllowed("bad_tool", emptyList(), listOf("bad_*")))
    }

    @Test
    fun `apply should enforce allow deny and maxTools`() {
        val tools = listOf(
            McpTool("read_file", "Read", emptyMap()),
            McpTool("read_secret", "Read secret", emptyMap()),
            McpTool("write_file", "Write", emptyMap()),
            McpTool("list_dir", "List", emptyMap())
        )

        val config = dummyConfig(
            maxTools = 10,
            allowed = listOf("read_*", "write_*"),
            denied = listOf("*_secret")
        )

        val result = McpToolFilter.apply(tools, config)
        assertEquals(2, result.size)
        assertTrue(result.any { it.name == "read_file" })
        assertTrue(result.any { it.name == "write_file" })
        assertFalse(result.any { it.name == "read_secret" })
        assertFalse(result.any { it.name == "list_dir" })
    }

    @Test
    fun `apply should truncate to maxTools preserving order`() {
        val tools = (1..10).map { McpTool("tool_$it", "Tool $it", emptyMap()) }
        val result = McpToolFilter.apply(tools, dummyConfig(maxTools = 3))
        assertEquals(3, result.size)
        assertEquals("tool_1", result[0].name)
        assertEquals("tool_3", result[2].name)
    }
}
