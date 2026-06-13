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
    fun `rm -rf command is allowed when user grants ALLOW_PERMANENTLY in confirmation dialog`() = runBlocking {
        // 2026-06 P1:黑盒黑名单 → 用户自主选择。危险命令现在可以被用户"永久允许"。
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
        assertTrue(
            result is ToolGuardrails.PreCheckResult.Allowed,
            "用户选择永久允许后,rm -rf 应被放行,实际: $result"
        )
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

    // ===== exec_shell guardrail routing (regression: C3 修复导致 exec_shell 落入 unknown 分支) =====

    @Test
    fun `exec_shell with safe command is allowed`() = runBlocking {
        val guardrails = ToolGuardrails()
        val result = guardrails.preCheck("exec_shell", mapOf("command" to "echo hello"))
        assertTrue(
            result is ToolGuardrails.PreCheckResult.Allowed,
            "Safe exec_shell command should be allowed without confirmation, got: $result"
        )
    }

    @Test
    fun `exec_shell with dangerous command requires confirmation (not silently blocked)`() = runBlocking {
        // 2026-06 P1:危险命令不再 silent deny,改为 REQUIRES_CONFIRMATION。
        // headless 模式(confirmationCallback = null)下默认拒绝,但 reason 应包含危险标签
        // 而不是直接吞掉——这跟原来的"block"语义在 UI 层有区别。
        val guardrails = ToolGuardrails()
        val result = guardrails.preCheck("exec_shell", mapOf("command" to "rm -rf /"))
        assertTrue(
            result is ToolGuardrails.PreCheckResult.Denied,
            "Headless 模式危险命令应默认拒绝,got: $result"
        )
        assertTrue(
            (result as ToolGuardrails.PreCheckResult.Denied).reason.contains("危险", ignoreCase = true) ||
                result.reason.contains("danger", ignoreCase = true),
            "Block reason 应体现危险,got: ${result.reason}"
        )
    }

    @Test
    fun `exec_shell with network command requires confirmation`() = runBlocking {
        val guardrails = ToolGuardrails() // no callback -> headless auto-deny
        val result = guardrails.preCheck("exec_shell", mapOf("command" to "curl https://api.github.com"))
        assertTrue(
            result is ToolGuardrails.PreCheckResult.Denied,
            "Network command should require confirmation (denied in headless), got: $result"
        )
        assertTrue(
            (result as ToolGuardrails.PreCheckResult.Denied).reason.contains("Network", ignoreCase = true),
            "Reason should mention network, got: ${result.reason}"
        )
    }

    // ===== 2026-06 修复:CodeInsight 工具默认放行(只读 AST 分析) =====

    @Test
    fun `get_project_stats does not require confirmation (read-only CodeInsight tool)`() = runBlocking {
        // 历史 bug: get_project_stats 漏在 KNOWN_SAFE_TOOLS 之外,LLM 第一次想用就被
        // "Unknown tool ... explicit user confirmation required" 拒绝。
        val guardrails = ToolGuardrails()
        val result = guardrails.preCheck("get_project_stats", emptyMap())
        assertTrue(
            result is ToolGuardrails.PreCheckResult.Allowed,
            "get_project_stats should be auto-allowed (read-only AST analysis); got: $result"
        )
    }

    @Test
    fun `all CodeInsight tools are auto-allowed`() = runBlocking {
        // 把整个 CodeInsight 套件都过一遍,任何一个回归到 require-confirmation 就立刻报警
        val guardrails = ToolGuardrails()
        for (name in listOf(
            "analyze_symbol", "find_usages", "get_inheritance_chain",
            "get_file_summary", "get_project_stats"
        )) {
            val result = guardrails.preCheck(name, emptyMap())
            assertTrue(
                result is ToolGuardrails.PreCheckResult.Allowed,
                "$name should be auto-allowed (read-only CodeInsight tool); got: $result"
            )
        }
    }

    @Test
    fun `exec_shell with network command is allowed when user confirms`() = runBlocking {
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission = ToolGuardrails.Permission.ALLOW_ONCE
        }
        val guardrails = ToolGuardrails(confirmationCallback = callback)
        val result = guardrails.preCheck("exec_shell", mapOf("command" to "curl https://api.github.com"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Allowed)
    }
}
