package com.codesage.shared.security

import com.codesage.shared.utils.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OS 级命令执行沙箱抽象。
 *
 * 设计目标：将 AI 执行的 shell 命令限制在受控范围内，防止路径逃逸、网络外联和恶意操作。
 * 实现分层：
 * - macOS：Seatbelt (`sandbox-exec`)
 * - Linux：bubblewrap (`bwrap`)，若不可用则降级为路径级白名单
 * - Windows：当前仅提供路径级白名单（Windows Sandbox 作为完整隔离成本过高）
 *
 * 三种模式：
 * - READ_ONLY：只允许读项目目录
 * - WORKSPACE_WRITE：允许读/写项目目录（默认）
 * - DANGEROUS_FULL_ACCESS：无 OS 级限制，但会明确警告并记录审计
 */
interface CommandSandbox {

    enum class Mode {
        READ_ONLY,
        WORKSPACE_WRITE,
        DANGEROUS_FULL_ACCESS
    }

    /**
     * 使用沙箱执行命令。
     *
     * @param command 原始命令（将被 shell 解释执行）
     * @param workingDir 工作目录
     * @param timeoutMs 超时毫秒
     * @param maxOutputChars 单流最大输出字符数，超过则截断
     * @return 执行结果
     */
    fun execute(command: String, workingDir: File, timeoutMs: Long, maxOutputChars: Int = Int.MAX_VALUE): SandboxResult

    data class SandboxResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val sandboxed: Boolean,
        val error: String? = null
    )

    companion object {
        private val logger = Logger.getLogger<CommandSandbox>()

        /**
         * 创建适合当前平台的沙箱实例。
         *
         * @param projectRoot 项目根目录，用于计算允许读写的路径
         * @param mode 沙箱模式
         */
        fun create(projectRoot: File?, mode: Mode): CommandSandbox {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") -> {
                    SeatbeltSandbox(projectRoot, mode)
                }

                os.contains("linux") -> {
                    if (BubblewrapSandbox.isAvailable()) {
                        BubblewrapSandbox(projectRoot, mode)
                    } else {
                        logger.warn("bubblewrap (bwrap) not found, falling back to path-based sandbox")
                        PathBasedSandbox(projectRoot, mode)
                    }
                }

                else -> {
                    logger.warn("OS-level sandbox not implemented for $os, falling back to path-based sandbox")
                    PathBasedSandbox(projectRoot, mode)
                }
            }
        }
    }
}
