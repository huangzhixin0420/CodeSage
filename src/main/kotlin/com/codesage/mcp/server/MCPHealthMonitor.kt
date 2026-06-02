package com.codesage.mcp.server

import com.codesage.mcp.transport.MCPServerStatus
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * T2.2 修复：MCP 健康监控 + 自动重连
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T2.2）：
 * - [x] 单元测试：杀死 server 进程后，监控器在 60s 内检测并尝试重连
 * - [x] 单元测试：连续 5 次重连失败后标记 FAILED，不再尝试
 * - [x] 测试恢复后自动转回 CONNECTED
 *
 * 设计要点：
 * - 状态机：CONNECTING → CONNECTED → UNHEALTHY → RECONNECTING → CONNECTED / FAILED
 * - 健康检查周期：60s（可配置）
 * - 重连退避：1s / 2s / 4s / 8s / 16s / 30s（指数退避 + 上限）
 * - 连续 5 次失败标记 FAILED，不再尝试（避免无限重连）
 * - 监控器是协程，start() 启动，stop() 停止
 */
class MCPHealthMonitor(
    private val serverManager: MCPServerManager,
    private val checkIntervalMs: Long = 60_000L,
    private val maxReconnectAttempts: Int = 5,
    private val baseReconnectDelayMs: Long = 1_000L,
    private val maxReconnectDelayMs: Long = 30_000L
) {
    private val logger = Logger.getLogger<MCPHealthMonitor>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitorJobs = ConcurrentHashMap<String, Job>()
    private val healthState = ConcurrentHashMap<String, MCPHealthState>()
    private val reconnectAttempts = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 健康状态机
     */
    enum class MCPHealthState {
        CONNECTING,    // 正在初次连接
        CONNECTED,     // 健康
        UNHEALTHY,     // 检测到不健康（ping 失败等）
        RECONNECTING,  // 正在尝试重连
        FAILED         // 超过最大重连次数
    }

    /**
     * 启动对所有 server 的健康监控。
     */
    fun start() {
        val statuses = serverManager.getAllServerStatuses()
        for (serverId in statuses.keys) {
            startMonitoring(serverId)
        }
        logger.info("Health monitor started for ${statuses.size} servers")
    }

    /**
     * 停止所有监控。
     */
    fun stop() {
        for ((serverId, job) in monitorJobs) {
            job.cancel()
            logger.debug("Stopped monitoring $serverId")
        }
        monitorJobs.clear()
        scope.cancel()
    }

    /**
     * 启动单个 server 的监控。
     */
    fun startMonitoring(serverId: String) {
        if (monitorJobs.containsKey(serverId)) return
        healthState[serverId] = MCPHealthState.CONNECTING
        val job = scope.launch {
            monitorLoop(serverId)
        }
        monitorJobs[serverId] = job
    }

    /**
     * 立即检查所有 server 状态（用于单元测试或外部触发）。
     */
    suspend fun checkNow() {
        for (serverId in healthState.keys.toList()) {
            performHealthCheck(serverId)
        }
    }

    /**
     * 获取 server 的当前健康状态。
     */
    fun getState(serverId: String): MCPHealthState = healthState[serverId] ?: MCPHealthState.CONNECTING

    private suspend fun monitorLoop(serverId: String) {
        // 初次连接
        performHealthCheck(serverId)
        // 周期性检查
        while (currentCoroutineContext().isActive) {
            delay(checkIntervalMs)
            if (!currentCoroutineContext().isActive) break
            performHealthCheck(serverId)
        }
    }

    private suspend fun performHealthCheck(serverId: String) {
        try {
            // 通过调用 listTools 验证 server 仍然响应
            val tools = serverManager.listTools(serverId)
            if (tools != null) {
                onServerHealthy(serverId)
            } else {
                onServerUnhealthy(serverId, "listTools returned null")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onServerUnhealthy(serverId, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun onServerHealthy(serverId: String) {
        val previous = healthState[serverId]
        if (previous != MCPHealthState.CONNECTED) {
            logger.info("Server $serverId is healthy (was: $previous)")
        }
        healthState[serverId] = MCPHealthState.CONNECTED
        // 重置重连计数
        reconnectAttempts[serverId]?.set(0)
    }

    private fun onServerUnhealthy(serverId: String, reason: String) {
        val previous = healthState[serverId]
        healthState[serverId] = MCPHealthState.UNHEALTHY
        logger.warn("Server $serverId is unhealthy: $reason (was: $previous)")

        // 触发重连（异步，不阻塞健康检查循环）
        if (previous != MCPHealthState.RECONNECTING && previous != MCPHealthState.FAILED) {
            scope.launch { attemptReconnect(serverId) }
        }
    }

    private suspend fun attemptReconnect(serverId: String) {
        healthState[serverId] = MCPHealthState.RECONNECTING
        val attempts = reconnectAttempts.computeIfAbsent(serverId) { AtomicInteger(0) }

        while (attempts.get() < maxReconnectAttempts) {
            val attemptNumber = attempts.incrementAndGet()
            val delayMs = calculateBackoff(attemptNumber)

            logger.info("Reconnecting to $serverId (attempt $attemptNumber/$maxReconnectAttempts) in ${delayMs}ms")
            delay(delayMs)

            if (!currentCoroutineContext().isActive) return

            // 尝试重连
            try {
                val newStatus = serverManager.addServer(serverManager.serverConfigOf(serverId) ?: return)
                if (newStatus == MCPServerStatus.CONNECTED) {
                    logger.info("Server $serverId reconnected successfully")
                    onServerHealthy(serverId)
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("Reconnect attempt $attemptNumber failed: ${e.message}")
            }
        }

        // 达到最大重连次数
        logger.error("Server $serverId failed to reconnect after $maxReconnectAttempts attempts, marking FAILED")
        healthState[serverId] = MCPHealthState.FAILED
    }

    /**
     * 指数退避 + jitter
     */
    private fun calculateBackoff(attempt: Int): Long {
        // attempt 1 → 1 × base
        // attempt 2 → 2 × base
        // attempt 3 → 4 × base
        // attempt N → 2^(N-1) × base, capped at maxReconnectDelayMs
        val safeAttempt = (attempt - 1).coerceAtLeast(0).coerceAtMost(30)  // 防止 1L shl 31 溢出
        val exponential = baseReconnectDelayMs * (1L shl safeAttempt)
        val capped = exponential.coerceAtMost(maxReconnectDelayMs)
        // ±30% jitter
        val jitter = (Math.random() * 0.3 * capped).toLong()
        return capped + jitter
    }
}
