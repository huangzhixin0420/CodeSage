package com.codesage.agent.core

import com.codesage.shared.utils.Logger

/**
 * T1.5 修复：对话模式路由器
 *
 * **问题**：原 AgentCore 内 `detectChatMode` 在 backend 静默根据消息内容猜测 chat mode，
 * 直接套用 CODING/REASONING/VISION。用户没有显式选择 mode 的权利，体验差且行为不透明。
 *
 * **修复方案**：
 * 1. 后端只提供 `suggestChatMode(message)` —— 一个**建议**，不是强制选择
 * 2. UI 暴露"对话模式"下拉（GENERAL / CODING / REASONING / VISION），用户显式选择
 * 3. `chat / chatStream / chatWithTools` 接受 `ChatMode? = null`：
 *    - `null` = 用户未显式选择 → 后端用 `suggestChatMode` 推断，并把建议作为
 *      `ModeSuggestion` 事件 emit 出来供 UI 提示
 *    - 非空 = 用户显式选择 → 严格使用用户值，**不**调用 `suggestChatMode`
 *
 * 这样保留了 keyword 路由的可用性（用户没选时还能给个合理默认），同时让用户有最终控制权。
 */
object ChatModeRouter {

    private val logger = Logger.getLogger<ChatModeRouter>()

    private val CODING_KEYWORDS = listOf(
        "code", "function", "class", "debug", "refactor", "bug", "compile",
        "写代码", "代码", "函数", "类", "调试", "重构", "编译", "报错",
        "implement", "algorithm", "sort", "递归", "leetcode"
    )
    private val REASONING_KEYWORDS = listOf(
        "analyze", "reason", "prove", "calculate", "推导", "证明", "分析",
        "推理", "逻辑", "math", "mathematics", "complex", "optimize",
        "compare", "difference", "优缺点", "为什么", "what if"
    )
    private val VISION_KEYWORDS = listOf(
        "image", "picture", "screenshot", "截图", "图片", "照片",
        "photo", "diagram", "chart", "可视化", "看图", "describe this"
    )

    /**
     * 根据消息内容推断 chat mode（仅作为建议，不强制）
     */
    fun suggestChatMode(message: String): ChatMode {
        if (message.isBlank()) return ChatMode.GENERAL
        val lower = message.lowercase()
        return when {
            VISION_KEYWORDS.any { lower.contains(it) } -> ChatMode.VISION
            CODING_KEYWORDS.any { lower.contains(it) } -> ChatMode.CODING
            REASONING_KEYWORDS.any { lower.contains(it) } -> ChatMode.REASONING
            else -> ChatMode.GENERAL
        }
    }

    /**
     * T1.5 核心：决定最终生效的 ChatMode
     *
     * @param userExplicit 用户通过 UI 显式选择的模式（可能为 null = 用户未选）
     * @param message 用户消息（用于推断）
     * @return 路由结果，含 effective mode + 是否来自用户显式选择 + 自动建议值
     */
    fun resolve(
        userExplicit: ChatMode?,
        message: String
    ): ChatModeRouting {
        val suggestion = suggestChatMode(message)
        return if (userExplicit != null) {
            ChatModeRouting(
                effective = userExplicit,
                userExplicit = true,
                suggestion = suggestion
            )
        } else {
            ChatModeRouting(
                effective = suggestion,
                userExplicit = false,
                suggestion = suggestion
            )
        }
    }
}

/**
 * 路由结果
 *
 * @property effective 实际生效的 mode
 * @property userExplicit 是否来自用户显式选择（true = 严格尊重用户；false = 后端推断）
 * @property suggestion 后端根据消息内容推断的建议值（用于 UI 提示或后续遥测）
 */
data class ChatModeRouting(
    val effective: ChatMode,
    val userExplicit: Boolean,
    val suggestion: ChatMode
)
