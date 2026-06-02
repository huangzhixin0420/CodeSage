package com.codesage.observability

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 执行追踪器
 * 记录Agent执行的完整调用链，支持分布式追踪风格
 */
class ExecutionTracer {

    private val activeTraces = ConcurrentHashMap<String, Trace>()
    private val traceHistory = CopyOnWriteArrayList<Trace>()
    private val maxHistory = 100

    /**
     * 开始新追踪
     */
    fun startTrace(
        name: String,
        sessionId: String? = null,
        parentTraceId: String? = null
    ): TraceContext {
        val traceId = generateTraceId()
        val trace = Trace(
            id = traceId,
            name = name,
            sessionId = sessionId,
            parentTraceId = parentTraceId,
            startTime = System.currentTimeMillis(),
            rootSpan = Span(
                id = generateSpanId(),
                name = name,
                startTime = System.currentTimeMillis()
            )
        )
        activeTraces[traceId] = trace
        return TraceContext(traceId, trace.rootSpan.id, this)
    }

    /**
     * 结束追踪
     */
    fun endTrace(traceId: String, status: TraceStatus = TraceStatus.OK) {
        val trace = activeTraces.remove(traceId) ?: return
        val endTime = System.currentTimeMillis()
        val completed = trace.copy(
            endTime = endTime,
            durationMs = endTime - trace.startTime,
            status = status
        )
        traceHistory.add(completed)
        if (traceHistory.size > maxHistory) {
            traceHistory.removeAt(0)
        }
    }

    /**
     * 添加Span到追踪
     */
    fun addSpan(
        traceId: String,
        parentSpanId: String? = null,
        name: String,
        attributes: Map<String, String> = emptyMap()
    ): String {
        val trace = activeTraces[traceId] ?: return ""
        val spanId = generateSpanId()
        val span = Span(
            id = spanId,
            name = name,
            startTime = System.currentTimeMillis(),
            parentSpanId = parentSpanId,
            attributes = attributes
        )
        trace.spans.add(span)
        return spanId
    }

    /**
     * 结束Span
     */
    fun endSpan(traceId: String, spanId: String, status: TraceStatus = TraceStatus.OK) {
        val trace = activeTraces[traceId] ?: return
        val span = trace.spans.find { it.id == spanId } ?: return
        val endTime = System.currentTimeMillis()
        val index = trace.spans.indexOf(span)
        trace.spans[index] = span.copy(
            endTime = endTime,
            durationMs = endTime - span.startTime,
            status = status
        )
    }

    /**
     * 添加事件到Span
     */
    fun addEvent(traceId: String, spanId: String, eventName: String, attributes: Map<String, String> = emptyMap()) {
        val trace = activeTraces[traceId] ?: return
        val span = trace.spans.find { it.id == spanId } ?: trace.rootSpan
        val event = TraceEvent(
            name = eventName,
            timestamp = System.currentTimeMillis(),
            attributes = attributes
        )
        span.events.add(event)
    }

    /**
     * 获取活跃追踪
     */
    fun getActiveTrace(traceId: String): Trace? = activeTraces[traceId]

    /**
     * 获取所有活跃追踪的 ID 列表
     */
    fun listActiveTraceIds(): List<String> = activeTraces.keys.toList()

    /**
     * 获取追踪历史
     */
    fun getTraceHistory(limit: Int = 50): List<Trace> {
        return traceHistory.takeLast(limit)
    }

    /**
     * 获取追踪树（用于可视化）
     */
    fun getTraceTree(traceId: String): TraceTree? {
        val trace = activeTraces[traceId] ?: traceHistory.find { it.id == traceId } ?: return null
        return buildTraceTree(trace)
    }

    private fun buildTraceTree(trace: Trace): TraceTree {
        val spanMap = (listOf(trace.rootSpan) + trace.spans).associateBy { it.id }
        val childrenMap = spanMap.values.groupBy { it.parentSpanId }

        fun buildNode(spanId: String): TraceTree.Node {
            val span = spanMap[spanId]!!
            val children = childrenMap[spanId]?.map { buildNode(it.id) } ?: emptyList()
            return TraceTree.Node(
                span = span,
                children = children
            )
        }

        return TraceTree(
            trace = trace,
            root = buildNode(trace.rootSpan.id)
        )
    }

    private fun generateTraceId(): String = "trace_${UUID.randomUUID().toString().take(16)}"
    private fun generateSpanId(): String = "span_${UUID.randomUUID().toString().take(8)}"

    // === 数据模型 ===

    data class Trace(
        val id: String,
        val name: String,
        val sessionId: String?,
        val parentTraceId: String?,
        val startTime: Long,
        val endTime: Long? = null,
        val durationMs: Long? = null,
        val status: TraceStatus = TraceStatus.OK,
        val rootSpan: Span,
        val spans: MutableList<Span> = mutableListOf()
    )

    data class Span(
        val id: String,
        val name: String,
        val startTime: Long,
        val endTime: Long? = null,
        val durationMs: Long? = null,
        val parentSpanId: String? = null,
        val status: TraceStatus = TraceStatus.OK,
        val attributes: Map<String, String> = emptyMap(),
        val events: MutableList<TraceEvent> = mutableListOf()
    )

    data class TraceEvent(
        val name: String,
        val timestamp: Long,
        val attributes: Map<String, String> = emptyMap()
    )

    enum class TraceStatus {
        OK, ERROR, CANCELLED
    }

    data class TraceTree(
        val trace: Trace,
        val root: Node
    ) {
        data class Node(
            val span: Span,
            val children: List<Node> = emptyList()
        )
    }

    /**
     * 追踪上下文，用于在代码中传递追踪信息
     */
    class TraceContext(
        val traceId: String,
        val currentSpanId: String,
        private val tracer: ExecutionTracer
    ) {
        fun childSpan(name: String, attributes: Map<String, String> = emptyMap()): String {
            return tracer.addSpan(traceId, currentSpanId, name, attributes)
        }

        fun endChildSpan(spanId: String, status: TraceStatus = TraceStatus.OK) {
            tracer.endSpan(traceId, spanId, status)
        }

        fun event(name: String, attributes: Map<String, String> = emptyMap()) {
            tracer.addEvent(traceId, currentSpanId, name, attributes)
        }

        fun end(status: TraceStatus = TraceStatus.OK) {
            tracer.endSpan(traceId, currentSpanId, status)
            tracer.endTrace(traceId, status)
        }
    }
}
