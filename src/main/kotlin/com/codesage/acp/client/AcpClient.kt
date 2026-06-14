package com.codesage.acp.client

import com.codesage.acp.model.*
import com.codesage.acp.transport.AcpSessionTransport
import com.codesage.shared.utils.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * ACP 客户端实现
 *
 * 通过 [AcpSessionTransport] 连接外部 ACP agent，完成握手后查询并调用远端工具。
 * 典型使用方式：
 * ```
 * val transport = StdioAcpSessionTransport(process.inputStream, process.outputStream)
 * val client = AcpClient(transport)
 * client.initialize()
 * val tools = client.listTools()
 * val result = client.callTool("read_file", buildJsonObject { put("path", "README.md") })
 * client.shutdown()
 * ```
 */
class AcpClient(
    private val transport: AcpSessionTransport,
    private val protocolVersion: String = "2024-11-05"
) {
    private val logger = Logger.getLogger<AcpClient>()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val idCounter = AtomicInteger(0)

    @Volatile
    private var initialized = false

    /**
     * 与远端 ACP 服务端进行 initialize 握手
     */
    suspend fun initialize(): Boolean {
        val params = AcpInitializeParams(
            protocolVersion = protocolVersion,
            capabilities = AcpClientCapabilities()
        )
        val result = request("initialize", json.encodeToJsonElement(params))
            ?: throw AcpClientException("Initialize returned null result")
        val initResult = json.decodeFromJsonElement(AcpInitializeResult.serializer(), result)
        initialized = true
        logger.info("ACP client initialized with server: ${initResult.serverInfo.name} ${initResult.serverInfo.version}")
        return true
    }

    /**
     * 查询远端工具列表
     */
    suspend fun listTools(): List<AcpTool> {
        ensureInitialized()
        val result = request("tools/list", JsonObject(emptyMap()))
            ?: throw AcpClientException("tools/list returned null result")
        return json.decodeFromJsonElement(AcpToolListResult.serializer(), result).tools
    }

    /**
     * 调用远端工具
     *
     * @param name 工具名
     * @param arguments 工具参数 JSON 对象
     */
    suspend fun callTool(name: String, arguments: JsonObject): AcpCallToolResult {
        ensureInitialized()
        val params = AcpCallToolParams(name = name, arguments = arguments)
        val result = request("tools/call", json.encodeToJsonElement(params))
            ?: throw AcpClientException("tools/call returned null result")
        return json.decodeFromJsonElement(AcpCallToolResult.serializer(), result)
    }

    /**
     * 发送 shutdown 请求并关闭传输层
     */
    suspend fun shutdown() {
        if (initialized) {
            try {
                request("shutdown", JsonObject(emptyMap()))
            } catch (e: Exception) {
                logger.warn("ACP shutdown request failed: ${e.message}")
            }
            initialized = false
        }
        transport.close()
    }

    private fun ensureInitialized() {
        if (!initialized) {
            throw AcpClientException("ACP client not initialized")
        }
    }

    private suspend fun request(method: String, params: JsonElement): JsonElement? {
        val id = JsonPrimitive(idCounter.incrementAndGet())
        val request = AcpJsonRpcRequest(
            id = id,
            method = method,
            params = params.jsonObject
        )
        val line = json.encodeToString(AcpJsonRpcRequest.serializer(), request)
        logger.debug("ACP client request: $line")

        transport.writeLine(line)
        val responseLine = transport.readLine()
            ?: throw AcpClientException("No response for method $method")
        logger.debug("ACP client response: $responseLine")

        val response = try {
            json.decodeFromString(AcpJsonRpcResponse.serializer(), responseLine)
        } catch (e: Exception) {
            throw AcpClientException("Failed to parse response: ${e.message}")
        }

        if (response.id != id && response.id != null) {
            logger.warn("ACP response id mismatch: expected $id, got ${response.id}")
        }

        response.error?.let {
            throw AcpClientException("ACP error ${it.code}: ${it.message}")
        }

        return response.result
    }
}

class AcpClientException(message: String) : RuntimeException(message)
