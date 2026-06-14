package com.codesage.agent.core

import com.codesage.agent.tools.GitDiffParser
import com.codesage.shared.utils.Logger
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Git worktree 隔离支持。
 *
 * 为子 Agent 在独立 worktree 中运行提供：创建 worktree、收集 diff、清理 worktree。
 * worktree 创建在仓库同级目录的 `.codesage-worktrees/<repoName>/sub-<sessionId>` 下，
 * 避免在主线 worktree 内部嵌套导致的 git 限制与未跟踪文件污染。
 */
object WorktreeIsolation {

    private val logger = Logger.getLogger<WorktreeIsolation>()
    private const val WORKTREE_ROOT_NAME = ".codesage-worktrees"
    private const val BRANCH_PREFIX = "codesage-sub"
    private const val GIT_TIMEOUT_SECONDS = 30L

    data class WorktreeInfo(
        val repoRoot: String,
        val worktreePath: String,
        val branchName: String,
        val baseCommit: String
    )

    data class WorktreeDiff(
        val rawDiff: String,
        val structuredDiff: JsonObject
    )

    /**
     * 在 [repoRoot] 对应的 git 仓库中为子 Agent 创建独立 worktree。
     *
     * @param repoRoot 主项目 git 仓库根目录
     * @param subSessionId 子 Agent 会话 ID，用于生成唯一分支名与目录名
     */
    fun createWorktree(repoRoot: String, subSessionId: String): WorktreeInfo {
        val repoDir = File(repoRoot)
        require(repoDir.isDirectory) { "Repository root does not exist: $repoRoot" }

        val repoName = repoDir.name.takeIf { it.isNotBlank() } ?: "repo"
        val worktreesRoot = File(repoDir.parentFile ?: repoDir, "$WORKTREE_ROOT_NAME/$repoName")
            .apply { mkdirs() }

        val sanitized = subSessionId.replace(Regex("[^A-Za-z0-9_.-]"), "_").takeLast(64)
        val branchName = "$BRANCH_PREFIX-$sanitized"
        val worktreePath = File(worktreesRoot, "sub-$sanitized").absolutePath

        val baseCommit = runGit(repoRoot, listOf("git", "rev-parse", "HEAD"), "get HEAD").trim()
        require(baseCommit.isNotBlank()) { "Failed to determine HEAD commit in $repoRoot" }

        runGit(repoRoot, listOf("git", "branch", branchName, baseCommit), "create branch")
        runGit(repoRoot, listOf("git", "worktree", "add", worktreePath, branchName), "add worktree")

        logger.info(
            "[Worktree] Created worktree at $worktreePath on branch $branchName " +
                    "from base commit ${baseCommit.take(12)}"
        )
        return WorktreeInfo(repoRoot, worktreePath, branchName, baseCommit)
    }

    /**
     * 收集子 Agent 在 worktree 中产生的所有变更（相对 [WorktreeInfo.baseCommit]）。
     */
    fun collectDiff(info: WorktreeInfo): WorktreeDiff {
        val rawDiff = runGit(
            info.worktreePath,
            listOf("git", "diff", info.baseCommit),
            "collect diff"
        )
        val structured = GitDiffParser.parse(rawDiff).toJson()
        logger.info(
            "[Worktree] Collected diff for ${info.branchName}: " +
                    "${rawDiff.length} chars, files=${structured["files"]}"
        )
        return WorktreeDiff(rawDiff = rawDiff, structuredDiff = structured)
    }

    /**
     * 移除 worktree 并清理对应分支。
     *
     * @param deleteBranch 是否同时删除分支，默认 true
     */
    fun cleanup(info: WorktreeInfo, deleteBranch: Boolean = true) {
        try {
            runGit(info.repoRoot, listOf("git", "worktree", "remove", "-f", info.worktreePath), "remove worktree")
            logger.info("[Worktree] Removed worktree at ${info.worktreePath}")
        } catch (e: Exception) {
            logger.warn("[Worktree] Failed to remove worktree: ${e.message}")
            // 兜底：强制删除目录
            try {
                File(info.worktreePath).deleteRecursively()
            } catch (e2: Exception) {
                logger.warn("[Worktree] Failed to delete worktree directory: ${e2.message}")
            }
        }

        if (deleteBranch) {
            try {
                runGit(info.repoRoot, listOf("git", "branch", "-D", info.branchName), "delete branch")
                logger.info("[Worktree] Deleted branch ${info.branchName}")
            } catch (e: Exception) {
                logger.warn("[Worktree] Failed to delete branch ${info.branchName}: ${e.message}")
            }
        }
    }

    private fun runGit(workingDir: String, command: List<String>, description: String): String {
        logger.debug("[Worktree] git $description: ${command.joinToString(" ")} in $workingDir")
        val process = ProcessBuilder(command)
            .directory(File(workingDir))
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("Git command timed out after ${GIT_TIMEOUT_SECONDS}s: ${command.joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            throw RuntimeException(
                "Git command failed (exit=${process.exitValue()}): ${command.joinToString(" ")}\n$output"
            )
        }
        return output
    }
}
