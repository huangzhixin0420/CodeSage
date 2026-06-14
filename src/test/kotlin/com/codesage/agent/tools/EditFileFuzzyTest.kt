package com.codesage.agent.tools

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * P1 6.2.3：edit_file / multi_edit 模糊匹配端到端测试。
 */
class EditFileFuzzyTest {

    private val tools = IDETools(project = null)

    @Test
    fun `edit_file fuzzy_match disambiguates by context`(@TempDir tempDir: File) {
        val target = File(tempDir, "sample.kt").apply {
            writeText(
                """
                fun alpha() {
                    val x = 1
                }
                fun beta() {
                    val x = 1
                }
                """.trimIndent()
            )
        }

        val result = tools.editFile(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(target.absolutePath),
                    "old_string" to JsonPrimitive("val x = 1"),
                    "new_string" to JsonPrimitive("val x = 2"),
                    "fuzzy_match" to JsonPrimitive(true)
                )
            )
        )

        assertTrue(result is ToolResult.Success, "Expected success: $result")
        val content = target.readText()
        assertTrue(content.contains("val x = 2"))
        // 只有一处被替换
        assertEquals(1, content.split("val x = 2").size - 1)
    }

    @Test
    fun `edit_file returns candidate locations when still ambiguous`(@TempDir tempDir: File) {
        // 两个 old_string 处于完全相同的上下文中，去歧失败，应返回候选位置。
        val target = File(tempDir, "sample.kt").apply {
            writeText(
                """
                fun alpha() {
                    val x = 1
                    val x = 1
                }
                """.trimIndent()
            )
        }

        val result = tools.editFile(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(target.absolutePath),
                    "old_string" to JsonPrimitive("val x = 1"),
                    "new_string" to JsonPrimitive("val x = 2"),
                    "fuzzy_match" to JsonPrimitive(true)
                )
            )
        )

        assertTrue(result is ToolResult.Error, "Expected error: $result")
        val message = (result as ToolResult.Error).message
        assertTrue(message.contains("line"), "Should include candidate line numbers: $message")
    }

    @Test
    fun `multi_edit fuzzy_match applies multiple edits with indentation tolerance`(@TempDir tempDir: File) {
        val target = File(tempDir, "sample.kt").apply {
            writeText(
                """
                class Sample {
                    val a = 1
                    val b = 2
                }
                """.trimIndent()
            )
        }

        val edits = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "old_string" to JsonPrimitive("val a = 1"),
                        "new_string" to JsonPrimitive("val a = 10")
                    )
                ),
                JsonObject(
                    mapOf(
                        "old_string" to JsonPrimitive("val b = 2"),
                        "new_string" to JsonPrimitive("val b = 20")
                    )
                )
            )
        )
        val result = tools.multiEdit(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(target.absolutePath),
                    "edits" to edits,
                    "fuzzy_match" to JsonPrimitive(true)
                )
            )
        )

        assertTrue(result is ToolResult.Success, "Expected success: $result")
        val content = target.readText()
        assertTrue(content.contains("val a = 10"))
        assertTrue(content.contains("val b = 20"))
    }

    @Test
    fun `edit_file exact match remains backward compatible`(@TempDir tempDir: File) {
        val target = File(tempDir, "sample.kt").apply { writeText("val x = 1\n") }

        val result = tools.editFile(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(target.absolutePath),
                    "old_string" to JsonPrimitive("val x = 1"),
                    "new_string" to JsonPrimitive("val x = 2")
                )
            )
        )

        assertTrue(result is ToolResult.Success, "Expected success: $result")
        assertEquals("val x = 2\n", target.readText())
    }

    @Test
    fun `edit_file schema exposes fuzzy_match parameter`() {
        val registry = ToolRegistry.createDefault(project = null)
        val editFile = registry.get("edit_file")
        assertNotNull(editFile)
        assertTrue("fuzzy_match" in editFile!!.parameters.properties.keys)

        val multiEdit = registry.get("multi_edit")
        assertNotNull(multiEdit)
        assertTrue("fuzzy_match" in multiEdit!!.parameters.properties.keys)
    }
}
