package com.codesage.acp.model

import com.codesage.model.dto.ToolParameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * ACP（Agent Client Protocol）JSON-RPC 2.0 消息模型
 *
 * 参考 Kimi Code CLI / Zed 等 IDE 的 ACP 集成方式，采用行分隔 JSON-RPC
 * 进行握手、能力协商、工具列表查询与工具调用。
 */

/**
 * JSON-RPC 2.0 请求
 *
 * @property jsonrpc 固定为 "2.0"
 * @property id 请求标识；null 表示通知（无需响应）
 * @property method 方法名，如 "initialize" / "tools/list" / "tools/call"
 * @property params 方法参数对象
 */
@Serializable
data class AcpJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap())
)

/**
 * JSON-RPC 2.0 响应
 *
 * @property result 成功结果；与 error 互斥
 * @property error 错误信息；与 result 互斥
 */
@Serializable
data class AcpJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: AcpJsonRpcError? = null
)

/**
 * JSON-RPC 2.0 错误对象
 *
 * @property code 错误码；参考 MCP / ACP 约定：-32700 解析错误、-32600 无效请求、
 *               -32601 方法未找到、-32602 无效参数、-32603 内部错误
 * @property message 错误描述
 * @property data 附加数据
 */
@Serializable
data class AcpJsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/**
 * ACP 握手参数
 */
@Serializable
data class AcpInitializeParams(
    @SerialName("protocolVersion")
    val protocolVersion: String,
    val capabilities: AcpClientCapabilities = AcpClientCapabilities()
)

/**
 * 客户端能力声明
 */
@Serializable
data class AcpClientCapabilities(
    val tools: JsonObject? = null
)

/**
 * 服务端能力声明
 */
@Serializable
data class AcpServerCapabilities(
    val tools: JsonObject? = null
)

/**
 * ACP 握手结果
 */
@Serializable
data class AcpInitializeResult(
    @SerialName("protocolVersion")
    val protocolVersion: String,
    @SerialName("serverInfo")
    val serverInfo: AcpServerInfo,
    val capabilities: AcpServerCapabilities
)

/**
 * ACP 服务端元信息
 */
@Serializable
data class AcpServerInfo(
    val name: String,
    val version: String
)

/**
 * tools/list 返回结果
 */
@Serializable
data class AcpToolListResult(
    val tools: List<AcpTool> = emptyList()
)

/**
 * ACP 工具定义
 *
 * 复用 CodeSage 的 [ToolParameters] 作为输入 schema。
 */
@Serializable
data class AcpTool(
    val name: String,
    val description: String,
    @SerialName("inputSchema")
    val inputSchema: ToolParameters
)

/**
 * tools/call 参数
 */
@Serializable
data class AcpCallToolParams(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap())
)

/**
 * tools/call 结果
 *
 * 结果以 content block 列表返回，与 MCP 工具结果格式保持一致，便于 IDE 侧渲染。
 */
@Serializable
data class AcpCallToolResult(
    val content: List<AcpContentBlock> = emptyList(),
    @SerialName("isError")
    val isError: Boolean = false
)

/**
 * 内容块，目前仅支持 text 类型
 */
@Serializable
data class AcpContentBlock(
    val type: String = "text",
    val text: String = ""
)
