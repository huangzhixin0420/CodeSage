package com.codesage.observability

import com.codesage.shared.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 结构化日志记录器
 * 以JSON格式记录事件，便于分析和监控
 */
class StructuredLogger(
    private val logDir: File = File(System.getProperty("user.home"), ".codesage/logs"),
    private val maxBufferSize: Int = 1000,
    private val flushIntervalMs: Long = 5000
) {
    private val baseLogger = Logger.getLogger<StructuredLogger>()
    private val json = Json { prettyPrint = false }
    private val buffer = ConcurrentLinkedQueue<LogEntry>()

    // T0.7 修复：用线程安全的 DateTimeFormatter 替换 SimpleDateFormat
    // 原实现：SimpleDateFormat 非线程安全，而 StructuredLogger 在多线程 flush 时会共享
    // 这两个实例，理论上会导致日期格式化错乱（虽然实际表现是数字错位不严重）
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        .withZone(ZoneOffset.systemDefault())
    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
            .withZone(ZoneOffset.UTC)

    init {
        logDir.mkdirs()
        startFlushTimer()
    }

    @Serializable
    data class LogEntry(
        val timestamp: String,
        val level: String,
        val component: String,
        val event: String,
        val message: String,
        val sessionId: String? = null,
        val traceId: String? = null,
        val durationMs: Long? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    /**
     * 记录事件
     */
    fun log(
        level: LogLevel,
        component: String,
        event: String,
        message: String,
        sessionId: String? = null,
        traceId: String? = null,
        durationMs: Long? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        val entry = LogEntry(
            timestamp = timestampFormat.format(java.time.Instant.now()),
            level = level.name,
            component = component,
            event = event,
            message = message,
            sessionId = sessionId,
            traceId = traceId,
            durationMs = durationMs,
            metadata = metadata
        )

        buffer.offer(entry)

        // 同时输出到SLF4J
        val logMessage = "[$component] $event: $message"
        when (level) {
            LogLevel.DEBUG -> baseLogger.debug(logMessage)
            LogLevel.INFO -> baseLogger.info(logMessage)
            LogLevel.WARN -> baseLogger.warn(logMessage)
            LogLevel.ERROR -> baseLogger.error(logMessage)
        }

        // 缓冲区满时立即刷新
        if (buffer.size >= maxBufferSize) {
            flush()
        }
    }

    fun debug(component: String, event: String, message: String, metadata: Map<String, String> = emptyMap()) =
        log(LogLevel.DEBUG, component, event, message, metadata = metadata)

    fun info(component: String, event: String, message: String, metadata: Map<String, String> = emptyMap()) =
        log(LogLevel.INFO, component, event, message, metadata = metadata)

    fun warn(component: String, event: String, message: String, metadata: Map<String, String> = emptyMap()) =
        log(LogLevel.WARN, component, event, message, metadata = metadata)

    fun error(component: String, event: String, message: String, metadata: Map<String, String> = emptyMap()) =
        log(LogLevel.ERROR, component, event, message, metadata = metadata)

    /**
     * 记录Agent事件
     */
    fun logAgentEvent(
        event: String,
        sessionId: String,
        traceId: String,
        durationMs: Long? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        log(
            level = LogLevel.INFO,
            component = "agent",
            event = event,
            message = event,
            sessionId = sessionId,
            traceId = traceId,
            durationMs = durationMs,
            metadata = metadata
        )
    }

    /**
     * 记录工具调用
     */
    fun logToolCall(
        toolName: String,
        sessionId: String,
        traceId: String,
        success: Boolean,
        durationMs: Long,
        metadata: Map<String, String> = emptyMap()
    ) {
        log(
            level = if (success) LogLevel.INFO else LogLevel.WARN,
            component = "tool",
            event = if (success) "tool.success" else "tool.failure",
            message = toolName,
            sessionId = sessionId,
            traceId = traceId,
            durationMs = durationMs,
            metadata = metadata + mapOf("tool" to toolName)
        )
    }

    /**
     * 刷新缓冲区到磁盘
     */
    fun flush() {
        if (buffer.isEmpty()) return

        val entries = mutableListOf<LogEntry>()
        while (entries.size < maxBufferSize) {
            val entry = buffer.poll() ?: break
            entries.add(entry)
        }

        if (entries.isEmpty()) return

        try {
            val logFile = getLogFile()
            val lines = entries.joinToString("\n") { json.encodeToString(it) }
            logFile.appendText(lines + "\n")
        } catch (e: Exception) {
            baseLogger.error("Failed to flush structured logs", e)
        }
    }

    /**
     * 读取日志文件
     */
    fun readLogs(date: Date = Date(), limit: Int = 1000): List<LogEntry> {
        val file = File(logDir, "${dateFormat.format(date.toInstant())}.ndjson")
        if (!file.exists()) return emptyList()

        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .takeLast(limit)
                .mapNotNull { line ->
                    try {
                        json.decodeFromString(LogEntry.serializer(), line)
                    } catch (e: Exception) {
                        null
                    }
                }
        } catch (e: Exception) {
            baseLogger.error("Failed to read logs", e)
            emptyList()
        }
    }

    private fun getLogFile(): File {
        return File(logDir, "${dateFormat.format(java.time.Instant.now())}.ndjson")
    }

    private fun startFlushTimer() {
        Thread {
            while (true) {
                Thread.sleep(flushIntervalMs)
                flush()
            }
        }.apply {
            isDaemon = true
            name = "StructuredLogger-Flush"
            start()
        }
    }

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
}
