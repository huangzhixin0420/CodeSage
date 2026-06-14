package com.codesage.acp.server

import com.codesage.acp.transport.SocketAcpSessionTransport
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 TCP Socket 的 ACP 服务端
 *
 * 监听指定端口，每个连接创建独立的 [AcpServer] 会话。
 * 适合把 CodeSage 插件作为常驻 ACP 后端暴露给本地其它客户端。
 */
class AcpSocketServer(
    private val sessionFactory: () -> AcpServer,
    private val port: Int = 0
) {
    private val logger = Logger.getLogger<AcpSocketServer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    val actualPort: Int
        get() = serverSocket?.localPort ?: -1

    /**
     * 启动 TCP 监听。
     * 若 [port] 为 0，则自动分配可用端口。
     */
    fun start() {
        if (running.compareAndSet(false, true)) {
            scope.launch {
                try {
                    val socket = ServerSocket(port)
                    serverSocket = socket
                    logger.info("ACP socket server listening on port ${socket.localPort}")
                    while (isActive && running.get() && !socket.isClosed) {
                        try {
                            val client = socket.accept()
                            launch {
                                logger.info("ACP client connected: ${client.inetAddress}")
                                val transport = SocketAcpSessionTransport(client)
                                sessionFactory().handleSession(transport)
                            }
                        } catch (e: Exception) {
                            if (running.get()) {
                                logger.warn("ACP socket accept error: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("ACP socket server failed to start on port $port", e)
                } finally {
                    running.set(false)
                }
            }
        }
    }

    /**
     * 停止监听并关闭所有正在处理的连接。
     */
    fun stop() {
        if (running.compareAndSet(true, false)) {
            try {
                serverSocket?.close()
            } catch (e: Exception) {
                logger.debug("Error closing ACP server socket: ${e.message}")
            }
            scope.cancel()
            logger.info("ACP socket server stopped")
        }
    }
}
