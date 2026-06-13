package com.codesage.shared.security

import com.codesage.shared.utils.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 路径级沙箱兜底实现。
 *
 * 当平台不支持 OS 级沙箱（如 Windows、未安装 bwrap 的 Linux）时使用。
 * 仅提供最后一道防线：
 * - 检查命令是否尝试访问项目根目录之外的敏感路径
 * - 记录 warn 日志
 * - 不阻止执行（因为 ProcessBuilder 本身已受操作系统权限约束）
 *
 * 注意：这不是真正的隔离，应尽可能使用 Seatbelt 或 bubblewrap。
 */
class PathBasedSandbox(
    private val projectRoot: File?,
    private val mode: CommandSandbox.Mode
) : CommandSandbox {

    private val logger = Logger.getLogger<PathBasedSandbox>()

    override fun execute(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputChars: Int
    ): CommandSandbox.SandboxResult {
        if (mode == CommandSandbox.Mode.DANGEROUS_FULL_ACCESS) {
            logger.warn("DANGEROUS_FULL_ACCESS mode: running with only OS permissions")
            return runUnsandboxed(command, workingDir, timeoutMs, maxOutputChars)
        }

        // 仅做审计和路径模式检查
        val normalizedWorkingDir = workingDir.canonicalPath
        val root = projectRoot?.canonicalPath ?: normalizedWorkingDir

        val suspiciousPatterns = listOf(
            Regex("""\s+(?:>|>>)\s*(/[^\s]+)"""), // 绝对路径重定向输出
            Regex("""\bcd\s+(/[^\s]+)"""),
            Regex("""\bcp\s+-[a-zA-Z]*\s+(/[^\s]+)""")
        )

        for (pattern in suspiciousPatterns) {
            pattern.find(command)?.let { match ->
                val path = match.groupValues[1]
                if (!path.startsWith(root)) {
                    logger.warn("PathBasedSandbox detected suspicious absolute path in command: $path")
                }
            }
        }

        return runUnsandboxed(command, workingDir, timeoutMs, maxOutputChars).copy(sandboxed = false)
    }

    private fun runUnsandboxed(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputChars: Int
    ): CommandSandbox.SandboxResult {
        return try {
            val process = ProcessBuilder(
                if (System.getProperty("os.name").lowercase().contains("windows")) {
                    listOf("cmd", "/c", command)
                } else {
                    listOf("/bin/bash", "-c", command)
                }
            )
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()

            val (stdoutFuture, stderrFuture) = BoundedProcessReader.readBothAsync(process, maxOutputChars)
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                return CommandSandbox.SandboxResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    sandboxed = false,
                    error = "Command timed out after ${timeoutMs}ms"
                )
            }

            val stdoutRead = stdoutFuture.get()
            val stderrRead = stderrFuture.get()
            CommandSandbox.SandboxResult(
                exitCode = process.exitValue(),
                stdout = stdoutRead.content,
                stderr = stderrRead.content,
                sandboxed = false
            )
        } catch (e: Exception) {
            logger.error("Failed to run command: ${e.message}", e)
            CommandSandbox.SandboxResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                sandboxed = false,
                error = "Command execution failed: ${e.message}"
            )
        }
    }
}
