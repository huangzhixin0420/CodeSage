package com.codesage.agent.tools

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * P1 6.2.2：multi_edit 工具单元测试（project = null，走 File I/O 路径）。
 */
class MultiEditToolTest {

    private val tools = IDETools(project = null)

    @Test
    fun `multiEdit applies multiple non-overlapping edits atomically`(@TempDir tempDir: File) {
        val target = File(tempDir, "sample.kt").apply {
            writeText(
                """
                class Sample {
                    val a = 1
                    val b = 2
                    val c = 3
                }
                """.trimIndent()
            )
        }

        val result = tools.multiEdit(
            buildArgs(
                target.absolutePath,
                listOf(
                    "val a = 1" to "val a = 10",
                    "val b = 2" to "val b = 20",
                    "val c = 3" to "val c = 30"
                )
            )
        )

        assertTrue(result is ToolResult.Success, "Expected success: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(3, data["edits_applied"]?.jsonPrimitive?.intOrNull)

        val content = target.readText()
        assertTrue(content.contains("val a = 10"))
        assertTrue(content.contains("val b = 20"))
        assertTrue(content.contains("val c = 30"))
    }

    @Test
    fun `multiEdit rejects missing old_string and writes nothing`(@TempDir tempDir: File) {
        val target = File(tempDir, "sample.kt").apply { writeText("val x = 1\n") }
        val before = target.readText()

        val result = tools.multiEdit(
            buildArgs(
                target.absolutePath,
                listOf(
                    "val x = 1" to "val x = 2",
                    "not present" to "irrelevant"
                )
            )
        )

        assertTrue(result is ToolResult.Error, "Expected error: $result")
        assertEquals(before, target.readText(), "No partial writes should occur")
    }

    @Test
    fun `multiEdit rejects non-unique old_string`(@TempDir tempDir: File) {
        val target = File(tempDir, "sample.kt").apply { writeText("val x = 1\nval x = 1\n") }

        val result = tools.multiEdit(
            buildArgs(
                target.absolutePath,
                listOf("val x = 1" to "val x = 2")
            )
        )

        assertTrue(result is ToolResult.Error, "Expected error: $result")
        val message = (result as ToolResult.Error).message
        assertTrue(
            message.contains("matches 2 locations") || message.contains("appears 2 times"),
            "Error should mention non-unique match: $message"
        )
    }

    @Test
    fun `multiEdit detects order-dependent edit invalidation`(@TempDir tempDir: File) {
        // 第一个 edit 把 "val x = 1" 改成 "val x = 2"，第二个 edit 的 old_string 仍是 "val x = 1"，
        // 应用在内存中第二个 edit 找不到，应返回明确的应用期错误且不写盘。
        val target = File(tempDir, "sample.kt").apply { writeText("val x = 1\nval y = 1\n") }
        val before = target.readText()

        val result = tools.multiEdit(
            buildArgs(
                target.absolutePath,
                listOf(
                    "val x = 1" to "val x = 2",
                    "val x = 1" to "val x = 3"
                )
            )
        )

        assertTrue(result is ToolResult.Error, "Expected error: $result")
        assertEquals(before, target.readText(), "No partial writes should occur")
    }

    @Test
    fun `multiEdit returns error for empty edits array`(@TempDir tempDir: File) {
        val target = File(tempDir, "sample.kt").apply { writeText("val x = 1\n") }
        val before = target.readText()

        val result = tools.multiEdit(
            buildArgs(target.absolutePath, emptyList())
        )

        assertTrue(result is ToolResult.Error, "Expected error for empty edits: $result")
        assertEquals(before, target.readText())
    }

    @Test
    fun `multiEdit is registered as UnifiedTool`() {
        val registry = ToolRegistry.createDefault(project = null)
        val tool = registry.get("multi_edit")
        assertNotNull(tool, "multi_edit should be registered")
        assertTrue("path" in tool!!.parameters.required)
        assertTrue("edits" in tool.parameters.required)
    }

    private fun buildArgs(path: String, edits: List<Pair<String, String>>): JsonObject {
        val editsArray = edits.map { (oldString, newString) ->
            JsonObject(
                mapOf(
                    "old_string" to JsonPrimitive(oldString),
                    "new_string" to JsonPrimitive(newString)
                )
            )
        }
        return JsonObject(
            mapOf(
                "path" to JsonPrimitive(path),
                "edits" to JsonArray(editsArray)
            )
        )
    }
}
