package com.codesage.acp

import com.codesage.acp.client.AcpClient
import com.codesage.acp.server.AcpServer
import com.codesage.acp.server.AcpSocketServer
import com.codesage.acp.transport.SocketAcpSessionTransport
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.UnifiedTool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.Socket
import java.util.concurrent.TimeUnit

class AcpSocketServerTest {

    private val pingTool = object : UnifiedTool(
        name = "ping",
        description = "Respond with pong",
        parameters = ToolParameters(
            properties = mapOf("message" to ToolProperty("string", "Message to echo")),
            required = emptyList()
        )
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            return ToolResult.Success(JsonPrimitive("pong"))
        }
    }

    private fun createSocketServer(): AcpSocketServer {
        return AcpSocketServer(
            sessionFactory = {
                val registry = ToolRegistry().apply { register(pingTool) }
                AcpServer(
                    toolRegistry = registry,
                    toolExecutorFactory = { ToolExecutor(project = null, toolRegistry = registry) }
                )
            },
            port = 0
        )
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `client can connect to ACP socket server and call tool`() {
        val socketServer = createSocketServer()
        socketServer.start()

        // 等待协程中的 ServerSocket 绑定完成
        var port = socketServer.actualPort
        var attempts = 0
        while (port <= 0 && attempts < 50) {
            Thread.sleep(100)
            port = socketServer.actualPort
            attempts++
        }
        assertTrue(port > 0, "Server should be listening on an ephemeral port")

        runBlocking {
            val socket = Socket("127.0.0.1", port)
            val transport = SocketAcpSessionTransport(socket)
            val client = AcpClient(transport)

            assertTrue(client.initialize())

            val tools = client.listTools()
            assertEquals(1, tools.size)
            assertEquals("ping", tools.first().name)

            val result = client.callTool(
                name = "ping",
                arguments = JsonObject(emptyMap())
            )
            assertFalse(result.isError)
            assertTrue(result.content.first().text.contains("pong"))

            client.shutdown()
        }

        socketServer.stop()
    }
}
