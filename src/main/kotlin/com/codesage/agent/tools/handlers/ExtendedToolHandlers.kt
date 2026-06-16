package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.*
import com.codesage.tools.guardrails.SensitiveActionPolicy
import com.codesage.model.dto.Tool
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

/**
 * 扩展工具（Git / Shell / HTTP / 数据处理）的 Handler 适配器
 */
object ExtendedToolHandlers {

    fun createGitStatusHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(gitStatusTool()) { extended.gitStatus(it) }

    fun createGitDiffHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(gitDiffTool()) { extended.gitDiff(it) }

    fun createGitLogHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(gitLogTool()) { extended.gitLog(it) }

    fun createGitBranchHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(gitBranchTool()) { extended.gitBranch(it) }

    fun createExecShellHandler(
        extended: ExtendedTools,
        ideTools: IDETools
    ): ToolHandler =
        object : ToolHandler {
            override val tool: Tool = execShellTool()
            override val riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS
            override suspend fun execute(args: JsonObject): ToolResult {
                // P0 优化 6.4.1：exec_shell 已废弃，统一转发到 run_command 执行。
                return ideTools.runCommand(args)
            }

            override suspend fun execute(
                args: JsonObject,
                onStream: suspend (com.codesage.agent.core.AgentStreamEvent) -> Unit
            ): ToolResult {
                // P0 优化 6.4.3：exec_shell 流式路径同样转发到 run_command。
                return ideTools.runCommand(args, onStream)
            }
        }

    fun createHttpRequestHandler(extended: ExtendedTools): ToolHandler =
        object : ToolHandler {
            override val tool: Tool = httpRequestTool()
            override suspend fun execute(args: JsonObject): ToolResult =
                extended.httpRequest(args)
        }

    fun createParseJsonHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(parseJsonTool()) { extended.parseJson(it) }

    fun createEncodeBase64Handler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(encodeBase64Tool()) { extended.encodeBase64(it) }

    fun createDecodeBase64Handler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(decodeBase64Tool()) { extended.decodeBase64(it) }

    fun createFormatJsonHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(formatJsonTool()) { extended.formatJson(it) }

    fun createHashMd5Handler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(hashMd5Tool()) { extended.hashMd5(it) }

    fun createHashSha256Handler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(hashSha256Tool()) { extended.hashSha256(it) }

    // region 增强版 Git 工具

