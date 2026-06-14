package com.codesage.acp

import com.codesage.acp.client.AcpClient
import com.codesage.acp.model.AcpCallToolResult
import com.codesage.acp.model.AcpTool
import com.codesage.acp.server.AcpServer
import com.codesage.acp.transport.InMemoryAcpSessionTransport
import com.codesage.acp.transport.createInMemoryAcpTransportPair
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.UnifiedTool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class AcpServerClientTest {

    private val echoTool = object : UnifiedTool(
        name = "echo",
        description = "Echo the input back",
        parameters = ToolParameters(
            properties = mapOf("input" to ToolProperty("string", "Input to echo")),
            required = listOf("input")
        )
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            val input = args["input"]?.toString() ?: JsonPrimitive("").toString()
            return ToolResult.Success(
                JsonObject(mapOf("echo" to JsonPrimitive(input.trim('"'))))
            )
        }
    }

    private fun createServer(): AcpServer {
        val registry = ToolRegistry().apply { register(echoTool) }
        return AcpServer(
            toolRegistry = registry,
            toolExecutorFactory = { ToolExecutor(project = null, toolRegistry = registry) }
        )
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `client can initialize list tools and call tool over in-memory transport`() = runBlocking {
        val (clientTransport, serverTransport) = createInMemoryAcpTransportPair()
        val server = createServer()
        val serverJob: Job = launch { server.handleSession(serverTransport) }

        val client = AcpClient(clientTransport)
        assertTrue(client.initialize())

        val tools = client.listTools()
        assertEquals(1, tools.size)
        assertEquals("echo", tools.first().name)

        val result = client.callTool(
            name = "echo",
            arguments = JsonObject(mapOf("input" to JsonPrimitive("hello acp")))
        )
        assertFalse(result.isError)
        assertTrue(result.content.isNotEmpty())
        assertTrue(result.content.first().text.contains("hello acp"))

        client.shutdown()
        serverJob.join()
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `calling unknown tool returns error result`() = runBlocking {
        val (clientTransport, serverTransport) = createInMemoryAcpTransportPair()
        val server = createServer()
        val serverJob: Job = launch { server.handleSession(serverTransport) }

        val client = AcpClient(clientTransport)
        client.initialize()

        val result = client.callTool(
            name = "unknown_tool",
            arguments = JsonObject(emptyMap())
        )
        assertTrue(result.isError)
        assertTrue(result.content.first().text.contains("Unknown tool"))

        client.shutdown()
        serverJob.join()
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `calling tool before initialize returns error`() = runBlocking {
        val (clientTransport, serverTransport) = createInMemoryAcpTransportPair()
        val server = createServer()
        val serverJob: Job = launch { server.handleSession(serverTransport) }

        val client = AcpClient(clientTransport)
        val exception = assertThrows(Exception::class.java) {
            runBlocking {
                client.callTool("echo", JsonObject(mapOf("input" to JsonPrimitive("x"))))
            }
        }
        assertTrue(exception.message?.contains("not initialized") == true)

        client.shutdown()
        serverJob.join()
    }
}
