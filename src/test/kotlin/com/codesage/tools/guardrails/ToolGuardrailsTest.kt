package com.codesage.tools.guardrails

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ToolGuardrailsTest {

    @Test
    fun `curl command with allow session permission is allowed`() = runBlocking {
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission = ToolGuardrails.Permission.ALLOW_SESSION
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)
        val result = guardrails.preCheck("run_command", mapOf("command" to "curl https://api.github.com"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Allowed)
    }

    @Test
    fun `curl command with allow once permission is allowed`() = runBlocking {
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission = ToolGuardrails.Permission.ALLOW_ONCE
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)
        val result = guardrails.preCheck("run_command", mapOf("command" to "curl https://api.github.com"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Allowed)
    }

    @Test
    fun `curl command with allow permanently permission is allowed`() = runBlocking {
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission = ToolGuardrails.Permission.ALLOW_PERMANENTLY
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)
        val result = guardrails.preCheck("run_command", mapOf("command" to "curl https://api.github.com"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Allowed)
    }

    @Test
    fun `curl command with deny permission is denied`() = runBlocking {
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission = ToolGuardrails.Permission.DENY
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)
        val result = guardrails.preCheck("run_command", mapOf("command" to "curl https://api.github.com"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Denied)
    }

    @Test
    fun `curl command without callback is denied by default`() = runBlocking {
        val guardrails = ToolGuardrails()
        val result = guardrails.preCheck("run_command", mapOf("command" to "curl https://api.github.com"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Denied)
    }

    @Test
    fun `rm -rf command is always denied even with permanent allow callback`() = runBlocking {
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission = ToolGuardrails.Permission.ALLOW_PERMANENTLY
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)
        val result = guardrails.preCheck("run_command", mapOf("command" to "rm -rf /tmp/test"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Denied)
    }

    @Test
    fun `session permission persists for same category commands`() = runBlocking {
        var callCount = 0
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission {
                callCount++
                return ToolGuardrails.Permission.ALLOW_SESSION
            }
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)

        // 第一次请求：需要回调
        val result1 = guardrails.preCheck("run_command", mapOf("command" to "curl https://api.github.com"))
        assertTrue(result1 is ToolGuardrails.PreCheckResult.Allowed)
        assertEquals(1, callCount)

        // 第二次请求同类别：直接通过，无需回调
        val result2 = guardrails.preCheck("run_command", mapOf("command" to "curl https://example.com"))
        assertTrue(result2 is ToolGuardrails.PreCheckResult.Allowed)
        assertEquals(1, callCount) // 回调不再触发
    }

    @Test
    fun `once permission does not persist for second call`() = runBlocking {
        var callCount = 0
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission {
                callCount++
                return ToolGuardrails.Permission.ALLOW_ONCE
            }
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)
        val args = mapOf("command" to "curl https://api.github.com")

        // 第一次：允许
        val result1 = guardrails.preCheck("run_command", args)
        assertTrue(result1 is ToolGuardrails.PreCheckResult.Allowed)
        assertEquals(1, callCount)

        // 第二次：需要再次确认（回调再次触发）
        val result2 = guardrails.preCheck("run_command", args)
        assertTrue(result2 is ToolGuardrails.PreCheckResult.Allowed)
        assertEquals(2, callCount)
    }

    @Test
    fun `clear session permissions removes session and once permissions`() = runBlocking {
        var callCount = 0
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission {
                callCount++
                return ToolGuardrails.Permission.ALLOW_SESSION
            }
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)

        // 获取 session 权限
        val result1 = guardrails.preCheck("run_command", mapOf("command" to "curl https://api.github.com"))
        assertTrue(result1 is ToolGuardrails.PreCheckResult.Allowed)
        assertEquals(1, callCount)

        // 确认权限在
        val result2 = guardrails.preCheck("run_command", mapOf("command" to "curl https://example.com"))
        assertTrue(result2 is ToolGuardrails.PreCheckResult.Allowed)
        assertEquals(1, callCount)

        // 清空 session 权限
        guardrails.clearSessionPermissions()

        // 再次请求需要重新确认
        val result3 = guardrails.preCheck("run_command", mapOf("command" to "curl https://example.com"))
        assertTrue(result3 is ToolGuardrails.PreCheckResult.Allowed)
        assertEquals(2, callCount)
    }

    @Test
    fun `safe command does not require confirmation`() = runBlocking {
        val guardrails = ToolGuardrails()
        val result = guardrails.preCheck("run_command", mapOf("command" to "ls -la"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Allowed)
    }

    @Test
    fun `permanent permission persists after clear session`() = runBlocking {
        var callCount = 0
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission {
                callCount++
                return ToolGuardrails.Permission.ALLOW_PERMANENTLY
            }
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)

        // 获取永久权限
        val result1 = guardrails.preCheck("run_command", mapOf("command" to "curl https://api.github.com"))
        assertTrue(result1 is ToolGuardrails.PreCheckResult.Allowed)
        assertEquals(1, callCount)

        // 清空 session 权限
        guardrails.clearSessionPermissions()

        // 永久权限仍然有效
        val result2 = guardrails.preCheck("run_command", mapOf("command" to "curl https://example.com"))
        assertTrue(result2 is ToolGuardrails.PreCheckResult.Allowed)
        assertEquals(1, callCount)
    }
}
