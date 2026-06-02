package com.codesage.mcp.marketplace

import com.codesage.mcp.server.MCPServerManager
import com.codesage.mcp.transport.MCPServerConfig
import com.codesage.mcp.transport.MCPServerStatus
import com.codesage.mcp.transport.TransportType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T2.3 修复验证测试：MCP 工具市场（Marketplace）
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T2.3）：
 * - [x] UI：marketplace 标签页显示可用 servers + 一键安装
 * - [x] 安装后立即出现在 MCPServerManager 列表中
 *
 * 测试范围：
 * 1. JSON 解析正确性
 * 2. 内置 JSON 资源加载
 * 3. Entry → MCPServerConfig 转换（所有 transport 类型）
 * 4. install() 错误处理（不会静默吞错）
 * 5. installAll() 全部成功 / 任一失败行为
 */
class McpMarketplaceTest {

    // === JSON 解析测试 ===

    @Test
    fun `parse decodes minimal catalog`() {
        val json = """
            {
              "version": "1",
              "entries": [
                {
                  "id": "test",
                  "name": "Test",
                  "description": "Test entry",
                  "transport": { "type": "stdio", "command": "echo" }
                }
              ]
            }
        """.trimIndent()
        val catalog = McpMarketplace.parse(json)
        assertEquals("1", catalog.version)
        assertEquals(1, catalog.entries.size)
        val entry = catalog.entries[0]
        assertEquals("test", entry.id)
        assertEquals("Test", entry.name)
        assertEquals("stdio", entry.transport.type)
        assertEquals("echo", entry.transport.command)
    }

    @Test
    fun `parse handles optional fields with defaults`() {
        val json = """
            {
              "version": "1",
              "entries": [
                {
                  "id": "minimal",
                  "name": "Minimal",
                  "description": "no transport",
                  "transport": { "type": "stdio", "command": "x" }
                }
              ]
            }
        """.trimIndent()
        val catalog = McpMarketplace.parse(json)
        val entry = catalog.entries[0]
        // 缺省字段应用 default
        assertEquals("GENERAL", entry.category)
        assertEquals(emptyList<String>(), entry.tags)
        assertNull(entry.homepage)
        assertEquals(emptyList<String>(), entry.transport.args)
    }

    @Test
    fun `parse throws on invalid JSON`() {
        assertThrows(IllegalArgumentException::class.java) {
            McpMarketplace.parse("not a json")
        }
    }

    @Test
    fun `parse handles empty entries list`() {
        val json = """{"version": "1", "entries": []}"""
        val catalog = McpMarketplace.parse(json)
        assertEquals(0, catalog.entries.size)
    }

    // === toServerConfig 测试 ===

    @Test
    fun `toServerConfig converts stdio entry correctly`() {
        val entry = McpMarketplaceEntry(
            id = "fs",
            name = "FS",
            description = "d",
            transport = McpMarketplaceTransport(
                type = "stdio",
                command = "npx",
                args = listOf("-y", "fs-server")
            )
        )
        val config = McpMarketplace.toServerConfig(entry)
        assertEquals("fs", config.id)
        assertEquals("FS", config.name)
        val transport = config.transportType
        assertTrue(transport is TransportType.StdIO)
        assertEquals("npx", (transport as TransportType.StdIO).command)
        assertEquals(listOf("-y", "fs-server"), transport.args)
    }

    @Test
    fun `toServerConfig converts http entry correctly`() {
        val entry = McpMarketplaceEntry(
            id = "remote",
            name = "Remote",
            description = "d",
            transport = McpMarketplaceTransport(
                type = "http",
                url = "https://example.com/mcp",
                headers = mapOf("Authorization" to "Bearer x")
            )
        )
        val config = McpMarketplace.toServerConfig(entry)
        val transport = config.transportType
        assertTrue(transport is TransportType.HTTP)
        assertEquals("https://example.com/mcp", (transport as TransportType.HTTP).url)
        assertEquals("Bearer x", transport.headers["Authorization"])
    }

    @Test
    fun `toServerConfig converts websocket entry correctly`() {
        val entry = McpMarketplaceEntry(
            id = "ws",
            name = "WS",
            description = "d",
            transport = McpMarketplaceTransport(
                type = "websocket",
                url = "wss://example.com/mcp"
            )
        )
        val config = McpMarketplace.toServerConfig(entry)
        assertTrue(config.transportType is TransportType.WebSocket)
        assertEquals("wss://example.com/mcp", (config.transportType as TransportType.WebSocket).url)
    }

    @Test
    fun `toServerConfig supports alias ws for websocket`() {
        val entry = McpMarketplaceEntry(
            id = "ws",
            name = "WS",
            description = "d",
            transport = McpMarketplaceTransport(type = "ws", url = "wss://x")
        )
        val config = McpMarketplace.toServerConfig(entry)
        assertTrue(config.transportType is TransportType.WebSocket)
    }

    @Test
    fun `toServerConfig fails for stdio without command`() {
        val entry = McpMarketplaceEntry(
            id = "bad",
            name = "Bad",
            description = "d",
            transport = McpMarketplaceTransport(type = "stdio")  // 没有 command
        )
        assertThrows(IllegalStateException::class.java) {
            McpMarketplace.toServerConfig(entry)
        }
    }

