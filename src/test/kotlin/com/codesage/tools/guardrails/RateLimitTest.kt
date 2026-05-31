package com.codesage.tools.guardrails

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RateLimitTest {

    @Test
    fun `should block after consecutive calls exceeded`() {
        val rateLimiter = ToolRateLimiter(
            maxConsecutiveCalls = 3,
            policy = ToolRateLimiter.RateLimitPolicy.BLOCK
        )

        // 前3次应该允许
        assertTrue(rateLimiter.check("search_code").allowed)
        assertTrue(rateLimiter.check("search_code").allowed)
        assertTrue(rateLimiter.check("search_code").allowed)

        // 第4次应该被阻止
        val result = rateLimiter.check("search_code")
        assertFalse(result.allowed)
        assertNotNull(result.warning)
        assertTrue(result.warning!!.contains("Blocked"))
    }

    @Test
    fun `should reset counter on success`() {
        val rateLimiter = ToolRateLimiter(
            maxConsecutiveCalls = 3,
            policy = ToolRateLimiter.RateLimitPolicy.BLOCK
        )

        // 调用2次
        assertTrue(rateLimiter.check("read_file").allowed)
        assertTrue(rateLimiter.check("read_file").allowed)

        // 成功执行后重置
        rateLimiter.recordSuccess("read_file")

        // 再次调用应该允许（计数已重置）
        assertTrue(rateLimiter.check("read_file").allowed)
        assertTrue(rateLimiter.check("read_file").allowed)
        assertTrue(rateLimiter.check("read_file").allowed)

        // 第4次才触发限制
        val result = rateLimiter.check("read_file")
        assertFalse(result.allowed)
    }

    @Test
    fun `should allow after cooldown`() {
        val rateLimiter = ToolRateLimiter(
            maxConsecutiveCalls = 2,
            windowSizeMs = 100, // 100ms 窗口，方便测试
            policy = ToolRateLimiter.RateLimitPolicy.BLOCK
        )

        // 超过限制
        rateLimiter.check("grep_code")
        rateLimiter.check("grep_code")
        rateLimiter.check("grep_code")

        val blocked = rateLimiter.check("grep_code")
        assertFalse(blocked.allowed)

        // 等待窗口过期
        Thread.sleep(150)

        // 旧调用已滑出窗口，应该允许
        val afterCooldown = rateLimiter.check("grep_code")
        assertTrue(afterCooldown.allowed)
    }

    @Test
    fun `should warn but allow with WARN policy`() {
        val rateLimiter = ToolRateLimiter(
            maxConsecutiveCalls = 2,
            policy = ToolRateLimiter.RateLimitPolicy.WARN
        )

        rateLimiter.check("list_directory")
        rateLimiter.check("list_directory")

        // 第3次：允许但警告
        val result = rateLimiter.check("list_directory")
        assertTrue(result.allowed)
        assertNotNull(result.warning)
        assertTrue(result.warning!!.contains("Warning"))
    }

    @Test
    fun `should skip with SKIP policy`() {
        val rateLimiter = ToolRateLimiter(
            maxConsecutiveCalls = 2,
            policy = ToolRateLimiter.RateLimitPolicy.SKIP
        )

        rateLimiter.check("delete_file")
        rateLimiter.check("delete_file")

        val result = rateLimiter.check("delete_file")
        assertFalse(result.allowed)
        assertNotNull(result.warning)
        assertTrue(result.warning!!.contains("Skipped"))
    }

    @Test
    fun `different tools should have independent counters`() {
        val rateLimiter = ToolRateLimiter(
            maxConsecutiveCalls = 2,
            policy = ToolRateLimiter.RateLimitPolicy.BLOCK
        )

        // 调用 toolA 3次
        rateLimiter.check("toolA")
        rateLimiter.check("toolA")
        val blockedA = rateLimiter.check("toolA")
        assertFalse(blockedA.allowed)

        // toolB 应该不受影响
        assertTrue(rateLimiter.check("toolB").allowed)
        assertTrue(rateLimiter.check("toolB").allowed)
    }

    @Test
    fun `resetAll should clear all counters`() {
        val rateLimiter = ToolRateLimiter(
            maxConsecutiveCalls = 2,
            policy = ToolRateLimiter.RateLimitPolicy.BLOCK
        )

        rateLimiter.check("toolA")
        rateLimiter.check("toolA")
        rateLimiter.check("toolB")
        rateLimiter.check("toolB")

        rateLimiter.resetAll()

        assertTrue(rateLimiter.check("toolA").allowed)
        assertTrue(rateLimiter.check("toolB").allowed)
    }
}
