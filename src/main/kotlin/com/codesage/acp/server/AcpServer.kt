package com.codesage.acp.server

import com.codesage.acp.model.*
import com.codesage.acp.transport.AcpSessionTransport
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.agent.tools.ToolResult
import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolCall
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ACP 服务端实现
 *
 * 将 CodeSage 的 [ToolRegistry] 通过 ACP 协议暴露给外部客户端（如 IDE、Agent Harness）。
 * 当前支持的方法：
 * - `initialize`：协议握手与能力协商
 * - `tools/list`：列出所有已注册工具
 * - `tools/call`：执行指定工具
 * - `shutdown`：优雅关闭当前会话
 */
class AcpServer(
    private val toolRegistry: ToolRegistry,
    private val toolExecutorFactory: () -> ToolExecutor,
    private val serverInfo: AcpServerInfo = AcpServerInfo("CodeSage", "1.0"),
    private val protocolVersion: String = "2024-11-05"
) {
    private val logger = Logger.getLogger<AcpServer>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    @Volatile
    private var initialized = false

    /**
     * 处理一条 ACP 会话。此方法会阻塞（挂起）直到传输层关闭或收到 shutdown。
     */
    suspend fun handleSession(transport: AcpSessionTransport) {
        logger.info("ACP session started")
        try {
            while (transport.isOpen) {
                val line = try {
                    transport.readLine()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("ACP session read error: ${e.message}")
                    break
                }
                if (line == null) break
                if (line.isBlank()) continue

                val responseLine = handleMessage(line)
                if (responseLine != null) {
                    try {
                        transport.writeLine(responseLine)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("ACP session write error: ${e.message}")
                        break
                    }
                }
            }
        } finally {
            try {
                transport.close()
            } catch (e: Exception) {
                logger.debug("Error closing ACP session: ${e.message}")
            }
            initialized = false
            logger.info("ACP session finished")
        }
    }

    internal suspend fun handleMessage(line: String): String? {
        val request = try {
            json.decodeFromString(AcpJsonRpcRequest.serializer(), line)
        } catch (e: Exception) {
            return errorResponse(null, -32700, "Parse error: ${e.message}")
        }

        if (request.jsonrpc != "2.0") {
            return errorResponse(request.id, -32600, "Invalid Request: jsonrpc must be 2.0")
        }

        return when (request.method) {
            "initialize" -> handleInitialize(request)
            "tools/list" -> handleToolsList(request)
            "tools/call" -> handleToolsCall(request)
            "shutdown" -> handleShutdown(request)
            else -> errorResponse(request.id, -32601, "Method not found: ${request.method}")
        }
    }

    private fun handleInitialize(request: AcpJsonRpcRequest): String {
        val params = try {
            json.decodeFromJsonElement(AcpInitializeParams.serializer(), request.params)
        } catch (e: Exception) {
            return errorResponse(request.id, -32602, "Invalid params: ${e.message}")
        }

        logger.info("ACP initialize from client protocolVersion=${params.protocolVersion}")
        initialized = true

        val result = AcpInitializeResult(
            protocolVersion = protocolVersion,
            serverInfo = serverInfo,
            capabilities = AcpServerCapabilities(tools = JsonObject(emptyMap()))
        )
        return successResponse(request.id, json.encodeToJsonElement(result))
    }

    private fun handleToolsList(request: AcpJsonRpcRequest): String {
        if (!initialized) {
            return errorResponse(request.id, -32603, "Server not initialized")
        }

        val tools = toolRegistry.getAllTools().map { it.toAcpTool() }
        val result = AcpToolListResult(tools = tools)
        return successResponse(request.id, json.encodeToJsonElement(result))
    }

    private suspend fun handleToolsCall(request: AcpJsonRpcRequest): String {
        if (!initialized) {
            return errorResponse(request.id, -32603, "Server not initialized")
        }

        val params = try {
            json.decodeFromJsonElement(AcpCallToolParams.serializer(), request.params)
        } catch (e: Exception) {
            return errorResponse(request.id, -32602, "Invalid params: ${e.message}")
        }

        return runToolCall(request.id, params)
    }

    private fun handleShutdown(request: AcpJsonRpcRequest): String {
        if (!initialized) {
            return errorResponse(request.id, -32603, "Server not initialized")
        }
        initialized = false
        return successResponse(request.id, JsonObject(emptyMap()))
    }

    private suspend fun runToolCall(id: JsonElement?, params: AcpCallToolParams): String {
        val toolName = params.name
        val argsString = json.encodeToString(params.arguments)
        val toolCall = ToolCall(
            id = id?.toString() ?: "0",
            name = toolName,
            arguments = argsString
        )

        val resultText = try {
            toolExecutorFactory().execute(toolCall)
        } catch (e: Exception) {
            logger.warn("ACP tool call failed: $toolName, error=${e.message}")
            val errorResult = AcpCallToolResult(
                content = listOf(AcpContentBlock(text = e.message ?: "Unknown error")),
                isError = true
            )
            return successResponse(id, json.encodeToJsonElement(errorResult))
        }

        // ToolExecutor 内部错误会返回 success=false 的 JSON，统一识别为 ACP error
        val parsed = try {
            json.parseToJsonElement(resultText).jsonObject
        } catch (e: Exception) {
            null
        }
        val isToolError = parsed?.get("success")?.jsonPrimitive?.content == "false"
        val acpResult = AcpCallToolResult(
            content = listOf(AcpContentBlock(text = resultText)),
            isError = isToolError
        )
        return successResponse(id, json.encodeToJsonElement(acpResult))
    }

    private fun Tool.toAcpTool(): AcpTool = AcpTool(
        name = name,
        description = description,
        inputSchema = parameters
    )

    private fun successResponse(id: JsonElement?, result: JsonElement): String {
        val response = AcpJsonRpcResponse(
            id = id,
            result = result
        )
        return json.encodeToString(AcpJsonRpcResponse.serializer(), response)
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): String {
        val response = AcpJsonRpcResponse(
            id = id,
            error = AcpJsonRpcError(code = code, message = message)
        )
        return json.encodeToString(AcpJsonRpcResponse.serializer(), response)
    }
}
