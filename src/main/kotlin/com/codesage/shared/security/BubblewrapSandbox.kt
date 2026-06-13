package com.codesage.shared.security

import com.codesage.shared.utils.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Linux bubblewrap (`bwrap`) 沙箱实现。
 *
 * 使用用户命名空间将命令限制在最小文件系统视图中：
 * - / 挂载为 tmpfs（空）
 * - 按需绑定挂载 /bin、/usr、/lib、/lib64、/dev/null、/dev/zero 等
 * - 绑定挂载项目目录为 /workspace
 * - 禁止网络（--unshare-net）
 *
 * 需要在目标系统安装 `bubblewrap` 包（Debian/Ubuntu: `apt install bubblewrap`，Fedora: `dnf install bubblewrap`）。
 */
class BubblewrapSandbox(
    private val projectRoot: File?,
    private val mode: CommandSandbox.Mode
) : CommandSandbox {

    private val logger = Logger.getLogger<BubblewrapSandbox>()

    override fun execute(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputChars: Int
    ): CommandSandbox.SandboxResult {
        if (mode == CommandSandbox.Mode.DANGEROUS_FULL_ACCESS) {
            logger.warn("DANGEROUS_FULL_ACCESS mode: running without bubblewrap sandbox")
            return runUnsandboxed(command, workingDir, timeoutMs, maxOutputChars)
        }

        val root = projectRoot?.absolutePath ?: workingDir.absolutePath
        val cmd = buildBwrapCommand(root, command)
        return runProcess(cmd, workingDir, timeoutMs, maxOutputChars, sandboxed = true)
    }

    private fun buildBwrapCommand(root: String, command: String): List<String> {
        val args = mutableListOf("bwrap")

        // 创建新的用户/网络/IPC/挂载命名空间
        args.add("--unshare-all")
        args.add("--unshare-net")

        // 重新初始化 / 为 tmpfs
        args.add("--tmpfs")
        args.add("/")

        // 绑定挂载必要系统目录（只读）
        listOf("/bin", "/usr", "/lib", "/lib64", "/etc").forEach { path ->
            if (File(path).exists()) {
                args.add("--ro-bind")
                args.add(path)
                args.add(path)
            }
        }

        // 绑定挂载项目目录
        args.add("--bind")
        args.add(root)
        args.add("/workspace")

        // dev 节点
        args.add("--dev")
        args.add("/dev")

        // proc（部分命令需要，如 ps）
        args.add("--proc")
        args.add("/proc")

        // 工作目录设为 /workspace
        args.add("--chdir")
        args.add("/workspace")

        // 执行命令
        args.add("/bin/bash")
        args.add("-c")
        args.add(command)

        return args
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
            logger.error("Failed to run bubblewrap command: ${e.message}", e)
            CommandSandbox.SandboxResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                sandboxed = sandboxed,
                error = "Bubblewrap execution failed: ${e.message}"
            )
        }
    }

    companion object {
        fun isAvailable(): Boolean {
            return try {
                ProcessBuilder("bwrap", "--version")
                    .start()
                    .waitFor(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                false
            }
        }
    }
}
