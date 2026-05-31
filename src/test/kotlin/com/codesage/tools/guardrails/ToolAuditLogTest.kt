package com.codesage.tools.guardrails

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class ToolAuditLogTest {

    @Test
    fun `should log tool execution`() {
        val auditLog = ToolAuditLog(maxEntries = 100)

        auditLog.log(
            toolName = "read_file",
            arguments = mapOf("path" to "test.kt"),
            resultStatus = "success",
            durationMs = 150
        )

        val entries = auditLog.getAll()
        assertEquals(1, entries.size)
        assertEquals("read_file", entries[0].toolName)
        assertEquals("success", entries[0].resultStatus)
        assertEquals(150, entries[0].durationMs)
    }

    @Test
    fun `should truncate recent entries`() {
        val auditLog = ToolAuditLog(maxEntries = 100)

        repeat(150) { i ->
            auditLog.log(
                toolName = "tool_$i",
                arguments = emptyMap(),
                resultStatus = "success",
                durationMs = 10
            )
        }

        assertEquals(100, auditLog.getAll().size)
        assertEquals(50, auditLog.getRecent(50).size)
    }

    @Test
    fun `should sanitize sensitive arguments`() {
        val auditLog = ToolAuditLog(maxEntries = 100)

        auditLog.log(
            toolName = "write_file",
            arguments = mapOf(
                "path" to "config.txt",
                "api_key" to "super-secret-123",
                "password" to "myPassword",
                "content" to "normal content"
            ),
            resultStatus = "success",
            durationMs = 50
        )

        val entry = auditLog.getAll().first()
        assertEquals("***REDACTED***", entry.arguments["api_key"])
        assertEquals("***REDACTED***", entry.arguments["password"])
        assertEquals("normal content", entry.arguments["content"])
    }

    @Test
    fun `should export as JSON`() {
        val auditLog = ToolAuditLog(maxEntries = 100)

        auditLog.log(
            toolName = "search_code",
            arguments = mapOf("query" to "class Main"),
            resultStatus = "success",
            durationMs = 200,
            truncated = true
        )

        val json = auditLog.exportAsJson()
        assertTrue(json.contains("search_code"))
        assertTrue(json.contains("success"))
        assertTrue(json.contains("truncated"))
    }

    @Test
    fun `should export as CSV`() {
        val auditLog = ToolAuditLog(maxEntries = 100)

        auditLog.log(
            toolName = "run_command",
            arguments = mapOf("command" to "ls -la"),
            resultStatus = "success",
            durationMs = 100
        )

        val csv = auditLog.exportAsCsv()
        assertTrue(csv.contains("timestamp,toolName,resultStatus"))
        assertTrue(csv.contains("run_command"))
        assertTrue(csv.contains("success"))
    }

    @Test
    fun `should clear all entries`() {
        val auditLog = ToolAuditLog(maxEntries = 100)

        auditLog.log(
            toolName = "delete_file",
            arguments = mapOf("path" to "old.txt"),
            resultStatus = "success",
            durationMs = 20
        )

        auditLog.clear()
        assertTrue(auditLog.getAll().isEmpty())
    }

    @Test
    fun `should write to log file`() {
        val tempFile = File.createTempFile("audit_test", ".log")
        tempFile.deleteOnExit()

        val auditLog = ToolAuditLog(maxEntries = 100, logFilePath = tempFile.absolutePath)

        auditLog.log(
            toolName = "read_file",
            arguments = mapOf("path" to "test.txt"),
            resultStatus = "success",
            durationMs = 30,
            rateLimitWarning = "Warning: rate limit"
        )

        val content = tempFile.readText()
        assertTrue(content.contains("read_file"))
        assertTrue(content.contains("status=success"))
        assertTrue(content.contains("rateLimit=Warning: rate limit"))

        tempFile.delete()
    }

    @Test
    fun `should record truncation info`() {
        val auditLog = ToolAuditLog(maxEntries = 100)

        auditLog.log(
            toolName = "search_code",
            arguments = mapOf("query" to "foo"),
            resultStatus = "success",
            durationMs = 100,
            truncated = true,
            originalLength = 10000,
            truncatedLength = 2000
        )

        val entry = auditLog.getAll().first()
        assertTrue(entry.truncated)
        assertEquals(10000, entry.originalLength)
        assertEquals(2000, entry.truncatedLength)
    }
}
