package com.codesage.mcp.transport

import com.codesage.mcp.client.MCPTransport
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * T2.1 修复：WebSocket 传输实现
 *
 * 原实现是空壳：`connect()` 直接 `connected = true`，`send()` 返回 null。
 * 现用 OkHttp WebSocket + Kotlin 协程桥接实现完整的 RFC 6455 客户端。
 *
 * **设计要点**：
 * - OkHttp WebSocket 是异步 listener 模式；用 [CountDownLatch] 桥接到 suspend 函数
 * - 后台 read 协程把消息推入 Channel；`send()` 从 Channel 取出下一条 message
 * - OkHttp 自动处理 ping/pong 帧（pingInterval 30s）
 * - 关闭时发送 close frame（code 1000 = normal closure）
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T2.1）：
 * - [ ] 单元测试：与 echo server 互发 100 条消息零丢失
 * - [ ] 服务器主动 close 时，client 正确清理
 * - [ ] 网络断开后重连（T2.2 负责）
 */
class WebSocketTransport(
    private val config: MCPServerConfig
) : MCPTransport {

    private val logger = Logger.getLogger<WebSocketTransport>()

    /**
     * OkHttp 客户端（lazy 初始化）
     * - pingInterval 30s：自动 ping/pong 心跳
     * - 短超时：快速失败
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // 0 = 无超时（长连接）
            .build()
    }

    /**
     * 当前 WebSocket 实例（null 表示未连接）
     */
    @Volatile
    private var webSocket: WebSocket? = null

    /**
     * 异步消息通道：listener 写入，send() 读取
     * Channel.BUFFERED 提供背压缓冲
     */
    private val messageChannel = Channel<String>(capacity = Channel.BUFFERED)

    /**
     * 连接状态：原子引用避免多线程 race
     */
    @Volatile
    private var _connected = false

    override fun isConnected(): Boolean = _connected

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (_connected) {
            logger.warn("[${config.name}] Already connected")
            return@withContext true
        }

        val wsUrl = (config.transportType as? TransportType.WebSocket)?.url
            ?: run {
                logger.error("[${config.name}] Config does not have WebSocket transport type")
                return@withContext false
            }

        val openLatch = CountDownLatch(1)
        val failureRef = AtomicReference<Throwable?>(null)

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger.info("[${config.name}] WebSocket connected: $wsUrl")
                _connected = true
                openLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 收到的所有消息都推入 channel；send() 会取出"下一条"作为响应
                val result = messageChannel.trySend(text)
                if (result.isFailure) {
                    logger.warn("[${config.name}] Failed to enqueue message: $result")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                logger.info("[${config.name}] WebSocket closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logger.info("[${config.name}] WebSocket closed: $code $reason")
                _connected = false
                messageChannel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger.error("[${config.name}] WebSocket failure: ${t.message}", t)
                _connected = false
                failureRef.set(t)
                messageChannel.close(t)
                // 关键：即使失败也要 countDown，否则 await 永久阻塞
                openLatch.countDown()
            }
        }

        try {
            webSocket = client.newWebSocket(request, listener)
        } catch (e: Exception) {
            logger.error("[${config.name}] Failed to create WebSocket", e)
            return@withContext false
        }

        // 等待 onOpen 或 onFailure（带超时）
        val opened = openLatch.await(5, TimeUnit.SECONDS)
        if (!opened) {
            logger.error("[${config.name}] WebSocket connect timeout (5s)")
            return@withContext false
        }
        if (failureRef.get() != null) {
            logger.error("[${config.name}] WebSocket connect failed: ${failureRef.get()?.message}")
            return@withContext false
        }
        return@withContext true
    }

    override suspend fun disconnect() {
        if (!_connected) {
            return
        }
        try {
            // 先尝试正常关闭帧
            webSocket?.close(1000, "client disconnect")
        } catch (e: Exception) {
            logger.warn("[${config.name}] Error during close: ${e.message}")
        }
        // 立即 cancel 强制关闭（避免 OkHttp 连接池挂起）
        try {
            webSocket?.cancel()
        } catch (_: Exception) {
        }
        // 关键：shutdown OkHttpClient 的 executor 以释放所有连接
        // （注意：每个 transport 一个 client 实例，shutdown 不影响其它 transport）
        try {
            val dispatcher = client.dispatcher
            dispatcher.executorService.shutdown()
            dispatcher.executorService.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("[${config.name}] Error shutting down OkHttp dispatcher: ${e.message}")
        }
        webSocket = null
        _connected = false
        messageChannel.close()
    }

    /**
     * 发送一条消息并等待下一条响应。
     *
     * JSON-RPC 风格：每个 request 期望一个 response（按顺序匹配）。
     * 当前实现：send 后等下一条 message 作为响应，不做 ID 匹配（简化）。
     * 注意：如果 server 在我们 send 之前推 notification，notification 会被当作 response
     * 返回。生产实现应在 message 头解析 JSON-RPC id。
     */
    override suspend fun send(message: String): String? = withContext(Dispatchers.IO) {
        val ws = webSocket
        if (!_connected || ws == null) {
            logger.warn("[${config.name}] Cannot send: not connected")
            return@withContext null
        }

        // OkHttp.send() 返回 Boolean：true 表示已加入待发送队列
        val enqueued = try {
            ws.send(message)
        } catch (e: Exception) {
            logger.error("[${config.name}] send() threw: ${e.message}", e)
            return@withContext null
        }

        if (!enqueued) {
            logger.warn("[${config.name}] WebSocket send returned false (queue full or closing)")
            return@withContext null
        }

        // 等待响应（30s 超时）
        return@withContext try {
            withTimeout(30_000) {
                messageChannel.receive()
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("[${config.name}] send() response timeout (30s)")
            null
        } catch (e: Exception) {
            logger.warn("[${config.name}] send() response error: ${e.message}")
            null
        }
    }
}
