package com.codesage.tools.guardrails

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * C1 修复验证：ToolAuditLog 嵌套敏感字段脱敏。
 *
 * 旧实现只对顶层 key 做 substring 检查，嵌套 Map/JsonElement 里的敏感字段
 * 会被原样写入审计日志。新实现递归遍历。
 */
class ToolAuditLogNestedTest {

    @Test
    fun `redacts nested api_key in Map`() {
        val auditLog = ToolAuditLog(maxEntries = 100)
        val nested = mapOf(
            "api_key" to "super-secret",
            "user" to "alice"
        )
        auditLog.log(
            toolName = "test_tool",
            arguments = mapOf("headers" to nested),
            resultStatus = "success",
            durationMs = 10
        )
        val entry = auditLog.getAll().first()
        val headersStr = entry.arguments["headers"] ?: ""
        assertTrue(headersStr.contains("***REDACTED***"), "nested api_key should be redacted: $headersStr")
        assertTrue(!headersStr.contains("super-secret"), "secret value must NOT appear: $headersStr")
    }

    @Test
    fun `redacts nested token in List of Maps`() {
        val auditLog = ToolAuditLog(maxEntries = 100)
        val users = listOf(
            mapOf("name" to "alice", "password" to "pwd1"),
            mapOf("name" to "bob", "password" to "pwd2")
        )
        auditLog.log(
            toolName = "test_tool",
            arguments = mapOf("users" to users),
            resultStatus = "success",
            durationMs = 10
        )
        val entry = auditLog.getAll().first()
        val usersStr = entry.arguments["users"] ?: ""
        assertTrue(usersStr.contains("***REDACTED***"), "nested password should be redacted: $usersStr")
        assertTrue(!usersStr.contains("pwd1"), "pwd1 should not appear")
        assertTrue(!usersStr.contains("pwd2"), "pwd2 should not appear")
    }

    @Test
    fun `case-insensitive sensitive key matching`() {
        val auditLog = ToolAuditLog(maxEntries = 100)
        auditLog.log(
            toolName = "test_tool",
            arguments = mapOf("API_KEY" to "k", "PassWord" to "p", "authToken" to "t"),
            resultStatus = "success",
            durationMs = 10
        )
        val entry = auditLog.getAll().first()
        assertEquals("***REDACTED***", entry.arguments["API_KEY"])
        assertEquals("***REDACTED***", entry.arguments["PassWord"])
        assertEquals("***REDACTED***", entry.arguments["authToken"])
    }

    @Test
    fun `non-sensitive value passes through with length cap`() {
        val auditLog = ToolAuditLog(maxEntries = 100)
        val longValue = "x".repeat(1000)
        auditLog.log(
            toolName = "test_tool",
            arguments = mapOf("content" to longValue),
            resultStatus = "success",
            durationMs = 10
        )
        val entry = auditLog.getAll().first()
        val value = entry.arguments["content"] ?: ""
        // 500 字符截断（maxValueLength = 500）
        assertEquals(500, value.length, "Value should be truncated to 500 chars")
    }
}
