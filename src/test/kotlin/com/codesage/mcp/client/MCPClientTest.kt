package com.codesage.mcp.client

import com.codesage.mcp.transport.MCPServerConfig
import com.codesage.mcp.transport.TransportType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MCPClientTest {

    @Test
    fun `should complete initialize handshake`() = runBlocking {
        val transport = TestMCPTransport()
        val config = MCPServerConfig(
            id = "test",
            name = "Test Server",
            transportType = TransportType.StdIO("echo", emptyList())
        )
        val connection = MCPConnection(config, transport)

        assertTrue(connection.connect())

        // Enqueue initialize response
        transport.enqueueResponse(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"test-server","version":"1.0.0"}}}"""
        )

        val initialized = connection.initialize()
        assertTrue(initialized)
        assertEquals("test-server", connection.serverInfo?.name)
        assertEquals("1.0.0", connection.serverInfo?.version)

        // Verify initialize request was sent
        val initRequest = transport.sentMessages.first()
        assertTrue(initRequest.contains(""""method":"initialize""""))

        // Verify notifications/initialized was sent
        val notification =
            transport.sentMessages.find { it.contains(""""method":"notifications/initialized"""") }
        assertNotNull(notification)
    }

    @Test
    fun `should fail initialize when server returns error`() = runBlocking {
        val transport = TestMCPTransport()
        val config = MCPServerConfig(
            id = "test",
            name = "Test Server",
            transportType = TransportType.StdIO("echo", emptyList())
        )
        val connection = MCPConnection(config, transport)

        assertTrue(connection.connect())

        transport.enqueueResponse(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Invalid Request"}}"""
        )

        val initialized = connection.initialize()
        assertFalse(initialized)
    }

    @Test
    fun `should fail initialize when no response`() = runBlocking {
        val transport = TestMCPTransport()
        val config = MCPServerConfig(
            id = "test",
            name = "Test Server",
            transportType = TransportType.StdIO("echo", emptyList())
        )
        val connection = MCPConnection(config, transport)

        assertTrue(connection.connect())
        // No response enqueued -> null response

        val initialized = connection.initialize()
        assertFalse(initialized)
    }

    @Test
    fun `should list tools with correct jsonrpc id`() = runBlocking {
        val transport = TestMCPTransport()
        val config = MCPServerConfig(
            id = "test",
            name = "Test Server",
            transportType = TransportType.StdIO("echo", emptyList())
        )
        val connection = MCPConnection(config, transport)

        connection.connect()
        transport.enqueueResponse(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"test","version":"1.0"}}}"""
        )
        connection.initialize()

        transport.enqueueResponse(
            """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"read_file","description":"Read a file","inputSchema":{}},{"name":"write_file","description":"Write a file","inputSchema":{}}]}}"""
        )

        val tools = connection.listTools()
        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].name)

        val toolsRequest =
            transport.sentMessages.find { it.contains(""""method":"tools/list"""") }
        assertNotNull(toolsRequest)
        assertTrue(toolsRequest!!.contains(""""id":2"""))
    }

    @Test
    fun `should call tool with correct jsonrpc id`() = runBlocking {
        val transport = TestMCPTransport()
        val config = MCPServerConfig(
            id = "test",
            name = "Test Server",
            transportType = TransportType.StdIO("echo", emptyList())
        )
        val connection = MCPConnection(config, transport)

        connection.connect()
        transport.enqueueResponse(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"test","version":"1.0"}}}"""
        )
        connection.initialize()

        transport.enqueueResponse(
            """{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"File contents here"}]}}"""
        )

        val result = connection.callTool("read_file", mapOf("path" to "/tmp/test.txt"))
        assertNotNull(result)
        assertEquals("File contents here", result!!.content)

        val callRequest =
            transport.sentMessages.find { it.contains(""""method":"tools/call"""") }
        assertNotNull(callRequest)
        assertTrue(callRequest!!.contains(""""id":2"""))
        assertTrue(callRequest.contains(""""name":"read_file""""))
        assertTrue(callRequest.contains("""/tmp/test.txt"""))
    }

    @Test
    fun `should use monotonically increasing request ids`() = runBlocking {
        val transport = TestMCPTransport()
        val config = MCPServerConfig(
            id = "test",
            name = "Test Server",
            transportType = TransportType.StdIO("echo", emptyList())
        )
        val connection = MCPConnection(config, transport)

        connection.connect()
        transport.enqueueResponse(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"test","version":"1.0"}}}"""
        )
        connection.initialize()

        transport.enqueueResponse(
            """{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""
        )
        connection.listTools()

        transport.enqueueResponse(
            """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"ok"}]}}"""
        )
        connection.callTool("t", emptyMap())

        val ids = transport.sentMessages
            .mapNotNull { msg ->
                Regex(""""id":(\d+)""").find(msg)?.groupValues?.get(1)?.toInt()
            }

        assertEquals(listOf(1, 2, 3), ids)
    }

    private class TestMCPTransport : MCPTransport {
        private var connected = false
        private val responseQueue = ArrayDeque<String>()
        val sentMessages = mutableListOf<String>()

        override suspend fun connect(): Boolean {
            connected = true
            return true
        }

        override suspend fun disconnect() {
            connected = false
        }

        override suspend fun send(message: String): String? {
            sentMessages.add(message)
            return responseQueue.removeFirstOrNull()
        }

        override suspend fun sendNotification(message: String) {
            sentMessages.add(message)
        }

        override fun isConnected(): Boolean = connected

        fun enqueueResponse(response: String) {
            responseQueue.add(response)
        }
    }
}
