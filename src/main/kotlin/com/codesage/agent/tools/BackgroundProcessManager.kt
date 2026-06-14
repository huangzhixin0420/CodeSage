package com.codesage.agent.tools

import com.codesage.agent.core.AgentStreamEvent
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
 *
 * P0 优化 6.4.3：新增流式输出支持。当调用方提供 [onStream] 时，后台命令会
 * 实时 emit [AgentStreamEvent.CommandOutputStream] 事件，同时仍将输出写入
 * 临时文件，保证 `read_process_output` 可以继续工作。
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
        /**
         * 流式模式下的 stdout 读取线程；非流式为 null。
         */
        val stdoutReader: Thread? = null,
        /**
         * 流式模式下的 stderr 读取线程；非流式为 null。
         */
        val stderrReader: Thread? = null,
        /**
         * 流式模式下监听进程退出并 emit 最终 done 事件的线程；非流式为 null。
         */
        val exitWatcher: Thread? = null,
    )

    private val processes = ConcurrentHashMap<String, ProcessInfo>()

    /**
     * 启动一个后台命令。
     *
     * @param command 要执行的 shell 命令
     * @param workingDir 工作目录
     * @param onStream 可选的流式回调；提供时，stdout/stderr 会实时 emit
     *   [AgentStreamEvent.CommandOutputStream] 事件
     * @return 进程 ID，用于后续 kill/read 操作
     */
    fun start(
        command: String,
        workingDir: String,
        onStream: ((AgentStreamEvent.CommandOutputStream) -> Unit)? = null
    ): String {
        val id = UUID.randomUUID().toString()
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "codesage-bg").apply { mkdirs() }
        val stdoutFile = File(tmpDir, "$id.stdout")
        val stderrFile = File(tmpDir, "$id.stderr")

        return if (onStream != null) {
            startStreaming(command, workingDir, id, stdoutFile, stderrFile, onStream)
        } else {
            startNonStreaming(command, workingDir, id, stdoutFile, stderrFile)
        }
    }

    private fun startNonStreaming(
        command: String,
        workingDir: String,
        id: String,
        stdoutFile: File,
        stderrFile: File
    ): String {
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

    private fun startStreaming(
        command: String,
        workingDir: String,
        id: String,
        stdoutFile: File,
        stderrFile: File,
        onStream: (AgentStreamEvent.CommandOutputStream) -> Unit
    ): String {
        val processBuilder = ProcessBuilder(
            if (System.getProperty("os.name").contains("Windows")) {
                listOf("cmd", "/c", command)
            } else {
                listOf("/bin/bash", "-c", command)
            }
        )
        processBuilder.directory(File(workingDir))
        // 流式模式需要直接读取进程输出，不能重定向到文件
        processBuilder.redirectErrorStream(false)

        val process = processBuilder.start()

        val stdoutReader = createReaderThread(
            name = "codesage-bg-stdout-$id",
            input = process.inputStream.bufferedReader(),
            outputFile = stdoutFile,
            emit = { chunk ->
                onStream(AgentStreamEvent.CommandOutputStream(stdout = chunk))
            }
        )
        stdoutReader.isDaemon = true
        stdoutReader.start()

        val stderrReader = createReaderThread(
            name = "codesage-bg-stderr-$id",
            input = process.errorStream.bufferedReader(),
            outputFile = stderrFile,
            emit = { chunk ->
                onStream(AgentStreamEvent.CommandOutputStream(stderr = chunk))
            }
        )
        stderrReader.isDaemon = true
        stderrReader.start()

        val exitWatcher = Thread({
            try {
                val exitCode = process.waitFor()
                // 等待读取线程把缓冲数据 flush 到文件并 emit 完毕
                stdoutReader.join(1000)
                stderrReader.join(1000)
                onStream(
                    AgentStreamEvent.CommandOutputStream(
                        exitCode = exitCode,
                        processId = id,
                        done = true
                    )
                )
            } catch (_: InterruptedException) {
                // 正常中断，不发射额外事件
            } catch (e: Exception) {
                onStream(
                    AgentStreamEvent.CommandOutputStream(
                        stderr = "Background process watcher failed: ${e.message}",
                        processId = id,
                        done = true
                    )
                )
            }
        }, "codesage-bg-watcher-$id")
        exitWatcher.isDaemon = true
        exitWatcher.start()

        processes[id] = ProcessInfo(
            id = id,
            process = process,
            stdoutFile = stdoutFile,
            stderrFile = stderrFile,
            command = command,
            workingDir = workingDir,
            startTimeMs = System.currentTimeMillis(),
            stdoutReader = stdoutReader,
            stderrReader = stderrReader,
            exitWatcher = exitWatcher
        )
        return id
    }

    private fun createReaderThread(
        name: String,
        input: java.io.BufferedReader,
        outputFile: File,
        emit: (String) -> Unit
    ): Thread = Thread({
        try {
            outputFile.outputStream().bufferedWriter().use { writer ->
                input.useLines { lines ->
                    lines.forEach { line ->
                        val chunk = "$line\n"
                        writer.write(chunk)
                        writer.flush()
                        emit(chunk)
                    }
                }
            }
        } catch (_: InterruptedException) {
            // 取消或 kill，正常结束
        } catch (e: Exception) {
            emit("<reader failed: ${e.message}>\n")
        }
    }, name)

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
        // 流式模式下等待读取线程结束，避免删除正在被写入的临时文件
        runCatching {
            info.stdoutReader?.join(1000)
            info.stderrReader?.join(1000)
            info.exitWatcher?.join(1000)
        }
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
