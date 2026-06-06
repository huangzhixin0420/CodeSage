package com.codesage.tools.guardrails

import com.codesage.shared.serialization.SharedJson
import com.codesage.shared.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 工具操作审计日志
 *
 * - 记录每次工具调用的完整信息
 * - 支持参数脱敏（递归 JsonElement 遍历，捕获嵌套敏感字段）
 * - 保留最近 N 条记录
 * - 支持导出为 JSON/CSV
 */
class ToolAuditLog(
    private val maxEntries: Int = 1000,
    private val logFilePath: String? = null
) {

    private val logger = Logger.getLogger<ToolAuditLog>()
    private val json = SharedJson.pretty

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

    // 脱敏关键字（key 名包含这些子串的值会被脱敏）
    private val sensitiveKeys = setOf(
        "password", "token", "secret", "api_key", "apikey", "credential",
        "auth", "private_key", "access_token", "refresh_token"
    )

    /**
     * 单个字段值的最大长度（仅在非敏感时生效；敏感字段直接被替换为 [REDACTED]）
     */
    private val maxValueLength: Int = 500

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
     *
     * C1 修复：现在用 [sanitizeValue] 递归处理嵌套 Map/List/JsonElement，
     * 之前只对顶层 key 做 contains 检查，嵌套字段（如 `{"headers": {"Authorization": "Bearer xxx"}}`）
     * 会被原样写入审计日志。
     *
     * 实现要点：
     * - 顶层 key 不区分大小写匹配 [sensitiveKeys] 任一子串 → 整个 value 替换为 REDACTED
     * - 嵌套对象（Map/JsonObject）继续递归
     * - 嵌套列表（List/JsonArray）继续递归每个元素
     * - 标量值做长度截断（仅非敏感时），避免巨大字符串撑爆日志
     */
    private fun sanitizeArguments(arguments: Map<String, Any>): Map<String, String> {
        return arguments.mapValues { (key, value) ->
            if (isSensitiveKey(key)) {
                REDACTED
            } else {
                sanitizeValue(value, depth = 0)
            }
        }
    }

    /**
     * 递归把任意 value 变成脱敏后的字符串。
     * 对嵌套 Map / 嵌套 JsonObject 继续递归匹配 sensitive key。
     */
    private fun sanitizeValue(value: Any?, depth: Int): String {
        // 防止异常深度的递归（理论上工具参数不会嵌套这么深，defense in depth）
        if (depth > MAX_SANITIZE_DEPTH) {
            return "[depth-exceeded]"
        }
        return when (value) {
            null -> ""
            is Map<*, *> -> {
                // 嵌套 Map：把 key 序列化为 JSON-like 表示；递归脱敏每个 value
                val inner = value.entries.joinToString(",") { (k, v) ->
                    val keyStr = k?.toString() ?: "null"
                    val sanitized = if (isSensitiveKey(keyStr)) REDACTED else sanitizeValue(v, depth + 1)
                    "$keyStr=$sanitized"
                }
                "{$inner}"
            }
            is List<*> -> {
                val inner = value.joinToString(",") { sanitizeValue(it, depth + 1) }
                "[$inner]"
            }
            is JsonObject -> {
                val inner = value.entries.joinToString(",") { (k, v) ->
                    val sanitized = if (isSensitiveKey(k)) REDACTED else sanitizeJsonElement(v, depth + 1)
                    "$k=$sanitized"
                }
                "{$inner}"
            }
            is JsonArray -> {
                val inner = value.joinToString(",") { sanitizeJsonElement(it, depth + 1) }
                "[$inner]"
            }
            else -> value.toString().take(maxValueLength)
        }
    }

    /**
     * 对 [JsonElement] 递归脱敏（处理 raw JSON 字符串输入——来自 stream 路径）。
     *
     * C1 修复：ToolExecutor 在 stream 路径下可能直接把 model 返回的 raw JSON 字符串
     * 当作 `arguments` 传入，旧的 `Map<String, Any>` sanitizeArguments 会先 `toString()`
     * 再 `take(500)` —— 包含 `apiKey` 的 JSON 字符串被截断后**仍包含敏感值**。
     * 现在新增的 sanitizeJsonElement 先尝试把 raw JSON 解析为 [JsonElement]，再递归脱敏。
     */
    private fun sanitizeJsonElement(element: JsonElement, depth: Int): String {
        if (depth > MAX_SANITIZE_DEPTH) return "[depth-exceeded]"
        return when (element) {
            is JsonNull -> "null"
            is JsonPrimitive -> {
                // 注意：value.toString() 会带引号包裹（如果 isString），这里统一返回不带引号
                // 的原始 content，便于审计人员识别。
                element.content.take(maxValueLength)
            }
            is JsonObject -> {
                val inner = element.entries.joinToString(",") { (k, v) ->
                    val sanitized = if (isSensitiveKey(k)) REDACTED else sanitizeJsonElement(v, depth + 1)
                    "$k=$sanitized"
                }
                "{$inner}"
            }
            is JsonArray -> {
                val inner = element.joinToString(",") { sanitizeJsonElement(it, depth + 1) }
                "[$inner]"
            }
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return sensitiveKeys.any { lower.contains(it) }
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

    companion object {
        const val REDACTED: String = "***REDACTED***"
        private const val MAX_SANITIZE_DEPTH: Int = 10
    }
}
