package com.codesage.acp.transport

import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动外部 ACP agent 子进程，并将其 stdio 包装为 [AcpSessionTransport]。
 *
 * 使用方式（连接外部 ACP agent）：
 * ```
 * val transport = AcpProcessTransport("kimi", listOf("acp"))
 * val client = AcpClient(transport)
 * client.initialize()
 * ```
 */
class AcpProcessTransport(
    command: String,
    args: List<String> = emptyList(),
    env: Map<String, String> = emptyMap(),
    workingDir: File? = null
) : AcpSessionTransport {

    private val logger = Logger.getLogger<AcpProcessTransport>()
    private val process: Process
    private val transport: StdioAcpSessionTransport
    private val closed = AtomicBoolean(false)

    init {
        val commandParts = listOf(command) + args
        val builder = ProcessBuilder(commandParts)
            .directory(workingDir)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        builder.environment().putAll(env)
        process = builder.start()
        transport = StdioAcpSessionTransport(
            input = process.inputStream,
            output = process.outputStream,
            name = command
        )
        logger.info("Started ACP agent process: ${commandParts.joinToString(" ")}")
    }

    override val isOpen: Boolean
        get() = !closed.get() && transport.isOpen && process.isAlive

    override suspend fun readLine(): String? = transport.readLine()

    override suspend fun writeLine(message: String) = transport.writeLine(message)

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            transport.close()
            withContext(Dispatchers.IO) {
                if (process.isAlive) {
                    process.destroy()
                    try {
                        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            process.destroyForcibly()
                        }
                    } catch (e: Exception) {
                        process.destroyForcibly()
                    }
                }
            }
            logger.info("ACP agent process terminated")
        }
    }
}
