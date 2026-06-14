package com.codesage.agent.core

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 6.10.1 delegate_task 结构化结果格式化器测试
 */
class SubAgentResultFormatterTest {

    @Test
    fun `parseFinalTurn extracts result files and blockers`() {
        val output = """
            **Result**: Refactored AuthService to use new token validator.
            **Files**: src/AuthService.kt, src/TokenValidator.kt
            **Blockers**: Need to update integration tests.
        """.trimIndent()

        val parsed = SubAgentResultFormatter.parseFinalTurn(output)
        assertEquals("Refactored AuthService to use new token validator.", parsed.result)
        assertEquals(listOf("src/AuthService.kt", "src/TokenValidator.kt"), parsed.files)
        assertEquals("Need to update integration tests.", parsed.blockers)
    }

    @Test
    fun `parseFinalTurn treats none files as empty`() {
        val output = """
            **Result**: Investigated but no changes needed.
            **Files**: none
            **Blockers**: none
        """.trimIndent()

        val parsed = SubAgentResultFormatter.parseFinalTurn(output)
        assertTrue(parsed.files.isEmpty())
        assertEquals("none", parsed.blockers)
    }

    @Test
    fun `parseFinalTurn falls back to full output when no structure`() {
        val output = "I did some work and it went fine."
        val parsed = SubAgentResultFormatter.parseFinalTurn(output)
        assertEquals(output, parsed.result)
        assertTrue(parsed.files.isEmpty())
        assertEquals("", parsed.blockers)
    }

    @Test
    fun `toJson produces structured metadata`() {
        val result = SubAgentResult(
            success = true,
            output = "**Result**: Done.\n**Files**: a.kt\n**Blockers**: none",
            sessionId = "sub_123",
            iterationsUsed = 3,
            toolsUsed = listOf("read_file", "write_file"),
            cancelled = false,
            completedToolCalls = listOf(
                ToolCallRecord("read_file", "path: a.kt", 120, true),
                ToolCallRecord("write_file", "path: b.kt", 45, true)
            )
        )

        val json = SubAgentResultFormatter.toJson(result, "sub_123")
        assertEquals(true, json["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, json["cancelled"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("Done.", json["result"]?.jsonPrimitive?.content)
        assertEquals(listOf("a.kt"), json["files"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("none", json["blockers"]?.jsonPrimitive?.content)
        assertEquals(3, json["iterations_used"]?.jsonPrimitive?.intOrNull)
        assertEquals(listOf("read_file", "write_file"), json["tools_used"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(2, json["completed_tool_calls"]?.jsonArray?.size)
        assertEquals("sub_123", json["session_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `toJson includes worktree diff and structured changes`() {
        val structuredChanges = buildJsonObject {
            put("has_changes", true)
            put("total_additions", 2)
            put("total_deletions", 1)
            put("files", JsonArray(emptyList()))
        }
        val result = SubAgentResult(
            success = true,
            output = "**Result**: Done.\n**Files**: a.kt\n**Blockers**: none",
            sessionId = "sub_wt",
            iterationsUsed = 1,
            toolsUsed = listOf("write_file"),
            worktreeDiff = "diff --git a/a.kt b/a.kt\n+added",
            worktreeChanges = structuredChanges
        )

        val json = SubAgentResultFormatter.toJson(result, "sub_wt")
        assertEquals("diff --git a/a.kt b/a.kt\n+added", json["worktree_diff"]?.jsonPrimitive?.content)
        assertEquals(true, json["worktree_changes"]?.jsonObject?.get("has_changes")?.jsonPrimitive?.booleanOrNull)
        assertEquals(2, json["worktree_changes"]?.jsonObject?.get("total_additions")?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `toJson uses empty defaults when worktree fields are absent`() {
        val result = SubAgentResult(
            success = true,
            output = "done",
            sessionId = "sub_no_wt",
            iterationsUsed = 0,
            toolsUsed = emptyList()
        )

        val json = SubAgentResultFormatter.toJson(result, "sub_no_wt")
        assertEquals("", json["worktree_diff"]?.jsonPrimitive?.content)
        assertTrue(json["worktree_changes"] is JsonObject)
    }

    @Test
    fun `toJson handles cancelled result`() {
        val result = SubAgentResult(
            success = false,
            output = "Cancelled by user. Partial work completed:\n...",
            sessionId = "sub_456",
            iterationsUsed = 1,
            toolsUsed = listOf("read_file"),
            cancelled = true,
            completedToolCalls = emptyList()
        )

        val json = SubAgentResultFormatter.toJson(result, "sub_456")
        assertEquals(false, json["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, json["cancelled"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("sub_456", json["session_id"]?.jsonPrimitive?.content)
    }
}
