package com.codesage.observability

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.EventHistory
import com.codesage.shared.utils.Logger

/**
 * T7.3 修复：Observability 聚合服务
 *
 * **目标**：为 IDE 的 Observability 面板提供统一的数据查询入口。
 *
 * **聚合数据源**：
 * - [EventHistory]：最近 N 条事件（按 sessionId 过滤）
 * - [ExecutionTracer]：当前活跃 trace + 历史 trace 树
 * - [MetricsCollector]：计数器、计时器、仪表盘
 * - [StructuredLogger]：结构化日志（可选）
 *
 * **设计**：
 * - 单一 `snapshot()` 方法返回完整快照，前端一次拉取
 * - 支持按 sessionId 过滤（多会话切换时只显示当前会话）
 * - 事件 / trace 数量上限（避免面板加载过慢）
 */
class ObservabilityService(
    private val eventHistory: EventHistory,
    private val tracer: ExecutionTracer,
    private val metrics: MetricsCollector,
    private val config: Config = Config()
) {
    private val logger = Logger.getLogger<ObservabilityService>()

    /**
     * 获取完整快照
     */
    fun snapshot(sessionId: String? = null, recentEventLimit: Int = 100): ObservabilitySnapshot {
        val events = eventHistory.query(
            sessionId = sessionId,
            limit = recentEventLimit
        )
        val traces = tracer.getTraceHistory(limit = config.recentTraceLimit)
            .map { it.id }
        return ObservabilitySnapshot(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            events = events,
            recentTraceIds = traces,
            metrics = metrics.export()
        )
    }

    /**
     * 获取单个 trace 的完整树
     */
    fun getTraceTree(traceId: String): ExecutionTracer.TraceTree? {
        return tracer.getTraceTree(traceId)
    }

    /**
     * 活跃 trace（尚未关闭）
     */
    fun getActiveTraceIds(): List<String> {
        // 1. tracer 中显式活跃的 trace
        val activeIds = tracer.listActiveTraceIds().toMutableSet()
        // 2. 也包含已结束但最近 100 条中 endTime 为 null 的（防御性，理论上不会发生）
        activeIds.addAll(
            tracer.getTraceHistory(limit = 100)
                .filter { it.endTime == null }
                .map { it.id }
        )
        return activeIds.toList()
    }

    /**
     * 按类型分组的最近事件
     */
    fun getEventsByType(eventType: String, limit: Int = 50): List<EventHistory.HistoryEntry> {
        return eventHistory.queryByType(eventType, limit)
    }

    /**
     * 按 session 分组的最近事件
     */
    fun getEventsBySession(sessionId: String, limit: Int = 50): List<EventHistory.HistoryEntry> {
        return eventHistory.query(sessionId = sessionId, limit = limit)
    }

    /**
     * 面板摘要：用于顶部 KPI 卡片
     */
    fun summary(): ObservabilitySummary {
        val metrics = metrics.export()
        val eventCount = eventHistory.size()
        val traceCount = tracer.getTraceHistory(limit = 1000).size
        return ObservabilitySummary(
            totalEvents = eventCount,
            totalTraces = traceCount,
            totalCounters = metrics.counters.size,
            totalTimers = metrics.timers.size,
            uptimeMs = System.currentTimeMillis() - START_TIME_MS
        )
    }

    /**
     * 配置
     */
    data class Config(
        val recentTraceLimit: Int = 20
    )

    companion object {
        // 服务启动时间（用于计算 uptime）
        private val START_TIME_MS = System.currentTimeMillis()
    }
}

/**
 * 完整快照（前端一次拉取）
 */
data class ObservabilitySnapshot(
    val sessionId: String?,
    val timestamp: Long,
    val events: List<EventHistory.HistoryEntry>,
    val recentTraceIds: List<String>,
    val metrics: MetricsCollector.MetricsSnapshot
)

/**
 * 面板摘要
 */
data class ObservabilitySummary(
    val totalEvents: Int,
    val totalTraces: Int,
    val totalCounters: Int,
    val totalTimers: Int,
    val uptimeMs: Long
) {
    fun uptimeFormatted(): String {
        val totalSec = uptimeMs / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
