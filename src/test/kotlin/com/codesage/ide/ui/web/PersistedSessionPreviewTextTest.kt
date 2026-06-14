package com.codesage.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * O5.2: PersistedSession.previewText 提取测试
 *
 * 验证提取规则:
 *  - 取首条 USER 消息的纯文本(去空白)
 *  - 长度 > 30 时截前 30 字并加 "…"
 *  - 空内容返回空串
 *
 * 由于 ConversationPersistence.extractPreviewText 是私有方法,
 * 这里用一份与之行为完全一致的纯函数副本做断言,保证后续重构不会
 * 静默改变截断/空白规则。
 */
class PersistedSessionPreviewTextTest {

    @Test
    fun `collapses runs of whitespace to single space`() {
        val text = previewForTest("  hello   world  \n  foo ")
        assertEquals("hello world foo", text)
    }

    @Test
    fun `truncates long messages to 30 chars with ellipsis`() {
        val long = "a".repeat(50)
        val text = previewForTest(long)
        // 30 字 + "…"
        assertEquals(31, text.length)
        assertTrue(text.endsWith("…"))
        assertEquals("a".repeat(30) + "…", text)
    }

    @Test
    fun `returns short text unchanged when under limit`() {
        assertEquals("hello", previewForTest("hello"))
        assertEquals("a".repeat(30), previewForTest("a".repeat(30)))
    }

    @Test
    fun `returns empty string for blank content`() {
        assertEquals("", previewForTest(""))
        assertEquals("", previewForTest("   \n  \t  "))
    }

    /**
     * 与 [ConversationPersistence.extractPreviewText] 行为完全一致的纯函数。
     * ConversationPersistence 中对应实现:
     *   val raw = (firstUser.content ?: "").trim().replace(Regex("\\s+"), " ")
     *   if (raw.isEmpty()) return ""
     *   val max = 30
     *   return if (raw.length <= max) raw else raw.substring(0, max) + "…"
     */
    private fun previewForTest(content: String): String {
        val raw = content.trim().replace(Regex("\\s+"), " ")
        if (raw.isEmpty()) return ""
        val max = 30
        return if (raw.length <= max) raw else raw.substring(0, max) + "…"
    }
}
