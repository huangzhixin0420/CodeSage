package com.codesage.skill.builtin

import com.codesage.skill.*
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.io.File

/**
 * 内置技能 - Git操作
 */
class GitOperationSkill : Skill {
    override val id = "builtin_git_operation"
    override val name = "Git Operation"
    override val description = "执行Git操作命令"
    override val version = "1.0.0"
    override val category = SkillCategory.GIT
    override val tags = setOf("git", "version", "control", "vcs")
    override val inputSchema = mapOf(
        "command" to mapOf(
            "type" to "string",
            "description" to "Git命令 (如: status, log, diff, branch)",
            "enum" to listOf("status", "log", "diff", "branch", "commit", "push", "pull", "checkout", "add", "stash")
        ),
        "args" to mapOf("type" to "array", "description" to "额外参数"),
        "workingDir" to mapOf("type" to "string", "description" to "工作目录")
    )
    override val outputSchema = mapOf(
        "stdout" to mapOf("type" to "string"),
        "stderr" to mapOf("type" to "string"),
        "exitCode" to mapOf("type" to "integer")
    )

    private val logger = Logger.getLogger<GitOperationSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        val workingDir = context.projectPath ?: return CanExecuteResult(false, "No project path")
        val gitDir = File(workingDir, ".git")
        return if (gitDir.exists()) {
            CanExecuteResult(true)
        } else {
            CanExecuteResult(false, "Not a Git repository: $workingDir")
        }
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return withContext(Dispatchers.IO) {
            try {
                val command = input.getString("command")
                    ?: return@withContext SkillResult.Failure("Missing command parameter")

                val extraArgs = input.getList("args")?.map { it.toString() } ?: emptyList()
                val workingDir = input.getString("workingDir") ?: context.projectPath
                ?: return@withContext SkillResult.Failure("No working directory")

                val result = executeGitCommand(command, extraArgs, workingDir)

                SkillResult.Success(
                    mapOf(
                        "stdout" to result.stdout,
                        "stderr" to result.stderr,
                        "exitCode" to result.exitCode
                    )
                )
            } catch (e: Exception) {
                logger.error("Git operation failed", e)
                SkillResult.Failure("Git operation failed: ${e.message}", e)
            }
        }
    }

    private fun executeGitCommand(
        command: String,
        args: List<String>,
        workingDir: String
    ): CommandResult {
        val fullCommand = listOf("git", command) + args
        return runCommand(fullCommand, File(workingDir))
    }

    private fun runCommand(command: List<String>, workingDir: File): CommandResult {
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(false)
            .start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return CommandResult(stdout, stderr, exitCode)
    }

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )
}

/**
 * 内置技能 - 命令执行
 */
class CommandExecutionSkill : Skill {
    override val id = "builtin_command_execution"
    override val name = "Command Execution"
    override val description = "执行系统命令"
    override val version = "1.0.0"
    override val category = SkillCategory.EXECUTION
    override val tags = setOf("shell", "command", "exec", "terminal")
    override val inputSchema = mapOf(
        "command" to mapOf("type" to "string", "description" to "要执行的命令"),
        "workingDir" to mapOf("type" to "string", "description" to "工作目录"),
        "timeout" to mapOf("type" to "integer", "description" to "超时时间(毫秒)")
    )
    override val outputSchema = mapOf(
        "stdout" to mapOf("type" to "string"),
        "stderr" to mapOf("type" to "string"),
        "exitCode" to mapOf("type" to "integer"),
        "duration" to mapOf("type" to "integer")
    )

    private val logger = Logger.getLogger<CommandExecutionSkill>()

    // Allowed safe commands (no shell metacharacters permitted)
    private val ALLOWED_COMMANDS = setOf(
        "ls", "dir", "pwd", "echo", "cat", "head", "tail", "grep", "find",
        "git", "gradle", "mvn", "npm", "yarn", "pip", "python", "python3",
        "node", "java", "javac", "kotlin", "kotlinc", "go", "rustc", "cargo",
        "docker", "docker-compose", "kubectl", "helm", "terraform", "aws",
        "chmod", "chown", "mkdir", "rmdir", "cp", "mv", "rm", "touch",
        "wc", "sort", "uniq", "diff", "patch", "tar", "zip", "unzip",
        "curl", "wget", "ping", "nc", "netstat", "ss", "lsof", "ps", "top"
    )

