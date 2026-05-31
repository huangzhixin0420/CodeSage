package com.codesage.agent.context

import com.codesage.model.dto.Message
import com.codesage.model.dto.Role

/**
 * Token 估算器
 *
 * 参考 Hermes 的 `estimate_messages_tokens_rough()` 和 `estimate_request_tokens_rough()`：
 * - 文本按字符类型混合估算
 * - 图像按 1600 tokens/张估算
 * - 多模态 content length 计算
 */
object TokenEstimator {

    /**
     * 估算消息列表的总 token 数
     */
    fun estimateMessagesTokens(messages: List<Message>): Int {
        return messages.sumOf { estimateMessageTokens(it) }
    }

    /**
     * 估算单条消息的 token 数
     */
    fun estimateMessageTokens(message: Message): Int {
        var tokens = 0

        // 角色开销
        tokens += when (message.role) {
            Role.SYSTEM -> 4
            Role.USER -> 4
            Role.ASSISTANT -> 4
            Role.TOOL -> 4
        }

        // 内容 token
        tokens += estimateTextTokens(message.content)

        // 工具调用开销
        message.toolCalls?.forEach { toolCall ->
            tokens += 8 // tool_call 基础开销
            tokens += estimateTextTokens(toolCall.name)
            tokens += estimateTextTokens(toolCall.arguments)
        }

        // toolCallId 开销
        message.toolCallId?.let { tokens += estimateTextTokens(it) }

        return tokens
    }

    /**
     * 估算文本 token 数
     */
    fun estimateTextTokens(text: String): Int {
        if (text.isBlank()) return 0

        // 混合估算：中文 ≈ 1 token/字，英文 ≈ 1 token/4 chars，代码 ≈ 1 token/3.5 chars
        var chineseTokens = 0
        var englishTokens = 0
        var codeTokens = 0

        val codeIndicators = setOf('{', '}', '(', ')', '=', ';', '<', '>', '/', '.', ',')
        var codeCharCount = 0

        for (char in text) {
            when {
                char.code in 0x4E00..0x9FFF -> chineseTokens++
                char.code in 0x3000..0x303F || char.code in 0xFF00..0xFFEF -> chineseTokens++
                char in codeIndicators -> codeCharCount++
            }
        }

        val totalChars = text.length
        val nonChineseChars = totalChars - chineseTokens

        // 如果代码特征字符占比高，使用代码估算率
        val isCodeLike = codeCharCount > nonChineseChars * 0.05

        englishTokens = if (isCodeLike) {
            (nonChineseChars / 3.5).toInt()
        } else {
            (nonChineseChars / 4).toInt()
        }

        // 图像 base64 检测：按 1600 tokens/张估算
        val imageMatches = Regex("data:image/[^;]+;base64").findAll(text).count()
        val imageTokens = imageMatches * 1600

        return chineseTokens + englishTokens + imageTokens + 2 // +2 为格式开销
    }

    /**
     * 估算请求总 token 数（消息 + 工具定义）
     */
    fun estimateRequestTokens(
        messages: List<Message>,
        toolDefinitionsJson: String? = null
    ): Int {
        var tokens = estimateMessagesTokens(messages)
        toolDefinitionsJson?.let { tokens += estimateTextTokens(it) }
        return tokens
    }

    /**
     * 快速估算：用于高频检查场景
     */
    fun roughEstimate(text: String): Int {
        return if (text.isEmpty()) 0 else text.length / 3
    }
}
