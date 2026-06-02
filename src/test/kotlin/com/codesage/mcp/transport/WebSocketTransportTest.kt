package com.codesage.mcp.transport

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T2.1 修复验证测试：WebSocket Transport 真实实现
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T2.1）：
 * - [x] 单元测试：与 echo server 互发 100 条消息零丢失
 * - [x] 服务器主动 close 时，client 正确清理
 * - [ ] 网络断开后重连（T2.2 负责，本测试只验证断开检测）
 */
class WebSocketTransportTest {

    private lateinit var echoServer: MockWebServer
    private lateinit var transport: WebSocketTransport

    @BeforeEach
    fun setUp() {
        echoServer = MockWebServer()
        echoServer.start()
    }

    @AfterEach
    fun tearDown() {
        // 1. 主动断开 transport，发送 close 帧
        runBlocking { transport.disconnect() }
        // 2. 给 OkHttp 一点时间传播 close 到 server
        Thread.sleep(200)
        // 3. 关闭 server
        echoServer.shutdown()
    }

    /**
     * 启动一个 WebSocket echo server，接收每条消息后回显同样的内容。
     * 监听收到的消息计数，用于测试验证。
     */
    private fun startEchoServer(receivedCount: AtomicInteger, closeAfter: Int = -1): CountDownLatch {
        val ready = CountDownLatch(1)
        echoServer.enqueue(
            MockResponse()
                .withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        ready.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedCount.incrementAndGet()
                        // Echo back
                        webSocket.send(text)
                        if (closeAfter > 0 && receivedCount.get() >= closeAfter) {
                            webSocket.close(1000, "echo server done")
                        }
                    }
                })
        )
        return ready
    }

    private fun newConfig(): MCPServerConfig = MCPServerConfig(
        id = "test_ws",
        name = "Test WebSocket",
        transportType = TransportType.WebSocket(url = echoServer.url("/ws").toString())
    )

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `connect to a websocket echo server succeeds`() = runBlocking {
        startEchoServer(AtomicInteger(0))
        transport = WebSocketTransport(newConfig())

        val connected = transport.connect()
        assertTrue(connected, "Should connect successfully")
        assertTrue(transport.isConnected(), "isConnected should be true after connect()")
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `100 messages round-trip with no loss`() = runBlocking {
        val receivedCount = AtomicInteger(0)
        startEchoServer(receivedCount)
        transport = WebSocketTransport(newConfig())

        val connected = transport.connect()
        assertTrue(connected)
        assertTrue(transport.isConnected())

        val messageCount = 100
        val successCount = AtomicInteger(0)

        // 串行发送 100 条消息（保证顺序匹配）
        for (i in 1..messageCount) {
            val sent = """{"id":$i,"method":"echo","params":{"text":"msg_$i"}}"""
            val response = transport.send(sent)
            if (response != null) {
                successCount.incrementAndGet()
                // 验证 echo 完整
                assertTrue(response.contains("\"text\":\"msg_$i\""), "Response should echo msg_$i: $response")
            }
        }

        assertEquals(messageCount, successCount.get(), "All 100 messages should get responses")
        assertEquals(messageCount, receivedCount.get(), "Server should have received 100 messages")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `server initiated close is properly detected`() = runBlocking {
        // 配置 server 在收到 1 条消息后主动关闭
        val receivedCount = AtomicInteger(0)
        startEchoServer(receivedCount, closeAfter = 1)
        transport = WebSocketTransport(newConfig())

        assertTrue(transport.connect())
        assertTrue(transport.isConnected())

        // 发送 1 条消息，server 会在 echo 后 close
        val response = transport.send("""{"test":1}""")
        assertNotNull(response)

        // 等待 server 关闭事件传播
        delay(500)

        // isConnected 应在 onClosed 后变 false
        // （可能 OkHttp 还来不及处理 close；给最多 2 秒）
        withTimeout(2_000) {
            while (transport.isConnected()) {
                delay(50)
            }
        }
        assertFalse(transport.isConnected(), "isConnected should be false after server close")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `send before connect returns null`() = runBlocking {
        transport = WebSocketTransport(newConfig())
        // 故意不调用 connect()
        val response = transport.send("""{"test":1}""")
        assertNull(response, "send should return null when not connected")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `disconnect sets isConnected to false`() = runBlocking {
        startEchoServer(AtomicInteger(0))
        transport = WebSocketTransport(newConfig())
        assertTrue(transport.connect())
        assertTrue(transport.isConnected())

        transport.disconnect()
        assertFalse(transport.isConnected())
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `connect to invalid url fails`() = runBlocking {
        val badConfig = MCPServerConfig(
            id = "bad",
            name = "Bad WS",
            transportType = TransportType.WebSocket(url = "ws://127.0.0.1:1/ws")  // 无服务
        )
        transport = WebSocketTransport(badConfig)

        val connected = transport.connect()
        assertFalse(connected, "connect to invalid URL should return false")
        assertFalse(transport.isConnected())
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `connect with non-WebSocket transport type fails gracefully`() = runBlocking {
        // 用错误的 transport type 构造
        val badConfig = MCPServerConfig(
            id = "bad",
            name = "Bad config",
            transportType = TransportType.StdIO(command = "echo", args = listOf("hello"))
        )
        transport = WebSocketTransport(badConfig)

        val connected = transport.connect()
        assertFalse(connected, "connect with wrong transport type should return false")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `disconnect is idempotent`() = runBlocking {
        transport = WebSocketTransport(newConfig())
        // 没 connect 就 disconnect
        transport.disconnect()  // 不抛异常
        transport.disconnect()  // 仍然不抛异常
    }
}
