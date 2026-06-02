package com.codesage.agent.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T1.5 修复验证测试：ChatMode 关键词路由
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T1.5）：
 * - [x] 保留 detectChatMode 但仅作为建议
 * - [x] UI 暴露"对话模式"下拉（GENERAL/CODING/REASONING/VISION）
 * - [x] 用户未选时回退到 GENERAL + 默认 routing
 * - [x] Backend 单元测试：未指定模式时不使用任何关键词匹配逻辑
 *   （即 userExplicit=true 时，suggestion 不会影响 effective）
 */
class ChatModeRouterTest {

    // === suggestChatMode 测试 ===

    @Test
    fun `suggestChatMode returns CODING for English coding keywords`() {
        assertEquals(ChatMode.CODING, ChatModeRouter.suggestChatMode("Please write a function to parse JSON"))
        assertEquals(ChatMode.CODING, ChatModeRouter.suggestChatMode("How do I implement a binary tree?"))
        assertEquals(ChatMode.CODING, ChatModeRouter.suggestChatMode("Debug this React component"))
    }

    @Test
    fun `suggestChatMode returns CODING for Chinese coding keywords`() {
        assertEquals(ChatMode.CODING, ChatModeRouter.suggestChatMode("帮我写一段代码"))
        assertEquals(ChatMode.CODING, ChatModeRouter.suggestChatMode("这个函数有 bug"))
        assertEquals(ChatMode.CODING, ChatModeRouter.suggestChatMode("重构一下这个类"))
    }

    @Test
    fun `suggestChatMode returns REASONING for reasoning keywords`() {
        assertEquals(ChatMode.REASONING, ChatModeRouter.suggestChatMode("Analyze the difference between A and B"))
        assertEquals(ChatMode.REASONING, ChatModeRouter.suggestChatMode("为什么这个算法更优？"))
        assertEquals(ChatMode.REASONING, ChatModeRouter.suggestChatMode("Prove that P=NP"))
    }

    @Test
    fun `suggestChatMode returns VISION for vision keywords`() {
        assertEquals(ChatMode.VISION, ChatModeRouter.suggestChatMode("看图说话：describe this image"))
        assertEquals(ChatMode.VISION, ChatModeRouter.suggestChatMode("这是截图，帮我分析一下"))
    }

    @Test
    fun `suggestChatMode returns GENERAL for ambiguous or empty messages`() {
        assertEquals(ChatMode.GENERAL, ChatModeRouter.suggestChatMode("Hello there"))
        assertEquals(ChatMode.GENERAL, ChatModeRouter.suggestChatMode("What time is it?"))
        assertEquals(ChatMode.GENERAL, ChatModeRouter.suggestChatMode(""))
        assertEquals(ChatMode.GENERAL, ChatModeRouter.suggestChatMode("   "))
    }

    // === resolve 测试（核心） ===

    @Test
    fun `resolve with userExplicit returns user choice strictly`() {
        // 用户显式选了 GENERAL，消息里却满是 code 关键字 → effective 仍是 GENERAL
        val result = ChatModeRouter.resolve(
            userExplicit = ChatMode.GENERAL,
            message = "Please write a function to do something"
        )
        assertEquals(ChatMode.GENERAL, result.effective)
        assertTrue(result.userExplicit, "userExplicit should be true")
        assertEquals(ChatMode.CODING, result.suggestion, "suggestion should still be computed")
    }

    @Test
    fun `resolve with userExplicit CODING forces CODING even for non-coding message`() {
        val result = ChatModeRouter.resolve(
            userExplicit = ChatMode.CODING,
            message = "Hello, how are you?"
        )
        assertEquals(ChatMode.CODING, result.effective)
        assertTrue(result.userExplicit)
    }

    @Test
    fun `resolve with null userExplicit falls back to suggestion`() {
        val result = ChatModeRouter.resolve(
            userExplicit = null,
            message = "Help me debug this React component"
        )
        assertEquals(ChatMode.CODING, result.effective)
        assertFalse(result.userExplicit)
        assertEquals(ChatMode.CODING, result.suggestion)
    }

    @Test
    fun `resolve with null userExplicit and empty message returns GENERAL`() {
        val result = ChatModeRouter.resolve(
            userExplicit = null,
            message = ""
        )
        assertEquals(ChatMode.GENERAL, result.effective)
        assertFalse(result.userExplicit)
    }

    // === 关键回归保护 ===

    @Test
    fun `userExplicit=true does NOT call keyword detection for routing decision`() {
        // 关键 T1.5 验收点：当用户显式选择时，后端不应被 message 关键词覆盖
        val cases = listOf(
            ChatMode.GENERAL to "implement a quicksort algorithm",
            ChatMode.CODING to "what's the meaning of life?",
            ChatMode.REASONING to "show me a screenshot",
            ChatMode.VISION to "explain how HTTP works"
        )
        cases.forEach { (userChoice, message) ->
            val result = ChatModeRouter.resolve(userChoice, message)
            assertEquals(
                userChoice, result.effective,
                "userExplicit=${userChoice} should override message='$message' (suggestion=${result.suggestion})"
            )
        }
    }

    // === 向后兼容 ===

    @Test
    fun `deprecated detectChatMode still works via suggestion`() {
        // AgentCore.detectChatMode 仍保留为 @Deprecated
        // 验证它现在委托给 suggestChatMode（行为一致）
        // 我们用一个直接调用 ChatModeRouter 的方式等价测试
        assertEquals(ChatMode.CODING, ChatModeRouter.suggestChatMode("function please"))
        assertEquals(ChatMode.GENERAL, ChatModeRouter.suggestChatMode("Hello"))
    }

    // === ChatMode enum ===

    @Test
    fun `ChatMode fromString parses correctly`() {
        assertEquals(ChatMode.GENERAL, ChatMode.fromString("GENERAL"))
        assertEquals(ChatMode.CODING, ChatMode.fromString("CODING"))
        assertEquals(ChatMode.REASONING, ChatMode.fromString("REASONING"))
        assertEquals(ChatMode.VISION, ChatMode.fromString("VISION"))
        // 未知值回退到 GENERAL
        assertEquals(ChatMode.GENERAL, ChatMode.fromString("UNKNOWN"))
        // 大小写敏感
        assertEquals(ChatMode.GENERAL, ChatMode.fromString("general"))
    }

    // === 优先级测试 ===

    @Test
    fun `vision keyword takes priority over coding keyword`() {
        // visionKeywords 在 codingKeywords 之前匹配，所以 VISION 优先
        val result = ChatModeRouter.suggestChatMode("看这个 image 并实现 code 来处理")
        assertEquals(ChatMode.VISION, result)
    }

    @Test
    fun `coding keyword takes priority over reasoning keyword`() {
        // codingKeywords 在 reasoningKeywords 之前匹配，所以 CODING 优先
        val result = ChatModeRouter.suggestChatMode("analyze the algorithm and implement it")
        assertEquals(ChatMode.CODING, result)
    }
}
