package com.codesage.agent.tools

import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * P0 优化 6.4.2：后台/长时间运行命令管理器。
 *
 * 设计要点：
 * - 进程输出重定向到临时文件，避免内存爆炸，也支持多次续读。
 * - `read_process_output` 可读取运行中或已结束进程的 stdout/stderr。
 * - `kill_process` 可安全终止后台进程。
 */
object BackgroundProcessManager {

    data class ProcessInfo(
        val id: String,
        val process: Process,
        val stdoutFile: File,
        val stderrFile: File,
        val command: String,
        val workingDir: String,
        val startTimeMs: Long,
    )

    private val processes = ConcurrentHashMap<String, ProcessInfo>()

    /**
     * 启动一个后台命令。
     *
     * @return 进程 ID，用于后续 kill/read 操作
     */
    fun start(command: String, workingDir: String): String {
        val id = UUID.randomUUID().toString()
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "codesage-bg").apply { mkdirs() }
        val stdoutFile = File(tmpDir, "$id.stdout")
        val stderrFile = File(tmpDir, "$id.stderr")

        val processBuilder = ProcessBuilder(
            if (System.getProperty("os.name").contains("Windows")) {
                listOf("cmd", "/c", command)
            } else {
                listOf("/bin/bash", "-c", command)
            }
        )
        processBuilder.directory(File(workingDir))
        processBuilder.redirectOutput(stdoutFile)
        processBuilder.redirectError(stderrFile)

        val process = processBuilder.start()
        processes[id] = ProcessInfo(
            id = id,
            process = process,
            stdoutFile = stdoutFile,
            stderrFile = stderrFile,
            command = command,
            workingDir = workingDir,
            startTimeMs = System.currentTimeMillis()
        )
        return id
    }

    /**
     * 读取指定进程的最新输出。
     *
     * @param processId 进程 ID
     * @param maxChars 单流最大读取字符数
     * @return 输出数据；进程不存在时返回 null
     */
    fun readOutput(processId: String, maxChars: Int): ToolResult? {
        val info = processes[processId] ?: return null
        val stdout = readTail(info.stdoutFile, maxChars)
        val stderr = readTail(info.stderrFile, maxChars)
        val running = info.process.isAlive
        val exitCode = if (running) null else info.process.exitValue()
        val runtimeMs = System.currentTimeMillis() - info.startTimeMs

        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "process_id" to JsonPrimitive(processId),
                    "command" to JsonPrimitive(info.command),
                    "running" to JsonPrimitive(running),
                    "exit_code" to JsonPrimitive(exitCode),
                    "runtime_ms" to JsonPrimitive(runtimeMs),
                    "stdout" to JsonPrimitive(stdout),
                    "stderr" to JsonPrimitive(stderr)
                )
            )
        )
    }

    /**
     * 终止指定进程。
     *
     * @return 操作结果；进程不存在时返回 null
     */
    fun kill(processId: String): ToolResult? {
        val info = processes[processId] ?: return null
        val wasAlive = info.process.isAlive
        info.process.destroyForcibly()
        val exitCode = try {
            info.process.waitFor()
            info.process.exitValue()
        } catch (_: Exception) {
            -1
        }
        cleanup(info)
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "process_id" to JsonPrimitive(processId),
                    "killed" to JsonPrimitive(wasAlive),
                    "exit_code" to JsonPrimitive(exitCode)
                )
            )
        )
    }

    /**
     * 清理已结束进程的资源。公开给外部定时任务或测试使用。
     */
    fun cleanupFinished() {
        processes.values.filter { !it.process.isAlive }.forEach { cleanup(it) }
    }

    private fun cleanup(info: ProcessInfo) {
        processes.remove(info.id)
        runCatching { info.stdoutFile.delete() }
        runCatching { info.stderrFile.delete() }
    }

    private fun readTail(file: File, maxChars: Int): String {
        if (!file.exists()) return ""
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.length <= maxChars) {
                text
            } else {
                // 截断尾部，保留最后 maxChars 个字符
                text.takeLast(maxChars)
            }
        } catch (e: Exception) {
            "<read failed: ${e.message}>"
        }
    }
}
