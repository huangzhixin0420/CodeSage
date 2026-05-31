package com.codesage.mcp.client

import com.codesage.mcp.transport.*
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP工具定义
 */
@Serializable
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, @Contextual Any>
)

/**
 * MCP资源
 */
@Serializable
data class MCPResource(
    val content: String,
    val type: String = "text"
)

/**
 * MCP服务器信息
 */
data class MCPServerInfo(
    val name: String,
    val version: String
)

/**
 * MCP连接
 */
class MCPConnection(
    val config: MCPServerConfig,
    private val transport: MCPTransport
) {
    private val logger = Logger.getLogger<MCPConnection>()
    private var isConnected = false
    private val requestIdCounter = AtomicInteger(0)

    var serverInfo: MCPServerInfo? = null
        private set

    suspend fun connect(): Boolean {
        isConnected = transport.connect()
        return isConnected
    }

    suspend fun disconnect() {
        transport.disconnect()
        isConnected = false
        serverInfo = null
    }

    /**
     * 执行 MCP initialize 握手
     * 参考 MCP Spec 2024-11-05
     */
    suspend fun initialize(): Boolean {
        if (!isConnected) {
            logger.warn("Cannot initialize: transport not connected")
            return false
        }

        val requestId = nextRequestId()
        val request = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(requestId),
                "method" to JsonPrimitive("initialize"),
                "params" to JsonObject(
                    mapOf(
                        "protocolVersion" to JsonPrimitive("2024-11-05"),
                        "capabilities" to JsonObject(emptyMap()),
                        "clientInfo" to JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("codesage"),
                                "version" to JsonPrimitive("2026.1.2")
                            )
                        )
                    )
                )
            )
        )

        val response = transport.send(request.toString())
        if (response == null) {
            logger.error("Initialize handshake failed: no response")
            return false
        }

        return try {
            val json = Json.parseToJsonElement(response).jsonObject
            val result = json["result"]?.jsonObject
            if (result != null) {
                val protocolVersion = result["protocolVersion"]?.jsonPrimitive?.content ?: "unknown"
                val serverInfoObj = result["serverInfo"]?.jsonObject
                val serverName = serverInfoObj?.get("name")?.jsonPrimitive?.content ?: "unknown"
                val serverVersion = serverInfoObj?.get("version")?.jsonPrimitive?.content ?: "unknown"
                serverInfo = MCPServerInfo(serverName, serverVersion)

                // Send notifications/initialized
                val notification = JsonObject(
                    mapOf(
                        "jsonrpc" to JsonPrimitive("2.0"),
                        "method" to JsonPrimitive("notifications/initialized")
                    )
                )
                transport.sendNotification(notification.toString())
                logger.info("MCP initialize handshake complete: protocolVersion=$protocolVersion, server=$serverName/$serverVersion")
                true
            } else {
                val error = json["error"]?.jsonObject
                logger.error("Initialize handshake failed: error=$error")
                false
            }
        } catch (e: Exception) {
            logger.error("Failed to parse initialize response", e)
            false
        }
    }

    suspend fun listTools(): List<McpTool> {
        if (!isConnected) return emptyList()

        val requestId = nextRequestId()
        val request = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(requestId),
                "method" to JsonPrimitive("tools/list"),
                "params" to JsonObject(emptyMap())
            )
        )

        val response = transport.send(request.toString())
            ?: return emptyList()

        return try {
            val json = Json.parseToJsonElement(response).jsonObject
            val tools = json["result"]?.jsonObject?.get("tools")?.jsonArray
            tools?.mapNotNull { element ->
                val obj = element.jsonObject
                McpTool(
                    name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    inputSchema = (obj["inputSchema"]?.jsonObject?.toMap()
                        ?: emptyMap()) as Map<String, Any>
                )
            } ?: emptyList()
        } catch (e: Exception) {
            logger.error("Failed to parse tools list", e)
            emptyList()
        }
    }

    suspend fun callTool(toolName: String, arguments: Map<String, Any>): MCPResource? {
        if (!isConnected) return null

        val params = JsonObject(arguments.mapValues { (_, v) ->
            when (v) {
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        })

        val requestId = nextRequestId()
        val request = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(requestId),
                "method" to JsonPrimitive("tools/call"),
                "params" to JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(toolName),
                        "arguments" to params
                    )
                )
            )
        )

        val response = transport.send(request.toString())
            ?: return null

        return try {
            val json = Json.parseToJsonElement(response).jsonObject
            val content = json["result"]?.jsonObject?.get("content")
            val text = content?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content ?: ""

            MCPResource(content = text)
        } catch (e: Exception) {
            logger.error("Failed to parse tool call response", e)
            null
        }
    }

    fun isActive(): Boolean = isConnected && transport.isConnected()

    private fun nextRequestId(): Int = requestIdCounter.incrementAndGet()
}

/**
 * MCP客户端
 */
class MCPClient(
    private val config: MCPServerConfig
) {
    private val logger = Logger.getLogger<MCPClient>()
    private var connection: MCPConnection? = null

    suspend fun connect(): MCPConnection? {
        val transport = when (config.transportType) {
            is TransportType.StdIO -> StdIOTransport(config)
            is TransportType.HTTP -> HTTPTransport(config)
            is TransportType.WebSocket -> WebSocketTransport(config)
        }

        connection = MCPConnection(config, transport)
        val success = connection!!.connect()
        if (!success) {
            logger.error("Transport connection failed for ${config.name}")
            return null
        }

        // Perform initialize handshake
        val initialized = connection!!.initialize()
        if (!initialized) {
            logger.error("Initialize handshake failed for ${config.name}")
            connection!!.disconnect()
            connection = null
            return null
        }

        return connection
    }

    suspend fun disconnect() {
        connection?.disconnect()
        connection = null
    }

    suspend fun listTools(): List<McpTool> = connection?.listTools() ?: emptyList()

    suspend fun callTool(toolName: String, args: Map<String, Any>): MCPResource? {
        return connection?.callTool(toolName, args)
    }

    fun isConnected(): Boolean = connection?.isActive() == true
}

/**
 * WebSocket传输实现
 */
class WebSocketTransport(
    private val config: MCPServerConfig
) : MCPTransport {
    private val logger = Logger.getLogger<WebSocketTransport>()
    private var connected = false

    override suspend fun connect(): Boolean {
        connected = true
        return true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun send(message: String): String? {
        // WebSocket实现
        return null
    }

    override fun isConnected(): Boolean = connected
}
