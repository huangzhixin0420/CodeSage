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
    fun `dangerous command requires explicit confirmation (no longer silently denied)`() {
        // 2026-06 P1:危险命令不再被静默拒绝,改为弹框让用户自主选择。
        // 仍要求 RiskLevel.DANGEROUS,verdict 必须是 REQUIRES_CONFIRMATION。
        val decision = SensitiveActionPolicy.evaluateCommand("rm -rf /")
        assertFalse(decision.allowed, "未确认前不应被直接放行")
        assertTrue(decision.requiresConfirmation, "危险命令必须走确认流程")
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
        assertEquals(SensitiveActionPolicy.PolicyDecision.Verdict.REQUIRES_CONFIRMATION, decision.verdict)
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

    // --- P0 回归:git --format / --pretty=format 不能再被 "format" 子串误判 (2026-06) ---
    @Test
    fun `git log oneline is safe`() {
        val decision = SensitiveActionPolicy.evaluateCommand("git log --oneline -n 10")
        assertTrue(decision.allowed)
    }

    // --- 危险命令仍要拦截 (回归) ---
    @Test
    fun `mkfs command requires confirmation`() {
        val decision = SensitiveActionPolicy.evaluateCommand("mkfs.ext4 /dev/sda1")
        assertFalse(decision.allowed, "未确认前不应被直接放行")
        assertTrue(decision.requiresConfirmation, "危险命令必须走确认流程")
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
    }

    @Test
    fun `fdisk command is still dangerous`() {
        val decision = SensitiveActionPolicy.evaluateCommand("fdisk -l")
        assertFalse(decision.allowed, "未确认前不应被直接放行")
        assertTrue(decision.requiresConfirmation, "危险命令必须走确认流程")
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
    }

    @Test
    fun `format keyword as parameter is safe regression for git pretty format`() {
        // 2026-06: --pretty=format:"%h %s %ad" 之前被裸 "format" 子串误判;
        // 修复后 format 作为参数值应被允许;真要格式化磁盘的极少数场景交给用户手工。
        val decision = SensitiveActionPolicy.evaluateCommand("format C:")
        assertTrue(decision.allowed, "format 不再作为 guardrail 危险命令,实际: ${decision.reason}")
    }

    @Test
    fun `git pretty format with percent placeholders is safe`() {
        val decision = SensitiveActionPolicy.evaluateCommand(
            "git log -1 --pretty=format:\"%h %s %ad\" --date=s"
        )
        assertTrue(decision.allowed)
    }

    @Test
    fun `git log with short format flag is safe`() {
        val decision = SensitiveActionPolicy.evaluateCommand("git log --format=%h -10")
        assertTrue(decision.allowed)
    }

    @Test
    fun `dd with if is still dangerous`() {
        val decision = SensitiveActionPolicy.evaluateCommand("dd if=/dev/zero of=/dev/sda")
        assertFalse(decision.allowed, "未确认前不应被直接放行")
        assertTrue(decision.requiresConfirmation, "危险命令必须走确认流程")
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
    }

    @Test
    fun `rm -rf home is still dangerous`() {
        val decision = SensitiveActionPolicy.evaluateCommand("rm -rf ~/Documents")
        assertFalse(decision.allowed, "未确认前不应被直接放行")
        assertTrue(decision.requiresConfirmation, "危险命令必须走确认流程")
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
    }

    @Test
    fun `fork bomb is still dangerous`() {
        val decision = SensitiveActionPolicy.evaluateCommand(":(){ :|:& };:")
        assertFalse(decision.allowed, "未确认前不应被直接放行")
        assertTrue(decision.requiresConfirmation, "危险命令必须走确认流程")
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
    }

    // --- P1 回归:危险命令绝不能是 BLOCKED (2026-06) ---
    // 黑盒黑名单会让用户遇到误报时完全无法绕过;必须保证所有命中危险模式的命令
    // 都走 REQUIRES_CONFIRMATION,让用户在弹框里自主决定。
    @Test
    fun `dangerous commands are never silently blocked, only require confirmation`() {
        val cases = listOf(
            "rm -rf /",
            "rm -rf ~/Documents",
            "rm -rf -f /tmp/x",
            "dd if=/dev/zero of=/dev/sda",
            "mkfs.ext4 /dev/sda1",
            "fdisk -l",
            ":(){ :|:& };:",
            "del /f /s /q C:\\Windows"
        )
        for (cmd in cases) {
            val d = SensitiveActionPolicy.evaluateCommand(cmd)
            assertEquals(
                SensitiveActionPolicy.PolicyDecision.Verdict.REQUIRES_CONFIRMATION,
                d.verdict,
                "危险命令 [$cmd] 不应被 BLOCKED (用户无法绕过),实际: ${d.verdict} / ${d.reason}"
            )
            assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, d.riskLevel, "危险命令 [$cmd] 必须打 DANGEROUS 标签")
            assertTrue(d.requiresConfirmation, "危险命令 [$cmd] 必须走确认流程")
        }
    }
}
