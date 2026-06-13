package com.codesage.agent.tools

import com.codesage.model.dto.ToolCall
import com.codesage.tools.guardrails.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ToolExecutorGuardrailsTest {

    @Test
    fun `should truncate long output`() = runBlocking {
        val truncator = OutputTruncator(defaultMaxLength = 1000)
        val guardrails = ToolGuardrails(truncator = truncator)
        val executor = ToolExecutor(
            project = null,
            guardrails = guardrails
        )

        // read_file on a non-existent path returns an error, but let's test via guardrails directly
        val longContent = "A".repeat(5000)
        val result = guardrails.postProcess(
            "read_file", ToolResult.Success(
                kotlinx.serialization.json.JsonPrimitive(longContent)
            )
        )

        assertTrue(result is ToolResult.Success)
        val content = (result as ToolResult.Success).data.toString()
        assertTrue(content.contains("[Output truncated...]"))
        assertTrue(content.length < longContent.length)
    }

    @Test
    fun `should block tool under rate limit`() = runBlocking {
        val rateLimiter = ToolRateLimiter(
            maxConsecutiveCalls = 2,
            policy = ToolRateLimiter.RateLimitPolicy.BLOCK
        )
        val executor = ToolExecutor(
            project = null,
            rateLimiter = rateLimiter
        )

        val toolCall = ToolCall(id = "1", name = "read_file", arguments = "{\"path\":\"test.txt\"}")

        // 前2次应该通过（但实际会失败因为 project 为 null）
        // 这里主要验证 rate limiter 的拦截逻辑
        try {
            executor.execute(toolCall)
        } catch (e: Exception) {
            // expected: IDETools needs a project
        }
        try {
            executor.execute(toolCall)
        } catch (e: Exception) {
            // expected
        }

        // 第3次应该被 rate limiter 拦截
        val exception = assertThrows(ToolExecutionBlocked::class.java) {
            runBlocking {
                executor.execute(toolCall)
            }
        }

        assertEquals(ToolExecutionBlocked.BlockReason.RATE_LIMIT, exception.reason)
        assertTrue(
            exception.message!!.contains("Blocked", ignoreCase = true) ||
                    exception.message!!.contains("rate limit", ignoreCase = true)
        )
    }

    @Test
    fun `should log audit entry`() = runBlocking {
        val auditLog = ToolAuditLog(maxEntries = 100)
        val rateLimiter = ToolRateLimiter(
            maxConsecutiveCalls = 10,
            policy = ToolRateLimiter.RateLimitPolicy.WARN
        )
        val executor = ToolExecutor(
            project = null,
            rateLimiter = rateLimiter,
            auditLog = auditLog
        )

        val toolCall = ToolCall(id = "1", name = "unknown_tool", arguments = "{}")

        try {
            executor.execute(toolCall)
        } catch (e: Exception) {
            // expected: unknown tool returns error, not exception
        }

        val entries = auditLog.getAll()
        assertEquals(1, entries.size)
        assertEquals("unknown_tool", entries[0].toolName)
        assertEquals("error", entries[0].resultStatus)
    }

    @Test
    fun `dangerous command requires confirmation (headless auto-denies as CONFIRMATION_DENIED)`() = runBlocking {
        // 2026-06 P1:危险命令不再 silent deny → BLOCKED/POLICY_VIOLATION,
        // 改为 REQUIRES_CONFIRMATION。Headless 模式(confirmationCallback = null)
        // 会自动拒绝,但 BlockReason 应为 CONFIRMATION_DENIED,而不是 POLICY_VIOLATION,
        // 以便调用方知道"用户有机会点确认"。
        val guardrails = ToolGuardrails()  // no callback -> headless
        val executor = ToolExecutor(
            project = null,
            guardrails = guardrails
        )

        val toolCall = ToolCall(id = "1", name = "run_command", arguments = "{\"command\":\"rm -rf /\"}")

        val exception = assertThrows(ToolExecutionBlocked::class.java) {
            runBlocking {
                executor.execute(toolCall)
            }
        }

        assertEquals(ToolExecutionBlocked.BlockReason.CONFIRMATION_DENIED, exception.reason)
    }
}