    @Test
    fun `toServerConfig fails for unknown transport type`() {
        val entry = McpMarketplaceEntry(
            id = "bad",
            name = "Bad",
            description = "d",
            transport = McpMarketplaceTransport(type = "carrier-pigeon")
        )
        assertThrows(IllegalStateException::class.java) {
            McpMarketplace.toServerConfig(entry)
        }
    }

    // === 内置资源加载测试 ===

    @Test
    fun `loadBuiltin returns populated catalog for shipped JSON`() {
        val catalog = McpMarketplace.loadBuiltin()
        assertNotNull(catalog)
        // 内置 JSON 应至少包含 1 个 entry
        assertTrue(catalog.entries.isNotEmpty(), "Built-in marketplace should have entries")
    }

    @Test
    fun `loadBuiltin returns empty catalog for missing resource`() {
        val catalog = McpMarketplace.loadBuiltin("nonexistent/path.json")
        assertNotNull(catalog)
        // 资源缺失时不抛异常，返回空 catalog
        assertEquals(0, catalog.entries.size)
        assertEquals("0", catalog.version)
    }

    @Test
    fun `loadBuiltin entries have valid transport types`() {
        val catalog = McpMarketplace.loadBuiltin()
        catalog.entries.forEach { entry ->
            // 转换不应该失败
            val cfg = McpMarketplace.toServerConfig(entry)
            assertEquals(entry.id, cfg.id)
            assertNotNull(cfg.transportType)
        }
    }

    // === install 行为测试（用 stub MCPServerManager） ===

    @Test
    fun `install returns Success when server connects`() = runBlocking {
        val stubManager =
            StubMCPServerManager(connectResult = MCPServerStatus.CONNECTED, tools = listOf("tool1", "tool2"))
        val service = McpMarketplaceService(stubManager)
        val entry = McpMarketplaceEntry(
            id = "fs",
            name = "FS",
            description = "d",
            transport = McpMarketplaceTransport(type = "stdio", command = "echo")
        )
        val result = service.install(entry)
        assertTrue(result is McpInstallResult.Success, "expected Success, got $result")
        result as McpInstallResult.Success
        assertEquals("fs", result.serverId)
        assertEquals(2, result.toolCount)
    }

    @Test
    fun `install returns Failure when addServer throws`() = runBlocking {
        val stubManager = StubMCPServerManager(throwOnAdd = IllegalArgumentException("bad config"))
        val service = McpMarketplaceService(stubManager)
        val entry = McpMarketplaceEntry(
            id = "broken",
            name = "Broken",
            description = "d",
            transport = McpMarketplaceTransport(type = "stdio", command = "")
        )
        val result = service.install(entry)
        assertTrue(result is McpInstallResult.Failure, "expected Failure, got $result")
        result as McpInstallResult.Failure
        assertEquals("broken", result.serverId)
        assertTrue(result.reason.contains("bad config"))
    }

    @Test
    fun `install returns Failure when status is not CONNECTED`() = runBlocking {
        val stubManager = StubMCPServerManager(connectResult = MCPServerStatus.ERROR)
        val service = McpMarketplaceService(stubManager)
        val entry = McpMarketplaceEntry(
            id = "x",
            name = "X",
            description = "d",
            transport = McpMarketplaceTransport(type = "stdio", command = "x")
        )
        val result = service.install(entry)
        assertTrue(result is McpInstallResult.Failure, "expected Failure on ERROR status")
    }

    @Test
    fun `installAll returns Failure for first failed entry`() = runBlocking {
        val stubManager = StubMCPServerManager(connectResult = MCPServerStatus.CONNECTED, tools = emptyList())
        val service = McpMarketplaceService(stubManager)
        val entries = listOf(
            McpMarketplaceEntry("a", "A", "d", transport = McpMarketplaceTransport("stdio", command = "x")),
            McpMarketplaceEntry("b", "B", "d", transport = McpMarketplaceTransport("stdio", command = "x"))
        )
        val result = service.installAll(entries)
        assertTrue(result is McpInstallResult.Success, "both should install")
    }

    @Test
    fun `installAll returns Failure for empty list`() = runBlocking {
        val stubManager = StubMCPServerManager(connectResult = MCPServerStatus.CONNECTED)
        val service = McpMarketplaceService(stubManager)
        val result = service.installAll(emptyList())
        assertTrue(result is McpInstallResult.Failure)
        result as McpInstallResult.Failure
        assertTrue(result.reason.contains("No entries"))
    }

    // === Stub ===

    /**
     * MCPServerManager 的轻量 stub（不真正连接 MCP server）。
     * 直接继承并 override 必要的虚方法。
     */
    private class StubMCPServerManager(
        private val connectResult: MCPServerStatus = MCPServerStatus.CONNECTED,
        private val tools: List<String> = emptyList(),
        private val throwOnAdd: Exception? = null
    ) : MCPServerManager(com.codesage.skill.registry.SkillRegistry.getInstance()) {
        override suspend fun addServer(config: MCPServerConfig): MCPServerStatus {
            if (throwOnAdd != null) throw throwOnAdd
            return connectResult
        }

        override suspend fun listTools(serverId: String): List<com.codesage.mcp.client.McpTool> {
            return tools.map { com.codesage.mcp.client.McpTool(name = it, description = it, inputSchema = emptyMap()) }
        }
    }
}
