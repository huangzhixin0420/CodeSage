package com.codesage.agent.core

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 6.6.3 Git worktree 隔离组件测试
 */
class WorktreeIsolationTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun `createWorktree should create branch and worktree directory`() {
        val repo = initGitRepo()
        val info = WorktreeIsolation.createWorktree(repo.absolutePath, "sub_123")

        assertTrue(File(info.worktreePath).isDirectory, "worktree directory should exist")
        assertTrue(info.branchName.startsWith("codesage-sub"), "branch name should use prefix")
        assertEquals(repo.absolutePath, info.repoRoot)
        assertTrue(info.baseCommit.isNotBlank(), "base commit should be captured")

        // 清理
        WorktreeIsolation.cleanup(info)
        assertFalse(File(info.worktreePath).exists(), "worktree directory should be removed")
    }

    @Test
    fun `collectDiff should return changes made inside worktree only`() {
        val repo = initGitRepo()
        val info = WorktreeIsolation.createWorktree(repo.absolutePath, "sub_diff")

        // 在 worktree 中修改文件
        File(info.worktreePath, "hello.txt").writeText("modified in worktree")
        // 在 worktree 中新增文件
        File(info.worktreePath, "new-in-worktree.txt").writeText("brand new")

        // 主线工作区也修改同一个文件，但不应出现在 worktree diff 中
        File(repo, "hello.txt").writeText("modified in main")

        val diff = WorktreeIsolation.collectDiff(info)

        assertTrue(diff.rawDiff.contains("modified in worktree"), "diff should include worktree change")
        assertFalse(diff.rawDiff.contains("modified in main"), "diff should NOT include main worktree changes")
        assertEquals(
            true,
            diff.structuredDiff["has_changes"]?.jsonPrimitive?.booleanOrNull,
            "structured diff should report changes"
        )
        val files = diff.structuredDiff["files"] as? kotlinx.serialization.json.JsonArray
        assertNotNull(files)
        assertTrue(files!!.isNotEmpty(), "structured diff should contain files")

        WorktreeIsolation.cleanup(info)
    }

    @Test
    fun `collectDiff should return empty when no changes`() {
        val repo = initGitRepo()
        val info = WorktreeIsolation.createWorktree(repo.absolutePath, "sub_no_diff")

        val diff = WorktreeIsolation.collectDiff(info)

        assertTrue(diff.rawDiff.isBlank(), "raw diff should be empty")
        assertEquals(
            false,
            diff.structuredDiff["has_changes"]?.jsonPrimitive?.booleanOrNull,
            "structured diff should report no changes"
        )

        WorktreeIsolation.cleanup(info)
    }

    @Test
    fun `cleanup should remove branch when deleteBranch is true`() {
        val repo = initGitRepo()
        val info = WorktreeIsolation.createWorktree(repo.absolutePath, "sub_branch")

        WorktreeIsolation.cleanup(info, deleteBranch = true)

        val branches = runGit(repo, listOf("git", "branch", "--list", info.branchName))
        assertTrue(branches.isBlank(), "branch should be deleted")
    }

    @Test
    fun `cleanup should keep branch when deleteBranch is false`() {
        val repo = initGitRepo()
        val info = WorktreeIsolation.createWorktree(repo.absolutePath, "sub_keep_branch")

        WorktreeIsolation.cleanup(info, deleteBranch = false)

        val branches = runGit(repo, listOf("git", "branch", "--list", info.branchName))
        assertTrue(branches.contains(info.branchName), "branch should be kept")
    }

    private fun initGitRepo(): File {
        val repo = File(tempDir, "repo").apply { mkdirs() }
        runGit(repo, listOf("git", "init"))
        runGit(repo, listOf("git", "config", "user.email", "test@codesage.ai"))
        runGit(repo, listOf("git", "config", "user.name", "Test"))
        File(repo, "hello.txt").writeText("initial")
        runGit(repo, listOf("git", "add", "."))
        runGit(repo, listOf("git", "commit", "-m", "initial"))
        return repo
    }

    private fun runGit(workingDir: File, command: List<String>): String {
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("Git command timed out: ${command.joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            throw RuntimeException("Git command failed: ${command.joinToString(" ")}\n$output")
        }
        return output
    }
}
