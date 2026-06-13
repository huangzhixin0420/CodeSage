package com.codesage.tools.guardrails

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GuardrailsTest {

    @Test
    fun `should require confirmation for dangerous tools`() = runBlocking {
        var confirmationRequested = false
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission {
                confirmationRequested = true
                return ToolGuardrails.Permission.DENY
            }
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)
        // curl command requires caution (needs confirmation)
        val result = guardrails.preCheck("run_command", mapOf("command" to "curl https://example.com"), "call_1")

        assertTrue(confirmationRequested, "Confirmation should be requested for curl command")
        assertTrue(result is ToolGuardrails.PreCheckResult.Denied)
    }

    @Test
    fun `should allow approved dangerous tools`() = runBlocking {
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission = ToolGuardrails.Permission.ALLOW_ONCE
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)
        val result = guardrails.preCheck("run_command", mapOf("command" to "curl https://example.com"), "call_2")

        assertTrue(result is ToolGuardrails.PreCheckResult.Allowed)
    }

    @Test
    fun `should timeout confirmation and deny`() = runBlocking {
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission {
                // 模拟长时间不响应
                kotlinx.coroutines.delay(5000)
                return ToolGuardrails.Permission.ALLOW_ONCE
            }
        }

        // 设置很短的超时
        val guardrails = ToolGuardrails(
            confirmationCallback = callback,
            confirmationTimeoutMs = 100
        )

        // curl command requires confirmation
        val result = guardrails.preCheck("run_command", mapOf("command" to "curl https://example.com"), "call_3")

        assertTrue(result is ToolGuardrails.PreCheckResult.Denied)
        assertTrue((result as ToolGuardrails.PreCheckResult.Denied).reason.contains("declined"))
    }

    @Test
    fun `should emit confirmation event when toolCallId provided`() = runBlocking {
        var emittedEvent: com.codesage.agent.core.AgentStreamEvent.ToolConfirmationNeeded? = null
        val emitter: (com.codesage.agent.core.AgentStreamEvent.ToolConfirmationNeeded) -> Unit = { event ->
            emittedEvent = event
        }

        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission = ToolGuardrails.Permission.ALLOW_ONCE
        }

        val guardrails = ToolGuardrails(
            confirmationCallback = callback,
            eventEmitter = emitter
        )

        // write to existing file requires confirmation
        val tempFile = java.io.File.createTempFile("existing", ".txt")
        tempFile.deleteOnExit()
        guardrails.preCheck(
            "write_file",
            mapOf("path" to tempFile.absolutePath, "content" to "x"),
            "call_4"
        )

        assertNotNull(emittedEvent)
        assertEquals("call_4", emittedEvent!!.toolCallId)
        assertEquals("write_file", emittedEvent!!.toolName)
    }

    @Test
    fun `should not emit confirmation event without toolCallId`() = runBlocking {
        var eventEmitted = false
        val emitter: (com.codesage.agent.core.AgentStreamEvent.ToolConfirmationNeeded) -> Unit = {
            eventEmitted = true
        }

        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission = ToolGuardrails.Permission.ALLOW_ONCE
        }

        val guardrails = ToolGuardrails(
            confirmationCallback = callback,
            eventEmitter = emitter
        )

        guardrails.preCheck("delete_file", mapOf("path" to "test.txt"))

        assertFalse(eventEmitted, "Event should not be emitted without toolCallId")
    }

    @Test
    fun `safe tool should not require confirmation`() = runBlocking {
        val guardrails = ToolGuardrails()
        val result = guardrails.preCheck("read_file", mapOf("path" to "test.txt"), "call_5")

        assertTrue(result is ToolGuardrails.PreCheckResult.Allowed)
    }

    @Test
    fun `dangerous command is allowed when user grants ALLOW_PERMANENTLY in confirmation dialog`() = runBlocking {
        // 2026-06 P1:危险命令不再 silent deny,改为 REQUIRES_CONFIRMATION 走弹框。
        // 用户在弹框里点"永久允许"后,后续调用应直接放行。
        val callback = object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission = ToolGuardrails.Permission.ALLOW_PERMANENTLY
        }

        val guardrails = ToolGuardrails(confirmationCallback = callback)
        val result = guardrails.preCheck("run_command", mapOf("command" to "rm -rf /"), "call_6")

        assertTrue(
            result is ToolGuardrails.PreCheckResult.Allowed,
            "用户选择永久允许后,危险命令应被放行,实际: $result"
        )
    }

    @Test
    fun `dangerous command is denied in headless mode (no callback)`() = runBlocking {
        // 2026-06 P1:Headless 模式(confirmationCallback = null)下危险命令默认拒绝,
        // 但走的是 CONFIRMATION_DENIED 路径(reason 中含"User declined"),而不是
        // 旧版的 POLICY_VIOLATION 静默拒绝。
        val guardrails = ToolGuardrails()  // no callback
        val result = guardrails.preCheck("run_command", mapOf("command" to "rm -rf /"), "call_7")

        assertTrue(result is ToolGuardrails.PreCheckResult.Denied)
        assertTrue(
            (result as ToolGuardrails.PreCheckResult.Denied).reason.contains("declined", ignoreCase = true) ||
                result.reason.contains("拒绝", ignoreCase = true),
            "Headless 模式默认拒绝应标记为 declined,实际: ${result.reason}"
        )
    }
}
