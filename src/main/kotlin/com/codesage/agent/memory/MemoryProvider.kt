package com.codesage.agent.memory

import com.codesage.model.dto.Message
import com.codesage.model.dto.Tool

/**
 * 记忆提供者抽象接口
 *
 * 参考 Hermes 的 MemoryProvider 设计，支持多种记忆后端：
 * - 内置 SQLite（默认）
 * - 外部插件（Honcho、Hindsight、Mem0 等）
 *
 * 每个 provider 独立管理自己的存储，通过 MemoryManager 统一编排。
 */
interface MemoryProvider {

    val name: String

    /**
     * 检查当前 provider 是否可用
     */
    fun isAvailable(): Boolean

    /**
     * 初始化 provider
     */
    fun initialize(sessionId: String, homeDir: String, platform: String = "intellij")

    /**
     * 返回要注入系统提示的静态文本
     */
    fun systemPromptBlock(): String = ""

    /**
     * 每轮预取相关记忆（快路径，返回缓存结果）
     * @param query 当前用户消息作为查询
     * @param sessionId 当前会话 ID
     * @return 格式化的记忆文本块，为空字符串表示无相关记忆
     */
    fun prefetch(query: String, sessionId: String): String = ""

    /**
     * 后台排队预取下一轮记忆
     */
    fun queuePrefetch(query: String, sessionId: String) {}

    /**
     * 每轮结束后异步写入记忆
     * @param userContent 用户消息内容
     * @param assistantContent AI 回复内容
     * @param sessionId 当前会话 ID
     */
    fun syncTurn(userContent: String, assistantContent: String, sessionId: String)

    /**
     * 暴露给模型的记忆工具 schema
     */
    fun getToolSchemas(): List<Tool> = emptyList()

    /**
     * 处理模型调用的记忆工具
     */
    fun handleToolCall(toolName: String, args: Map<String, Any>): String = ""

    /**
     * 会话切换时调用
     */
    fun onSessionSwitch(newSessionId: String, parentSessionId: String? = null, reset: Boolean = false) {}

    /**
     * 会话结束时调用（异步提取全会话记忆）
     */
    fun onSessionEnd(messages: List<Message>) {}

    /**
     * 关闭 provider，释放资源
     */
    fun shutdown()
}
