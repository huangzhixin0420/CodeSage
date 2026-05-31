package com.codesage.tools.guardrails

import com.codesage.shared.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 工具操作审计日志
 *
 * - 记录每次工具调用的完整信息
 * - 支持参数脱敏
 * - 保留最近 N 条记录
 * - 支持导出为 JSON/CSV
 */
class ToolAuditLog(
    private val maxEntries: Int = 1000,
    private val logFilePath: String? = null
) {

    private val logger = Logger.getLogger<ToolAuditLog>()
    private val json = Json { prettyPrint = true }

    @Serializable
    data class AuditEntry(
        val timestamp: Long,
        val toolName: String,
        val arguments: Map<String, String>,
        val resultStatus: String,
        val durationMs: Long,
        val truncated: Boolean = false,
        val originalLength: Int? = null,
        val truncatedLength: Int? = null,
        val rateLimitWarning: String? = null,
        val confirmationStatus: String? = null
    )

    private val entries = ConcurrentLinkedDeque<AuditEntry>()

    // 脱敏关键字
    private val sensitiveKeys = setOf(
        "password", "token", "secret", "api_key", "apikey", "credential",
        "auth", "private_key", "access_token", "refresh_token"
    )

    /**
     * 记录一次工具调用
     */
    fun log(
        toolName: String,
        arguments: Map<String, Any>,
        resultStatus: String,
        durationMs: Long,
        truncated: Boolean = false,
        originalLength: Int? = null,
        truncatedLength: Int? = null,
        rateLimitWarning: String? = null,
        confirmationStatus: String? = null
    ) {
        val entry = AuditEntry(
            timestamp = System.currentTimeMillis(),
            toolName = toolName,
            arguments = sanitizeArguments(arguments),
            resultStatus = resultStatus,
            durationMs = durationMs,
            truncated = truncated,
            originalLength = originalLength,
            truncatedLength = truncatedLength,
            rateLimitWarning = rateLimitWarning,
            confirmationStatus = confirmationStatus
        )

        entries.addLast(entry)

        // 保留最近 N 条
        while (entries.size > maxEntries) {
            entries.pollFirst()
        }

        // 写入文件（如果配置了）
        logFilePath?.let { appendToFile(entry) }
    }

    /**
     * 获取最近 N 条审计记录
     */
    fun getRecent(n: Int = 100): List<AuditEntry> {
        return entries.toList().takeLast(n)
    }

    /**
     * 获取所有审计记录
     */
    fun getAll(): List<AuditEntry> {
        return entries.toList()
    }

    /**
     * 导出为 JSON 字符串
     */
    fun exportAsJson(): String {
        return json.encodeToString(entries.toList())
    }

    /**
     * 导出为 CSV 字符串
     */
    fun exportAsCsv(): String {
        val header = "timestamp,toolName,resultStatus,durationMs,truncated,rateLimitWarning,confirmationStatus\n"
        val rows = entries.joinToString("\n") { entry ->
            buildString {
                append(formatTimestamp(entry.timestamp))
                append(",")
                append(escapeCsv(entry.toolName))
                append(",")
                append(entry.resultStatus)
                append(",")
                append(entry.durationMs)
                append(",")
                append(entry.truncated)
                append(",")
                append(escapeCsv(entry.rateLimitWarning ?: ""))
                append(",")
                append(escapeCsv(entry.confirmationStatus ?: ""))
            }
        }
        return header + rows
    }

    /**
     * 清空审计日志
     */
    fun clear() {
        entries.clear()
    }

    /**
     * 对参数进行脱敏处理
     */
    private fun sanitizeArguments(arguments: Map<String, Any>): Map<String, String> {
        return arguments.mapValues { (key, value) ->
            if (sensitiveKeys.any { key.lowercase().contains(it) }) {
                "***REDACTED***"
            } else {
                value.toString().take(500) // 限制单个参数长度
            }
        }
    }

    private fun appendToFile(entry: AuditEntry) {
        try {
            val file = File(logFilePath!!)
            file.parentFile?.mkdirs()
            val line = buildString {
                append("[")
                append(formatTimestamp(entry.timestamp))
                append("] ")
                append(entry.toolName)
                append(" | status=")
                append(entry.resultStatus)
                append(" | duration=")
                append(entry.durationMs)
                append("ms")
                if (entry.truncated) append(" | truncated=true")
                if (entry.rateLimitWarning != null) {
                    append(" | rateLimit=")
                    append(entry.rateLimitWarning)
                }
                if (entry.confirmationStatus != null) {
                    append(" | confirmation=")
                    append(entry.confirmationStatus)
                }
            }
            file.appendText(line + "\n")
        } catch (e: Exception) {
            logger.warn("Failed to write audit log to file: ${e.message}")
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
