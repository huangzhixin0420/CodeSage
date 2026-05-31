package com.codesage.tools.guardrails

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class SensitiveActionPolicyTest {

    private val projectRoot = File(System.getProperty("java.io.tmpdir"), "test_project").apply {
        mkdirs()
    }.absolutePath

    @Test
    fun `delete regular file is safe`() {
        val decision = SensitiveActionPolicy.evaluateDelete("test.txt", projectRoot)
        assertTrue(decision.allowed)
        assertEquals(SensitiveActionPolicy.RiskLevel.SAFE, decision.riskLevel)
    }

    @Test
    fun `delete protected path is denied`() {
        val decision = SensitiveActionPolicy.evaluateDelete(".git/config", projectRoot)
        assertFalse(decision.allowed)
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
    }

    @Test
    fun `delete directory requires confirmation`() {
        val dir = File(projectRoot, "test_dir").apply { mkdirs() }
        val decision = SensitiveActionPolicy.evaluateDelete("test_dir", projectRoot)
        // REQUIRES_CONFIRMATION  verdict 下 allowed=false（需要显式确认）
        assertFalse(decision.allowed)
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `write to sensitive file is denied`() {
        val decision = SensitiveActionPolicy.evaluateWrite(".env", projectRoot)
        assertFalse(decision.allowed)
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
    }

    @Test
    fun `write to new file is safe`() {
        val decision = SensitiveActionPolicy.evaluateWrite("new_file.kt", projectRoot)
        assertTrue(decision.allowed)
        assertEquals(SensitiveActionPolicy.RiskLevel.SAFE, decision.riskLevel)
    }

    @Test
    fun `dangerous command is denied`() {
        val decision = SensitiveActionPolicy.evaluateCommand("rm -rf /")
        assertFalse(decision.allowed)
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
    }

    @Test
    fun `safe command is allowed`() {
        val decision = SensitiveActionPolicy.evaluateCommand("ls -la")
        assertTrue(decision.allowed)
        assertEquals(SensitiveActionPolicy.RiskLevel.SAFE, decision.riskLevel)
    }

    @Test
    fun `curl command requires caution`() {
        val decision = SensitiveActionPolicy.evaluateCommand("curl https://example.com")
        // REQUIRES_CONFIRMATION verdict 下 allowed=false（需要显式确认）
        assertFalse(decision.allowed)
        assertEquals(SensitiveActionPolicy.RiskLevel.CAUTION, decision.riskLevel)
        assertTrue(decision.requiresConfirmation)
    }
}
