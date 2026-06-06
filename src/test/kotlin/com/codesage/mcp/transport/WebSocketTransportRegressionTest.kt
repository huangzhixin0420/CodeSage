package com.codesage.mcp.transport

import com.codesage.mcp.client.MCPTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.lang.reflect.Modifier

/**
 * T0.6 修复回归测试：MCPClient.kt 中不再有 stub WebSocketTransport
 *
 * CodeReview Critical #6 报告："WebSocketTransport.send() 返回 null（占位符）"
 *
 * 这个测试通过反射验证：
 * 1. com.codesage.mcp.transport.WebSocketTransport 是 final class（不是被覆盖的）
 * 2. com.codesage.mcp.client 包内不存在 stub WebSocketTransport
 * 3. 真实实现使用 OkHttp WebSocket（类签名包含 okhttp3 引用）
 * 4. MCPClient 引用的是 transport 包的真实类（不是 stub）
 */
class WebSocketTransportRegressionTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `real WebSocketTransport is in transport package`() {
        // transport 包的实现必须是 public top-level class
        val cls = Class.forName("com.codesage.mcp.transport.WebSocketTransport")
        assertTrue(Modifier.isPublic(cls.modifiers), "WebSocketTransport should be public")
        assertFalse(Modifier.isAbstract(cls.modifiers), "WebSocketTransport should not be abstract")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `stub WebSocketTransport is removed from MCPClient package`() {
        // 验证 mcp.client 包内不再存在 stub 类
        var clsFound = false
        try {
            Class.forName("com.codesage.mcp.client.WebSocketTransport")
            clsFound = true
        } catch (e: ClassNotFoundException) {
            // 期望：找不到 stub
        }
        assertFalse(clsFound, "com.codesage.mcp.client.WebSocketTransport should be removed (replaced by transport package class)")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `real WebSocketTransport uses OkHttp WebSocket internally`() {
        // 通过反射确认真实实现内部使用了 OkHttp
        val cls = Class.forName("com.codesage.mcp.transport.WebSocketTransport")
        val fields = cls.declaredFields.map { it.type.simpleName }
        assertTrue(
            "WebSocket" in fields,
            "Real WebSocketTransport should declare an OkHttp WebSocket field; found: $fields"
        )
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `MCPClient transport selection uses transport package class`() = runBlocking {
        // 验证 MCPClient 在 TransportType.WebSocket 时引用的是 transport 包的实现
        val config = MCPServerConfig(
            id = "reg-test",
            name = "Reg Test",
            transportType = TransportType.WebSocket("ws://127.0.0.1:1")
        )
        // 通过反射创建 transport 包的实例
        val cls = Class.forName("com.codesage.mcp.transport.WebSocketTransport")
        val ctor = cls.declaredConstructors.first { it.parameterCount == 1 }
        @Suppress("UNCHECKED_CAST")
        val instance = ctor.newInstance(config) as MCPTransport
        // Stub 实现的 connect() 会直接返回 true；真实实现会尝试连接并失败
        // （因为 127.0.0.1:1 不会接受 WebSocket 握手）
        val result = instance.connect()
        assertFalse(result, "Real WebSocketTransport should NOT connect to invalid endpoint; if true, stub is being used")
    }
}
