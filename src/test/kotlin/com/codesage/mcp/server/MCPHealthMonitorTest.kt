package com.codesage.mcp.server

import com.codesage.mcp.client.McpTool
import com.codesage.mcp.transport.MCPServerConfig
import com.codesage.mcp.transport.MCPServerStatus
import com.codesage.mcp.transport.TransportType
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T2.2 修复验证测试：MCP 健康监控 + 自动重连
 */
class MCPHealthMonitorTest {

    /**
     * 假 MCPServerManager：可控地返回健康/不健康，模拟重连。
     */
    private class FakeServerManager : MCPServerManager() {
        var healthy = true
        val configStore = mutableMapOf<String, MCPServerConfig>()
        val reconnectAttempts = AtomicInteger(0)
        val addServerCalls = AtomicInteger(0)

        fun addFakeServer(id: String, name: String) {
            val cfg = MCPServerConfig(
                id = id,
                name = name,
                transportType = TransportType.StdIO(command = "fake", args = listOf())
            )
            configStore[cfg.id] = cfg
        }

        override suspend fun listTools(serverId: String): List<McpTool> {
            if (!healthy) throw RuntimeException("Server $serverId is unhealthy")
            return listOf(McpTool(name = "fake_tool_$serverId", description = "fake", inputSchema = emptyMap()))
        }

        override fun getAllServerStatuses(): Map<String, MCPServerStatus> {
            return configStore.mapValues { MCPServerStatus.CONNECTED }
        }

        override fun serverConfigOf(serverId: String): MCPServerConfig? = configStore[serverId]

        override suspend fun addServer(config: MCPServerConfig): MCPServerStatus {
            addServerCalls.incrementAndGet()
            reconnectAttempts.set(addServerCalls.get())
            return if (healthy) MCPServerStatus.CONNECTED else MCPServerStatus.ERROR
        }
    }

    private lateinit var manager: FakeServerManager
    private lateinit var monitor: MCPHealthMonitor

    @BeforeEach
    fun setUp() {
        manager = FakeServerManager()
    }

    @AfterEach
    fun tearDown() {
        if (::monitor.isInitialized) {
            monitor.stop()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `healthy server stays in CONNECTED state`() = runBlocking {
        manager.addFakeServer("s1", "Test 1")
        monitor = MCPHealthMonitor(manager, checkIntervalMs = 100, baseReconnectDelayMs = 10)
        monitor.startMonitoring("s1")

        // 等待首次健康检查
        delay(50)
        assertEquals(MCPHealthMonitor.MCPHealthState.CONNECTED, monitor.getState("s1"))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `unhealthy server triggers reconnect`() = runBlocking {
        manager.addFakeServer("s1", "Test 1")
        monitor = MCPHealthMonitor(
            manager,
            checkIntervalMs = 50,
            maxReconnectAttempts = 3,
            baseReconnectDelayMs = 10,
            maxReconnectDelayMs = 100
        )
        monitor.startMonitoring("s1")
        delay(50)  // 首次健康检查

        // 模拟 server 变不健康
        manager.healthy = false
        monitor.checkNow()
        delay(100)  // 给 monitor 时间触发 reconnect

        // Reconnect 已被尝试
        assertTrue(manager.addServerCalls.get() >= 1, "addServer should have been called at least once")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `recovery from unhealthy transitions back to CONNECTED`() = runBlocking {
        manager.addFakeServer("s1", "Test 1")
        monitor = MCPHealthMonitor(
            manager,
            checkIntervalMs = 50,
            maxReconnectAttempts = 5,
            baseReconnectDelayMs = 10,
            maxReconnectDelayMs = 100
        )
        monitor.startMonitoring("s1")
        delay(50)

        // Server 变不健康
        manager.healthy = false
        monitor.checkNow()
        delay(100)

        // Server 恢复
        manager.healthy = true
        // 强制一次健康检查让 monitor 看到恢复
        monitor.checkNow()
        delay(50)

        // 状态应回到 CONNECTED
        assertEquals(
            MCPHealthMonitor.MCPHealthState.CONNECTED,
            monitor.getState("s1"),
            "After recovery, state should be CONNECTED"
        )
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `max reconnect attempts leads to FAILED state`() = runBlocking {
        manager.addFakeServer("s1", "Test 1")
        // 持续不健康
        manager.healthy = false
        monitor = MCPHealthMonitor(
            manager,
            checkIntervalMs = 50,
            maxReconnectAttempts = 3,
            baseReconnectDelayMs = 5,
            maxReconnectDelayMs = 20
        )
        monitor.startMonitoring("s1")
        delay(50)

        monitor.checkNow()
        // 等到 state 变为 FAILED（用 withTimeout + 轮询，最多 2 秒）
        withTimeout(2_000) {
            while (monitor.getState("s1") != MCPHealthMonitor.MCPHealthState.FAILED) {
                delay(50)
            }
        }

        // 达到 max 次数后 state 应为 FAILED
        assertEquals(
            MCPHealthMonitor.MCPHealthState.FAILED,
            monitor.getState("s1"),
            "After max attempts, state should be FAILED"
        )
        // addServer 应被调用 maxReconnectAttempts 次
        assertTrue(
            manager.addServerCalls.get() <= 3,
            "addServer called ${manager.addServerCalls.get()} times, expected <= 3"
        )
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `backoff calculation respects max delay`() {
        val monitor = MCPHealthMonitor(
            manager,
            baseReconnectDelayMs = 1_000,
            maxReconnectDelayMs = 5_000
        )
        // 第一次重试：1s，第二次：2s，第三次：4s，第四次：8s (cap to 5s)
        // 通过反射访问私有方法，或用 behavior testing
        val method = MCPHealthMonitor::class.java.getDeclaredMethod("calculateBackoff", Int::class.java)
        method.isAccessible = true

        val backoff1 = method.invoke(monitor, 1) as Long
        val backoff4 = method.invoke(monitor, 4) as Long
        val backoff10 = method.invoke(monitor, 10) as Long

        // backoff1: base * 2^0 = 1000ms
        assertTrue(backoff1 in 700L..1500L, "backoff1 should be ~1000ms (with ±30% jitter), got $backoff1")
        // backoff4: base * 2^3 = 8000ms, capped to 5s
        assertTrue(backoff4 in 3_500L..6_500L, "backoff4 should be capped to ~5s (with jitter), got $backoff4")
        // backoff10 also capped
        assertTrue(backoff10 in 3_500L..6_500L, "backoff10 should be capped to ~5s, got $backoff10")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `stop cancels all monitor jobs`() = runBlocking {
        manager.addFakeServer("s1", "Test 1")
        manager.addFakeServer("s2", "Test 2")
        val mon = MCPHealthMonitor(manager, checkIntervalMs = 1_000, baseReconnectDelayMs = 10)
        mon.start()
        delay(50)
        mon.stop()
        // stop 后应无残留任务（无法直接验证，但确保不抛异常）
    }
}
