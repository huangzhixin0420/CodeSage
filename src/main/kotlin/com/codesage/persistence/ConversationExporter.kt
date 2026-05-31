package com.codesage.persistence

import com.codesage.shared.utils.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 对话导出/导入器
 * 支持多种格式导出对话历史
 */
class ConversationExporter {
    private val logger = Logger.getLogger<ConversationExporter>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 导出格式
     */
    enum class ExportFormat {
        MARKDOWN,
        JSON,
        HTML,
        TXT
    }

    /**
     * 导出会话到文件
     */
    fun export(
        session: PersistedSession,
        format: ExportFormat,
        outputFile: File
    ): Boolean {
        return try {
            val content = when (format) {
                ExportFormat.MARKDOWN -> exportToMarkdown(session)
                ExportFormat.JSON -> exportToJson(session)
                ExportFormat.HTML -> exportToHtml(session)
                ExportFormat.TXT -> exportToTxt(session)
            }
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(content)
            logger.info("Exported session ${session.id} to ${outputFile.path}")
            true
        } catch (e: Exception) {
            logger.error("Failed to export session", e)
            false
        }
    }

    /**
     * 导出为Markdown格式
     */
    fun exportToMarkdown(session: PersistedSession): String {
        val sb = StringBuilder()
        sb.appendLine("# Conversation: ${session.name.ifEmpty { "Untitled" }}")
        sb.appendLine()
        sb.appendLine("- **Session ID**: ${session.id}")
        sb.appendLine("- **Created**: ${formatDate(session.createdAt)}")
        sb.appendLine("- **Messages**: ${session.messages.size}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        session.messages.forEach { msg ->
            when (msg.role) {
                "user" -> {
                    sb.appendLine("## User")
                    sb.appendLine()
                    sb.appendLine(msg.content ?: "")
                }

                "assistant" -> {
                    sb.appendLine("## Assistant")
                    sb.appendLine()
                    sb.appendLine(msg.content ?: "")
                }

                "system" -> {
                    sb.appendLine("## System")
                    sb.appendLine()
                    sb.appendLine("```")
                    sb.appendLine(msg.content ?: "")
                    sb.appendLine("```")
                }

                "tool" -> {
                    sb.appendLine("## Tool Result")
                    sb.appendLine()
                    sb.appendLine("```")
                    sb.appendLine(msg.content ?: "")
                    sb.appendLine("```")
                }
            }
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * 导出为JSON格式
     */
    fun exportToJson(session: PersistedSession): String {
        return kotlinx.serialization.json.Json {
            prettyPrint = true
        }.encodeToString(PersistedSession.serializer(), session)
    }

    /**
     * 导出为HTML格式
     */
    fun exportToHtml(session: PersistedSession): String {
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html><head>")
            appendLine("<meta charset=\"UTF-8\">")
            appendLine("<title>${session.name.ifEmpty { "Conversation" }}</title>")
            appendLine("<style>")
            appendLine("body { font-family: sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; }")
            appendLine(".user { background: #e3f2fd; padding: 10px; border-radius: 8px; margin: 10px 0; }")
            appendLine(".assistant { background: #f3e5f5; padding: 10px; border-radius: 8px; margin: 10px 0; }")
            appendLine(".system { background: #fff3e0; padding: 10px; border-radius: 8px; margin: 10px 0; font-size: 0.9em; }")
            appendLine(".tool { background: #e8f5e9; padding: 10px; border-radius: 8px; margin: 10px 0; font-family: monospace; }")
            appendLine("pre { background: #f5f5f5; padding: 10px; overflow-x: auto; }")
            appendLine("</style></head><body>")
            appendLine("<h1>${session.name.ifEmpty { "Conversation" }}</h1>")
            appendLine("<p>Created: ${formatDate(session.createdAt)} | Messages: ${session.messages.size}</p>")
            appendLine("<hr>")

            session.messages.forEach { msg ->
                val cssClass = when (msg.role) {
                    "user" -> "user"
                    "assistant" -> "assistant"
                    "system" -> "system"
                    else -> "tool"
                }
                appendLine("<div class=\"$cssClass\">")
                appendLine("<strong>${msg.role.uppercase()}</strong>")
                appendLine("<pre>${escapeHtml(msg.content ?: "")}</pre>")
                appendLine("</div>")
            }

            appendLine("</body></html>")
        }
    }

    /**
     * 导出为纯文本格式
     */
    fun exportToTxt(session: PersistedSession): String {
        val sb = StringBuilder()
        sb.appendLine("Conversation: ${session.name.ifEmpty { "Untitled" }}")
        sb.appendLine("Created: ${formatDate(session.createdAt)}")
        sb.appendLine("Messages: ${session.messages.size}")
        sb.appendLine("=".repeat(60))
        sb.appendLine()

        session.messages.forEach { msg ->
            val prefix = when (msg.role) {
                "user" -> "USER"
                "assistant" -> "ASSISTANT"
                "system" -> "SYSTEM"
                "tool" -> "TOOL"
                else -> msg.role.uppercase()
            }
            sb.appendLine("[$prefix] ${formatDate(msg.timestamp)}")
            sb.appendLine(msg.content ?: "")
            sb.appendLine("-".repeat(60))
        }

        return sb.toString()
    }

    /**
     * 从JSON文件导入会话
     */
    fun importFromJson(file: File): PersistedSession? {
        return try {
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }.decodeFromString(PersistedSession.serializer(), file.readText())
        } catch (e: Exception) {
            logger.error("Failed to import session from ${file.path}", e)
            null
        }
    }

    private fun formatDate(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