    fun createGitAddHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(gitAddTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS, executor = {  args ->
            val workingDir = extended.resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
            val all = args["all"]?.jsonPrimitive?.booleanOrNull ?: false
            val files = args["files"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

            val gitDir = File(workingDir, ".git")
            if (!gitDir.exists()) {
                return@FunctionalToolHandler ToolResult.Error("Not a Git repository: $workingDir")
            }

            val cmd = mutableListOf("git", "add")
            if (all) {
                cmd.add("-A")
            } else if (files.isNotEmpty()) {
                cmd.addAll(files)
            } else {
                cmd.add("-A")
            }

            executeGitCommand(cmd, workingDir) { stdout, stderr, exitCode ->
                if (exitCode != 0) {
                    ToolResult.Error("git add failed: $stderr")
                } else {
                    ToolResult.Success(
                        JsonObject(
                            mapOf(
                                "working_dir" to JsonPrimitive(workingDir),
                                "added" to JsonPrimitive(true),
                                "files" to JsonArray(files.map { JsonPrimitive(it) }),
                                "all" to JsonPrimitive(all)
                            )
                        )
                    )
                }
            }
         })

    fun createGitCommitHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(gitCommitTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS, executor = {  args ->
            val workingDir = extended.resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
            val message = args["message"]?.jsonPrimitive?.content
            val amend = args["amend"]?.jsonPrimitive?.booleanOrNull ?: false
            val noVerify = args["no_verify"]?.jsonPrimitive?.booleanOrNull ?: false

            val gitDir = File(workingDir, ".git")
            if (!gitDir.exists()) {
                return@FunctionalToolHandler ToolResult.Error("Not a Git repository: $workingDir")
            }

            val cmd = mutableListOf("git", "commit")
            if (amend || message.isNullOrBlank()) {
                cmd.add("--amend")
                if (!message.isNullOrBlank()) {
                    cmd.addAll(listOf("-m", message))
                }
                cmd.add("--no-edit")
            } else {
                cmd.addAll(listOf("-m", message))
            }
            if (noVerify) cmd.add("--no-verify")

            executeGitCommand(cmd, workingDir) { stdout, stderr, exitCode ->
                if (exitCode != 0) {
                    ToolResult.Error("git commit failed: $stderr")
                } else {
                    ToolResult.Success(
                        JsonObject(
                            mapOf(
                                "working_dir" to JsonPrimitive(workingDir),
                                "committed" to JsonPrimitive(true),
                                "message" to JsonPrimitive(message ?: ""),
                                "amend" to JsonPrimitive(amend)
                            )
                        )
                    )
                }
            }
         })

    fun createGitStashHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(gitStashTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS, executor = {  args ->
            val workingDir = extended.resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
            val action = args["action"]?.jsonPrimitive?.content ?: "save"
            val message = args["message"]?.jsonPrimitive?.content

            val gitDir = File(workingDir, ".git")
            if (!gitDir.exists()) {
                return@FunctionalToolHandler ToolResult.Error("Not a Git repository: $workingDir")
            }

            val cmd = when (action) {
                "save" -> mutableListOf("git", "stash", "push", "-m", message ?: "CodeSage stash")
                "pop" -> mutableListOf("git", "stash", "pop")
                "list" -> mutableListOf("git", "stash", "list")
                "clear" -> mutableListOf("git", "stash", "clear")
                "drop" -> mutableListOf("git", "stash", "drop")
                else -> return@FunctionalToolHandler ToolResult.Error("Unknown stash action: $action")
            }

            executeGitCommand(cmd, workingDir) { stdout, stderr, exitCode ->
                if (exitCode != 0) {
                    ToolResult.Error("git stash $action failed: $stderr")
                } else {
                    ToolResult.Success(
                        JsonObject(
                            mapOf(
                                "action" to JsonPrimitive(action),
                                "output" to JsonPrimitive(stdout),
                                "success" to JsonPrimitive(true)
                            )
                        )
                    )
                }
            }
         })

    fun createGitBlameHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(gitBlameTool()) { args ->
            val workingDir = extended.resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
            val file = args["file"]?.jsonPrimitive?.content
                ?: return@FunctionalToolHandler ToolResult.Error("Missing 'file' parameter")
            val lineStart = args["line_start"]?.jsonPrimitive?.intOrNull
            val lineEnd = args["line_end"]?.jsonPrimitive?.intOrNull

            val gitDir = File(workingDir, ".git")
            if (!gitDir.exists()) {
                return@FunctionalToolHandler ToolResult.Error("Not a Git repository: $workingDir")
            }

            val cmd = mutableListOf("git", "blame", "--porcelain")
            if (lineStart != null && lineEnd != null) {
                cmd.add("-L")
                cmd.add("$lineStart,$lineEnd")
            }
            cmd.add(file)

            executeGitCommand(cmd, workingDir) { stdout, stderr, exitCode ->
                if (exitCode != 0) {
                    ToolResult.Error("git blame failed: $stderr")
                } else {
                    val lines = stdout.lines()
                    val annotations = mutableListOf<JsonObject>()
                    var currentCommit = ""
                    var currentAuthor = ""
                    var currentTime = ""
                    var currentLine = ""
                    var lineNum = 0

                    lines.forEach { line ->
                        when {
                            line.startsWith("\t") -> {
                                currentLine = line.removePrefix("\t")
                                lineNum++
                                annotations.add(
                                    JsonObject(
                                        mapOf(
                                            "line" to JsonPrimitive(lineNum),
                                            "commit" to JsonPrimitive(currentCommit.take(8)),
                                            "author" to JsonPrimitive(currentAuthor),
                                            "time" to JsonPrimitive(currentTime),
                                            "content" to JsonPrimitive(currentLine)
                                        )
                                    )
                                )
                            }

                            line.startsWith("author ") -> currentAuthor = line.removePrefix("author ")
                            line.startsWith("author-time ") -> {
                                val ts = line.removePrefix("author-time ").toLongOrNull() ?: 0L
                                currentTime = java.time.Instant.ofEpochSecond(ts).toString()
                            }

                            Regex("""^[a-f0-9]{40}""").containsMatchIn(line.take(40)) -> {
                                currentCommit = line.take(40)
                            }
                        }
                    }

                    ToolResult.Success(
                        JsonObject(
                            mapOf(
                                "file" to JsonPrimitive(file),
                                "annotations" to JsonArray(annotations),
                                "total_lines" to JsonPrimitive(annotations.size)
                            )
                        )
                    )
                }
            }
        }

    /**
     * 6.6.1 新增：将当前分支推送到远程仓库。
     *
     * 若分支尚无上游跟踪分支，自动使用 `git push -u origin <branch>`。
     */
    fun createGitPushHandler(extended: ExtendedTools): ToolHandler =
        FunctionalToolHandler(gitPushTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS, executor = {  args ->
            val workingDir = extended.resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
            val remote = args["remote"]?.jsonPrimitive?.content ?: "origin"
            val branchArg = args["branch"]?.jsonPrimitive?.content

            val gitDir = File(workingDir, ".git")
            if (!gitDir.exists()) {
                return@FunctionalToolHandler ToolResult.Error("Not a Git repository: $workingDir")
            }

            val branch = branchArg ?: runGit(workingDir, listOf("git", "rev-parse", "--abbrev-ref", "HEAD"))
                .stdout
                .trim()
                .takeIf { it.isNotBlank() && it != "HEAD" }
            ?: return@FunctionalToolHandler ToolResult.Error("Cannot determine current branch (detached HEAD?)")

            val upstream = runGit(workingDir, listOf("git", "rev-parse", "--abbrev-ref", "$branch@{upstream}"))
            val setUpstream = upstream.exitCode != 0 || upstream.stdout.isBlank()

            val cmd = mutableListOf("git", "push")
            if (setUpstream) cmd.add("-u")
            cmd.add(remote)
            cmd.add(branch)

            executeGitCommand(cmd, workingDir) { stdout, stderr, exitCode ->
                if (exitCode != 0) {
                    ToolResult.Error("git push failed: ${stderr.ifBlank { stdout }}")
                } else {
                    ToolResult.Success(
                        JsonObject(
                            mapOf(
                                "pushed" to JsonPrimitive(true),
                                "remote" to JsonPrimitive(remote),
                                "branch" to JsonPrimitive(branch),
                                "upstream_set" to JsonPrimitive(setUpstream),
                                "output" to JsonPrimitive(stdout.ifBlank { stderr })
                            )
                        )
                    )
                }
            }
         })

    // endregion

    private fun executeGitCommand(
        command: List<String>,
        workingDir: String,
        transform: (String, String, Int) -> ToolResult
    ): ToolResult {
        val logger = Logger.getLogger<ExtendedToolHandlers>()
        val gitDir = File(workingDir, ".git")
        if (!gitDir.exists()) {
            return ToolResult.Error("Not a Git repository: $workingDir")
        }
        return try {
            val process = ProcessBuilder(command)
                .directory(File(workingDir))
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            transform(stdout, stderr, exitCode)
        } catch (e: Exception) {
            logger.error("Git command failed: $command", e)
            ToolResult.Error("Git command failed: ${e.message}")
        }
    }

    private data class GitOutput(val stdout: String, val stderr: String, val exitCode: Int)

    private fun runGit(workingDir: String, command: List<String>): GitOutput {
        return try {
            val process = ProcessBuilder(command)
                .directory(File(workingDir))
                .redirectErrorStream(false)
                .start()
            GitOutput(
                stdout = process.inputStream.bufferedReader().readText(),
                stderr = process.errorStream.bufferedReader().readText(),
                exitCode = process.waitFor()
            )
        } catch (e: Exception) {
            GitOutput("", e.message ?: "Git command failed", -1)
        }
    }
}

// T6.1 修复：ExtendedTools.resolveWorkingDir 已经是 internal，同 module 内可直接访问。
// 原反射 hack 是冗余的。