    // Dangerous shell metacharacters that could enable injection
    private val SHELL_META_CHARS = setOf(';', '|', '&', '`', '$', '(', ')', '<', '>', '\\')

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        return CanExecuteResult(true, "Command execution allowed with restrictions")
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return withContext(Dispatchers.IO) {
            try {
                val command = input.getString("command")
                    ?: return@withContext SkillResult.Failure("Missing command parameter")

                val projectPath = context.projectPath
                    ?: return@withContext SkillResult.Failure("No project context available")

                val workingDir = input.getString("workingDir")?.let { File(it) }
                    ?: File(projectPath)

                // Security: working directory must be within project
                if (!isPathWithinProject(workingDir, projectPath)) {
                    return@withContext SkillResult.Failure("Access denied: working directory must be within project")
                }

                val timeout = input.getInt("timeout")?.coerceIn(1000, 300000) ?: 60000

                // Security validation
                val validation = validateCommand(command)
                if (!validation.valid) {
                    return@withContext SkillResult.Failure("Security check failed: ${validation.reason}")
                }

                val startTime = System.currentTimeMillis()
                val result = executeCommand(command, workingDir, timeout)
                val duration = System.currentTimeMillis() - startTime

                if (result.exitCode == 0) {
                    SkillResult.Success(
                        mapOf(
                            "stdout" to result.stdout,
                            "stderr" to result.stderr,
                            "exitCode" to result.exitCode,
                            "duration" to duration
                        )
                    )
                } else {
                    SkillResult.Failure("Command failed with exit code ${result.exitCode}: ${result.stderr}")
                }
            } catch (e: Exception) {
                logger.error("Command execution failed", e)
                SkillResult.Failure("Command execution failed: ${e.message}", e)
            }
        }
    }

    private data class CommandValidation(val valid: Boolean, val reason: String = "")

    private fun validateCommand(command: String): CommandValidation {
        if (command.isBlank()) {
            return CommandValidation(false, "Empty command")
        }

        // Reject commands with shell metacharacters to prevent injection
        if (command.any { it in SHELL_META_CHARS }) {
            return CommandValidation(false, "Command contains forbidden shell metacharacters")
        }

        // Extract the base command (first token)
        val baseCommand = command.trim().takeWhile { !it.isWhitespace() }
        val normalized = baseCommand.lowercase()

        // Check against allowlist
        if (normalized !in ALLOWED_COMMANDS) {
            return CommandValidation(false, "Command '$baseCommand' is not in the allowed command list")
        }

        return CommandValidation(true)
    }

    private fun executeCommand(command: String, workingDir: File, timeout: Int): CommandResult {
        // Parse command into tokens (simple space splitting, no shell features)
        val tokens = command.trim().split(Regex("""\s+"""))

        val processBuilder = ProcessBuilder(tokens)
            .directory(workingDir)
            .redirectErrorStream(false)

        val process = processBuilder.start()

        val stdoutFuture = CompletableFuture<String>()
        val stderrFuture = CompletableFuture<String>()

        val stdoutThread = Thread {
            try {
                stdoutFuture.complete(process.inputStream.bufferedReader().readText())
            } catch (e: Exception) {
                stdoutFuture.completeExceptionally(e)
            }
        }
        stdoutThread.isDaemon = true
        stdoutThread.start()

        val stderrThread = Thread {
            try {
                stderrFuture.complete(process.errorStream.bufferedReader().readText())
            } catch (e: Exception) {
                stderrFuture.completeExceptionally(e)
            }
        }
        stderrThread.isDaemon = true
        stderrThread.start()

        val completed = process.waitFor(timeout.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)

        if (!completed) {
            process.destroyForcibly()
            stdoutThread.interrupt()
            stderrThread.interrupt()
            throw java.util.concurrent.TimeoutException("Command timed out after ${timeout}ms")
        }

        return CommandResult(
            stdoutFuture.get(),
            stderrFuture.get(),
            process.exitValue()
        )
    }

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )
}
