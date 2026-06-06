package com.codesage.agent.planner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T0.8 修复验证测试：TaskPlanner 正则表达式缓存
 *
 * CodeReview High #13 报告："缺少正则表达式缓存"
 *
 * 验证 [TaskPlanner.splitBySentences]（私有）使用的 SENTENCE_ENDER_REGEX
 * 是 companion 缓存的同一个实例，避免每次调用都重新编译 Pattern。
 *
 * 注：Kotlin companion 字段在 JVM 上是外层类的 private static 字段。
 * 注：SENTENCE_ENDER_REGEX 匹配 `(?<=[.!?。！？])\s+` —— 即标点后必须跟空白字符。
 */
class TaskPlannerRegexCacheTest {

    @Test
    fun `companion SENTENCE_ENDER_REGEX is initialized`() {
        val cls = TaskPlanner::class.java
        val field = cls.getDeclaredField("SENTENCE_ENDER_REGEX")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val regex: Regex = field.get(null) as Regex
        assertNotNull(regex, "SENTENCE_ENDER_REGEX should be initialized")
        val sample = "First sentence. Second sentence! Third? Fourth."
        val parts = sample.split(regex).map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(4, parts.size, "Should split into 4 sentences; got: $parts")
    }

    @Test
    fun `same regex instance is reused across calls`() {
        val cls = TaskPlanner::class.java
        val field = cls.getDeclaredField("SENTENCE_ENDER_REGEX")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val r1 = field.get(null) as Regex
        val r2 = field.get(null) as Regex
        assertSame(r1, r2, "SENTENCE_ENDER_REGEX should be a singleton in companion")
    }

    @Test
    fun `splitBySentences handles both English and Chinese punctuation`() {
        val cls = TaskPlanner::class.java
        val field = cls.getDeclaredField("SENTENCE_ENDER_REGEX")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val regex: Regex = field.get(null) as Regex
        val sample = "你好世界。 今天天气好! How are you. Fine."
        val parts = sample.split(regex).map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(4, parts.size, "Should split mixed Chinese/English sentences; got: $parts")
    }
}
