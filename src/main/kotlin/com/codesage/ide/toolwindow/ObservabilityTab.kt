package com.codesage.ide.toolwindow

import com.codesage.agent.core.EventHistory
import com.codesage.observability.MetricsCollector
import com.codesage.observability.ObservabilityService
import com.codesage.observability.ExecutionTracer
import com.codesage.shared.utils.Logger
import com.intellij.ui.jcef.JBCefBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * T7.3 修复：Observability 面板工具窗口
 *
 * **架构**：
 * - 单独的 JBCefBrowser 加载 `observability.html`
 * - 通过 javaBridge 双向通信：
 *   - Kotlin → JS: `observability.snapshot` (聚合数据)
 *   - JS → Kotlin: `observability.refresh` (请求新数据)
 *
 * **职责**：
 * - 显示当前会话的事件历史
 * - 显示 trace 树（工具调用嵌套关系）
 * - 显示指标（counters / timers）
 * - 支持按 sessionId 过滤
 */
class ObservabilityTab(
    private val eventHistory: EventHistory,
    private val tracer: ExecutionTracer,
    private val metrics: MetricsCollector
) : JPanel(BorderLayout()) {

    private val logger = Logger.getLogger<ObservabilityTab>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val observabilityService = ObservabilityService(eventHistory, tracer, metrics)

    private var browser: JBCefBrowser? = null

    init {
        try {
            val b = JBCefBrowser()
            b.loadHTML(loadHtml())
            browser = b
            add(b.component, BorderLayout.CENTER)
            logger.info("ObservabilityTab initialized")
        } catch (e: Exception) {
            logger.error("Failed to initialize ObservabilityTab", e)
            // Fallback: 添加错误占位符
            add(
                javax.swing.JLabel(
                    "<html><center>Observability panel requires JCEF.<br/>" +
                            "Error: ${e.message?.replace("<", "&lt;")}</center></html>"
                ),
                BorderLayout.CENTER
            )
        }
    }

    /**
     * 加载 observability.html 资源
     */
    private fun loadHtml(): String {
        val stream = javaClass.classLoader.getResourceAsStream("webui/observability.html")
            ?: return """<html><body><h1>Observability</h1><p>observability.html not found</p></body></html>"""
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * 处理来自 JS 的消息
     */
    fun handleJSMessage(message: String) {
        try {
            val json = Json.parseToJsonElement(message)
            val type = json.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: return
            when (type) {
                "observability.refresh" -> {
                    val sessionId = json.jsonObject["sessionId"]?.jsonPrimitive?.contentOrNull
                    refreshSnapshot(sessionId)
                }

                "observability.getTrace" -> {
                    val traceId = json.jsonObject["traceId"]?.jsonPrimitive?.contentOrNull ?: return
                    sendTraceTree(traceId)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to handle JS message: $message", e)
        }
    }

    /**
     * 刷新快照
     */
    private fun refreshSnapshot(sessionId: String?) {
        scope.launch {
            try {
                val snapshot = observabilityService.snapshot(sessionId)
                val summary = observabilityService.summary()
                // 手动构建 JSON 避免 @Serializable 要求
                val payload = buildJsonObject {
                    put("sessionId", snapshot.sessionId ?: "")
                    put("timestamp", snapshot.timestamp)
                    put(
                        "events", json.encodeToJsonElement(
                            ListSerializer(EventHistory.HistoryEntry.serializer()),
                            snapshot.events
                        )
                    )
                    put(
                        "recentTraceIds", json.encodeToJsonElement(
                            ListSerializer(String.serializer()),
                            snapshot.recentTraceIds
                        )
                    )
                    put("metrics", buildMetricsJson(snapshot.metrics))
                    put("summary", buildSummaryJson(summary))
                }
                sendToJS(
                    mapOf(
                        "type" to "observability.snapshot",
                        "payload" to payload.toString()
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to refresh snapshot", e)
            }
        }
    }

    private fun buildMetricsJson(snap: MetricsCollector.MetricsSnapshot): JsonObject = buildJsonObject {
        put("timestamp", snap.timestamp)
        // counters
        val countersObj = kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in snap.counters) put(k, v)
        }
        put("counters", countersObj)
        // timers
        val timersObj = kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in snap.timers) {
                put(k, buildJsonObject {
                    put("count", v.count)
                    put("totalMs", v.totalMs)
                    put("avgMs", v.avgMs)
                    put("minMs", v.minMs)
                    put("maxMs", v.maxMs)
                })
            }
        }
        put("timers", timersObj)
        // gauges
        val gaugesObj = kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in snap.gauges) put(k, v.toDouble())
        }
        put("gauges", gaugesObj)
    }

    private fun buildSummaryJson(s: com.codesage.observability.ObservabilitySummary): JsonObject = buildJsonObject {
        put("totalEvents", s.totalEvents)
        put("totalTraces", s.totalTraces)
        put("totalCounters", s.totalCounters)
        put("totalTimers", s.totalTimers)
        put("uptimeMs", s.uptimeMs)
        put("uptimeFormatted", s.uptimeFormatted())
    }

    /**
     * 发送 trace 树
     */
    private fun sendTraceTree(traceId: String) {
        val tree = observabilityService.getTraceTree(traceId) ?: return
        val html = renderTreeHtml(tree.root, 0)
        sendToJS(
            mapOf(
                "type" to "observability.trace",
                "payload" to mapOf("traceId" to traceId, "html" to html)
            )
        )
    }

    private fun renderTreeHtml(node: ExecutionTracer.TraceTree.Node, depth: Int): String {
        val indent = "  ".repeat(depth)
        val status = node.span.status.name
        val duration = node.span.durationMs?.let { "${it}ms" } ?: ""
        val attrs = if (node.span.attributes.isNotEmpty()) {
            " " + node.span.attributes.entries.joinToString(" ") { "${it.key}=${it.value}" }
        } else ""
        val sb = StringBuilder()
        sb.append("$indent<span class=\"trace-name\">${escape(node.span.name)}</span>")
        sb.append(" <span class=\"trace-status $status\">$status</span>")
        if (duration.isNotEmpty()) sb.append(" <span class=\"trace-duration\">$duration</span>")
        if (attrs.isNotEmpty()) sb.append("<div class=\"trace-attrs\">${escape(attrs)}</div>")
        sb.append("<br/>")
        for (child in node.children) {
            sb.append(renderTreeHtml(child, depth + 1))
        }
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    /**
     * 发送消息到 JS
     */
    private fun sendToJS(message: Map<String, Any>) {
        val json = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("type", message["type"] as String)
            val payload = message["payload"]
            when (payload) {
                is String -> put("payload", kotlinx.serialization.json.JsonPrimitive(payload))
                else -> {
                    // 直接把 Map 转 JSON
                    @Suppress("UNCHECKED_CAST")
                    put("payload", Json.encodeToJsonElement(JsonObject.serializer(), payload as JsonObject))
                }
            }
        })
        val script = """
            (function() {
                if (typeof window.onJavaMessage === 'function') {
                    window.onJavaMessage($json);
                }
            })();
        """.trimIndent()
        val b = browser
        if (b != null) {
            b.cefBrowser?.executeJavaScript(script, b.cefBrowser.url ?: "", 0)
        }
    }

    /**
     * 释放资源
     */
    fun dispose() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        browser?.dispose()
    }
}
