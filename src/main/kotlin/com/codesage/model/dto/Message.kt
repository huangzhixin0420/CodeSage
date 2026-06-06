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
    val arguments: String,  // JSON string
    val summary: String? = null,  // 简短说明(去掉 "Running X..." 重复)
    val icon: String? = null,     // 工具专属 FA 图标 class,比如 "fas fa-eye"
)

/**
 * 工具定义
 *
 * T6.4 扩展：加 category 和 tags 字段，便于按类别检索。
 *
 * 注意：category 默认为 ToolCategory.GENERAL，不强制业务方填写。
 * 已有 50+ 工具的 metadata 升级时只需要简单声明 category。
 */
@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: ToolParameters,
    val category: ToolCategory = ToolCategory.GENERAL,
    val tags: Set<String> = emptySet()
)

/**
 * 工具类别
 *
 * 用于 [ToolRegistry.findByCategory] 和 UI 分类展示。
 */
@Serializable
enum class ToolCategory {
    FILE_OPERATION,   // 文件读写/搜索/编辑
    CODE_ANALYSIS,   // 代码洞察/分析
    GIT,              // Git 操作
    BUILD,            // Maven/Gradle/npm
    TEST,             // 测试执行/生成
    SEARCH,           // 网络搜索/API 调用
    SYSTEM,           // 系统命令/工具
    GENERAL           // 其它/默认
}

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
 *
 * [userLanguage] 是可选的 BCP-47 语言标签(例 "zh-CN" / "en-US"),由前端在
 * 发送 user message 时附带。Agent 层会把它拼进系统提示,要求模型用相同
 * 语言回答用户。如果为 null,则按 PluginConfig/SettingsRepository 里
 * `ui.language` 的当前值兜底。
 */
@Serializable
data class Message(
    val role: Role,
    val content: String,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val userLanguage: String? = null,
) {
    companion object {
        fun userMessage(content: String, userLanguage: String? = null) =
            Message(role = Role.USER, content = content, userLanguage = userLanguage)

        fun systemMessage(content: String) = Message(role = Role.SYSTEM, content = content)
        fun assistantMessage(content: String, toolCalls: List<ToolCall>? = null) =
            Message(role = Role.ASSISTANT, content = content, toolCalls = toolCalls)

        fun toolMessage(content: String, toolCallId: String) =
            Message(role = Role.TOOL, content = content, toolCallId = toolCallId)
    }
}
