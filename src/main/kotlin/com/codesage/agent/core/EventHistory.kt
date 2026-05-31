package com.codesage.agent.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 事件历史记录器
 * - 线程安全
 * - 内存限制：保留最近 MAX_EVENTS 条（默认 1000）
 * - 支持分页查询、按类型过滤、JSON 导出
 */
class EventHistory(private val maxEvents: Int = MAX_EVENTS) {

    private val events = ConcurrentLinkedDeque<HistoryEntry>()
    private val eventCounter = AtomicLong(0)

    @Serializable
    data class HistoryEntry(
        val seq: Long,
        val timestamp: Long,
        val eventType: String,
        val sessionId: String? = null,
        val payload: String? = null
    )

    /**
     * 记录一个事件
     */
    fun record(event: AgentStreamEvent, sessionId: String? = null) {
        val entry = HistoryEntry(
            seq = eventCounter.incrementAndGet(),
            timestamp = Instant.now().epochSecond,
            eventType = event::class.simpleName ?: "Unknown",
            sessionId = sessionId,
            payload = serializePayload(event)
        )
        events.addLast(entry)
        trimIfNeeded()
    }

    /**
     * 分页查询事件历史
     */
    fun query(
        offset: Int = 0,
        limit: Int = 100,
        eventType: String? = null,
        sessionId: String? = null
    ): List<HistoryEntry> {
        val filtered = events.filter {
            (eventType == null || it.eventType == eventType) &&
                    (sessionId == null || it.sessionId == sessionId)
        }
        return filtered.drop(offset).take(limit)
    }

    /**
     * 按类型过滤查询
     */
    fun queryByType(eventType: String, limit: Int = 100): List<HistoryEntry> {
        return events.filter { it.eventType == eventType }.takeLast(limit)
    }

    /**
     * 获取总事件数
     */
    fun size(): Int = events.size

    /**
     * 导出为 JSON 字符串
     */
    fun exportToJson(): String {
        return Json.encodeToString(events.toList())
    }

    /**
     * 清空历史
     */
    fun clear() {
        events.clear()
        eventCounter.set(0)
    }

    private fun trimIfNeeded() {
        while (events.size > maxEvents) {
            events.pollFirst()
        }
    }

    private fun serializePayload(event: AgentStreamEvent): String? {
        return when (event) {
            is AgentStreamEvent.TextDelta -> event.delta
            is AgentStreamEvent.Thinking -> event.message
            is AgentStreamEvent.Error -> event.message
            is AgentStreamEvent.ToolCallResult -> "${event.toolName}:${event.success}"
            is AgentStreamEvent.BudgetStatus -> event.status
            else -> null
        }
    }

    companion object {
        const val MAX_EVENTS = 1000
    }
}
