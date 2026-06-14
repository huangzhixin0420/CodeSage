package com.codesage.acp.transport

import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于标准输入输出的 ACP 会话传输层
 *
 * 用于：
 * - ACP 客户端连接外部 agent 子进程（读取子进程 stdout / 写入 stdin）
 * - 测试环境中以内存流模拟 stdio
 *
 * 协议约定：每行一条 JSON-RPC 消息，以换行符（\n 或 \r\n）分隔。
 */
class StdioAcpSessionTransport(
    input: InputStream,
    output: OutputStream,
    private val name: String = "stdio"
) : AcpSessionTransport {

    private val logger = Logger.getLogger(StdioAcpSessionTransport::class.java)
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
    private val closed = AtomicBoolean(false)

    override val isOpen: Boolean
        get() = !closed.get()

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        try {
            reader.readLine()
        } catch (e: Exception) {
            if (!closed.get()) {
                logger.debug("ACP stdio transport '$name' read error: ${e.message}")
                closed.set(true)
            }
            null
        }
    }

    override suspend fun writeLine(message: String) = withContext(Dispatchers.IO) {
        try {
            writer.write(message)
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            if (!closed.get()) {
                logger.warn("ACP stdio transport '$name' write error: ${e.message}")
                closed.set(true)
            }
        }
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) {
                try {
                    reader.close()
                } catch (e: Exception) {
                    logger.debug("Error closing ACP stdio reader: ${e.message}")
                }
                try {
                    writer.close()
                } catch (e: Exception) {
                    logger.debug("Error closing ACP stdio writer: ${e.message}")
                }
            }
        }
    }
}
