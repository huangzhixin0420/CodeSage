package com.codesage.acp.transport

import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 TCP Socket 的 ACP 会话传输层
 */
class SocketAcpSessionTransport(
    private val socket: Socket
) : AcpSessionTransport {

    private val logger = Logger.getLogger(SocketAcpSessionTransport::class.java)
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    private val closed = AtomicBoolean(false)

    override val isOpen: Boolean
        get() = !closed.get() && !socket.isClosed && socket.isConnected

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        try {
            reader.readLine()
        } catch (e: Exception) {
            if (!closed.get()) {
                logger.debug("ACP socket transport read error: ${e.message}")
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
                logger.warn("ACP socket transport write error: ${e.message}")
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
                    logger.debug("Error closing ACP socket reader: ${e.message}")
                }
                try {
                    writer.close()
                } catch (e: Exception) {
                    logger.debug("Error closing ACP socket writer: ${e.message}")
                }
                try {
                    socket.close()
                } catch (e: Exception) {
                    logger.debug("Error closing ACP socket: ${e.message}")
                }
            }
        }
    }
}
