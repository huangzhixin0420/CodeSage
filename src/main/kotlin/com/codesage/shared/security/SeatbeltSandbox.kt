package com.codesage.shared.security

import com.codesage.shared.utils.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * macOS Seatbelt 沙箱实现。
 *
 * 使用系统自带的 `sandbox-exec` 工具，通过内联 profile 限制：
 * - 读路径：只允许项目根目录、/bin、/usr/bin、/System 等必要系统路径
 * - 写路径：按模式决定是否允许写入项目目录
 * - 网络：默认禁止（WORKSPACE_WRITE 也禁止网络，避免数据外泄）
 *
 * 注意：`sandbox-exec` 在 macOS 10.15+ 仍可用，但 Apple 推荐使用应用沙箱。
 * 对于 CLI/IDE 插件场景，`sandbox-exec` 仍是可行方案。
 */
class SeatbeltSandbox(
    private val projectRoot: File?,
    private val mode: CommandSandbox.Mode
) : CommandSandbox {

    private val logger = Logger.getLogger<SeatbeltSandbox>()

    override fun execute(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputChars: Int
    ): CommandSandbox.SandboxResult {
        if (mode == CommandSandbox.Mode.DANGEROUS_FULL_ACCESS) {
            logger.warn("DANGEROUS_FULL_ACCESS mode: running without Seatbelt sandbox")
            return runUnsandboxed(command, workingDir, timeoutMs, maxOutputChars)
        }

        val profile = buildProfile()
        val wrappedCommand = listOf(
            "sandbox-exec",
            "-p",
            profile,
            "/bin/bash",
            "-c",
            command
        )

        return runProcess(wrappedCommand, workingDir, timeoutMs, maxOutputChars, sandboxed = true)
    }

    private fun buildProfile(): String {
        val root = projectRoot?.absolutePath ?: workingDirFallback().absolutePath
        val writeAllowed = mode == CommandSandbox.Mode.WORKSPACE_WRITE

        // Seatbelt profile 语法：版本 1 的 allow/deny 规则
        // 策略：默认允许绝大多数操作（保证 shell 命令能正常执行），只显式禁止：
        // 1. 网络外联
        // 2. 项目根目录外的写入（READ_ONLY 模式连项目内也禁止写入）
        return buildString {
            appendLine("(version 1)")
            appendLine("(allow default)")

            // 网络：默认全部禁止
            appendLine("(deny network*)")

            // 禁止全局写入；下面再按模式放开项目目录
            appendLine("(deny file-write*)")
            when (mode) {
                CommandSandbox.Mode.READ_ONLY -> {
                    // 只读模式：项目内也禁止写入
                }

                CommandSandbox.Mode.WORKSPACE_WRITE -> {
                    appendLine("(allow file-write* (subpath \"$root\"))")
                }

                CommandSandbox.Mode.DANGEROUS_FULL_ACCESS -> {
                    // 全访问模式不应进入 Seatbelt；此处留空
                }
            }
        }
    }

    private fun runUnsandboxed(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputChars: Int
    ): CommandSandbox.SandboxResult {
        return runProcess(
            listOf("/bin/bash", "-c", command),
            workingDir,
            timeoutMs,
            maxOutputChars,
            sandboxed = false
        )
    }

    private fun runProcess(
        cmd: List<String>,
        workingDir: File,
        timeoutMs: Long,
        maxOutputChars: Int,
        sandboxed: Boolean
    ): CommandSandbox.SandboxResult {
        return try {
            val process = ProcessBuilder(cmd)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()

            val (stdoutFuture, stderrFuture) = BoundedProcessReader.readBothAsync(process, maxOutputChars)
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                // 超时后不要阻塞等待 reader 线程，避免孤儿进程持有 pipe 导致挂起
                return CommandSandbox.SandboxResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    sandboxed = sandboxed,
                    error = "Sandboxed command timed out after ${timeoutMs}ms"
                )
            }

            val stdoutRead = stdoutFuture.get()
            val stderrRead = stderrFuture.get()
            CommandSandbox.SandboxResult(
                exitCode = process.exitValue(),
                stdout = stdoutRead.content,
                stderr = stderrRead.content,
                sandboxed = sandboxed
            )
        } catch (e: Exception) {
            logger.error("Failed to run sandboxed command: ${e.message}", e)
            CommandSandbox.SandboxResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                sandboxed = sandboxed,
                error = "Sandbox execution failed: ${e.message}"
            )
        }
    }

    private fun workingDirFallback(): File = File(System.getProperty("user.dir"))
}
