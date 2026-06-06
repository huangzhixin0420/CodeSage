package com.codesage.tools.guardrails

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

/**
 * C4 修复验证：路径穿越防护。
 *
 * 旧实现用 `relativePath.contains(".git")` 这种 substring 匹配做防护，可被以下绕过：
 * - `xgit`（含 .git 子串但不是 .git 目录）
 * - `.git_bak`（同样 .git 子串）
 * - `path = "/etc/passwd"` 直接绝对路径绕过
 * - `path = "../../../etc/passwd"` 路径穿越
 *
 * 新实现用：
 * 1. canonical 比较 + startsWith 强制路径必须在 projectRoot 内
 * 2. 路径段精确匹配（拆 `/` 后的任一段必须等于 PROTECTED_PATHS 任一项）
 */
class SensitiveActionPolicyPathTraversalTest {

    private fun makeProjectRoot(): String {
        val root = Files.createTempDirectory("codesage-test-").toFile()
        // 创建一个真实的 .git 目录
        File(root, ".git").mkdirs()
        return root.absolutePath
    }

    @Test
    fun `blocks path traversal via relative dot dot`() {
        val projectRoot = makeProjectRoot()
        val decision = SensitiveActionPolicy.evaluateDelete("../../etc/passwd", projectRoot)
        // 解析后 canonical 路径不在 project 内，应被 BLOCKED
        assertEquals(SensitiveActionPolicy.PolicyDecision.Verdict.BLOCKED, decision.verdict)
    }

    @Test
    fun `blocks absolute path outside project`() {
        val projectRoot = makeProjectRoot()
        val decision = SensitiveActionPolicy.evaluateDelete("/etc/passwd", projectRoot)
        assertEquals(SensitiveActionPolicy.PolicyDecision.Verdict.BLOCKED, decision.verdict)
    }

    @Test
    fun `blocks pseudo-path xgit that contains dot git substring`() {
        // 旧实现：relativePath.contains(".git") → true → 误判为保护路径
        // 新实现：拆段后 `xgit` 不在 PROTECTED_PATHS 中，不误判
        val projectRoot = makeProjectRoot()
        // 创建一个非保护的 xgit 文件
        val xgitFile = File(projectRoot, "xgit").apply { writeText("test") }
        val decision = SensitiveActionPolicy.evaluateDelete("xgit", projectRoot)
        // xgit 不在 PROTECTED_PATHS（保护列表是 .git），不应该被当作 .git 拒绝
        assertNotEquals(
            "Protected path cannot be deleted",
            decision.reason,
            "xgit should NOT be treated as protected (it has .git substring but is a different path)"
        )
    }

    @Test
    fun `blocks actual dot git directory`() {
        val projectRoot = makeProjectRoot()
        val decision = SensitiveActionPolicy.evaluateDelete(".git", projectRoot)
        assertEquals(SensitiveActionPolicy.PolicyDecision.Verdict.BLOCKED, decision.verdict)
        assertEquals(SensitiveActionPolicy.RiskLevel.DANGEROUS, decision.riskLevel)
    }

    @Test
    fun `blocks dot git config file`() {
        val projectRoot = makeProjectRoot()
        val decision = SensitiveActionPolicy.evaluateDelete(".git/config", projectRoot)
        assertEquals(SensitiveActionPolicy.PolicyDecision.Verdict.BLOCKED, decision.verdict)
    }

    @Test
    fun `blocks node_modules directory`() {
        val projectRoot = makeProjectRoot()
        val nmDir = File(projectRoot, "node_modules").apply { mkdirs() }
        val decision = SensitiveActionPolicy.evaluateDelete("node_modules", projectRoot)
        // node_modules 是目录 → REQUIRES_CONFIRMATION（不在 PROTECTED_PATHS 但因是目录）
        // 实际上 PROTECTED_PATHS 包含 node_modules，所以应该是 BLOCKED
        assertEquals(SensitiveActionPolicy.PolicyDecision.Verdict.BLOCKED, decision.verdict)
    }

    @Test
    fun `blocks path traversal in evaluateWrite`() {
        val projectRoot = makeProjectRoot()
        val decision = SensitiveActionPolicy.evaluateWrite("../../malicious.kt", projectRoot, "content")
        assertEquals(SensitiveActionPolicy.PolicyDecision.Verdict.BLOCKED, decision.verdict)
    }

    @Test
    fun `blocks absolute path in evaluateWrite`() {
        val projectRoot = makeProjectRoot()
        val decision = SensitiveActionPolicy.evaluateWrite("/etc/passwd", projectRoot, "x")
        assertEquals(SensitiveActionPolicy.PolicyDecision.Verdict.BLOCKED, decision.verdict)
    }
}
