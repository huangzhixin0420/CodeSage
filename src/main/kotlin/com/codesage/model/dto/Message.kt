package com.codesage.model.dto

import kotlinx.serialization.Serializable

/**
 * 消息角色枚举
 */
@Serializable
enum class Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

/**
 * 工具调用定义
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String  // JSON string
)

/**
 * 工具定义
 */
@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

/**
 * 工具参数定义
 */
@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty> = emptyMap(),
    val required: List<String> = emptyList()
)

/**
 * 工具属性定义
 */
@Serializable
data class ToolProperty(
    val type: String,
    val description: String? = null,
    val enum: List<String>? = null
)

/**
 * 统一消息结构
 */
@Serializable
data class Message(
    val role: Role,
    val content: String,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
) {
    companion object {
        fun userMessage(content: String) = Message(role = Role.USER, content = content)
        fun systemMessage(content: String) = Message(role = Role.SYSTEM, content = content)
        fun assistantMessage(content: String, toolCalls: List<ToolCall>? = null) = 
            Message(role = Role.ASSISTANT, content = content, toolCalls = toolCalls)
        fun toolMessage(content: String, toolCallId: String) = 
            Message(role = Role.TOOL, content = content, toolCallId = toolCallId)
    }
}