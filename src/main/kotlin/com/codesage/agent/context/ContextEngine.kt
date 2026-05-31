package com.codesage.agent.context

import com.codesage.model.dto.Message
import com.codesage.model.dto.Tool
import com.codesage.model.dto.Usage

/**
 * Context 引擎抽象基类
 *
 * 参考 Hermes 的 ContextEngine 设计，支持插件化替换（Compressor、LCM 等）。
 * 子类负责实现具体的压缩策略。
 */
abstract class ContextEngine {

    abstract val name: String

    // Token 状态（由响应更新）
    open var lastPromptTokens: Int = 0
    open var lastCompletionTokens: Int = 0
    open var contextLength: Int = DEFAULT_CONTEXT_LENGTH
    open var compressionCount: Int = 0

    // 压缩参数
    open val thresholdPercent: Double = DEFAULT_THRESHOLD_PERCENT
    open val protectFirstN: Int = DEFAULT_PROTECT_FIRST_N
    open val protectLastN: Int = DEFAULT_PROTECT_LAST_N

    /**
     * 从模型响应更新 token 统计
     */
    open fun updateFromResponse(usage: Usage?) {
        usage?.let {
            lastPromptTokens = it.promptTokens
            lastCompletionTokens = it.completionTokens
        }
    }

    /**
     * 检查是否需要压缩
     * @param promptTokens 当前提示的 token 数，为 null 时自动估算
     */
    fun shouldCompress(promptTokens: Int? = null): Boolean {
        val tokens = promptTokens ?: estimateTokens(messagesSnapshot)
        val thresholdTokens = (contextLength * thresholdPercent).toInt()
        return tokens >= thresholdTokens
    }

    /**
     * 压缩消息列表
     * @param messages 当前消息列表
     * @param currentTokens 当前 token 数（可选，避免重复计算）
     * @param focusTopic 聚焦主题（可选，用于指导摘要生成）
     * @return 压缩后的消息列表
     */
    abstract fun compress(
        messages: List<Message>,
        currentTokens: Int? = null,
        focusTopic: String? = null
    ): List<Message>

    /**
     * 可选工具（如 LCM 的 lcm_grep）
     */
    open fun getToolSchemas(): List<Tool> = emptyList()

    /**
     * 获取阈值 token 数
     */
    fun thresholdTokens(): Int = (contextLength * thresholdPercent).toInt()

    // 内部消息快照（用于估算）
    protected var messagesSnapshot: List<Message> = emptyList()

    companion object {
        const val DEFAULT_CONTEXT_LENGTH = 128000
        const val DEFAULT_THRESHOLD_PERCENT = 0.75
        const val DEFAULT_PROTECT_FIRST_N = 3
        const val DEFAULT_PROTECT_LAST_N = 6
        const val SUMMARY_RATIO = 0.20
        const val MAX_SUMMARY_TOKENS = 12000
    }
}

/**
 * 简单的 token 估算（基于字符数）
 */
fun estimateTokens(messages: List<Message>): Int {
    return messages.sumOf { msg ->
        val contentTokens = if (msg.content.isBlank()) 0 else {
            val chineseChars = msg.content.count { it.code in 0x4E00..0x9FFF }
            val otherChars = msg.content.length - chineseChars
            chineseChars + (otherChars / 4)
        }
        // 角色开销 + 内容
        4 + contentTokens
    }
}
