package com.codesage.agent.tools

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * apply_patch 工具端到端测试（project = null，走 File I/O 路径）。
 */
class ApplyPatchToolIntegrationTest {

    private val tools = IDETools(project = null)

    @Test
    fun `applyPatch updates existing file`(@TempDir tempDir: File) {
        val target = File(tempDir, "Foo.kt").apply { writeText("class Foo {\n    val x = 1\n}\n") }
        val patch = """
            *** Begin Patch
            *** Update File: ${target.absolutePath}
            @@ class Foo {
            -    val x = 1
            +    val x = 2
            *** End Patch
        """.trimIndent()

        val result = tools.applyPatch(args("patch" to patch))
        assertTrue(result is ToolResult.Success, "Expected success: $result")
        assertEquals("class Foo {\n    val x = 2\n}\n", target.readText())
    }

    @Test
    fun `applyPatch adds and deletes files`(@TempDir tempDir: File) {
        val toDelete = File(tempDir, "old.txt").apply { writeText("delete me") }
        val toAdd = File(tempDir, "new.txt")
        val patch = """
            *** Begin Patch
            *** Add File: ${toAdd.absolutePath}
            new content
            *** Delete File: ${toDelete.absolutePath}
            *** End Patch
        """.trimIndent()

        val result = tools.applyPatch(args("patch" to patch))
        assertTrue(result is ToolResult.Success, "Expected success: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        val changed = data["changed_files"]?.jsonArray?.map { it.jsonObject["path"]?.jsonPrimitive?.content }
        assertTrue(changed?.contains(toAdd.absolutePath) == true)
        assertTrue(changed?.contains(toDelete.absolutePath) == true)
        assertTrue(toAdd.exists())
        assertFalse(toDelete.exists())
    }

    @Test
    fun `applyPatch returns error on mismatch without writing partial changes`(@TempDir tempDir: File) {
        val target = File(tempDir, "a.txt").apply { writeText("hello\nworld\n") }
        val patch = """
            *** Begin Patch
            *** Update File: ${target.absolutePath}
            @@ missing context
            - old
            + new
            *** End Patch
        """.trimIndent()

        val before = target.readText()
        val result = tools.applyPatch(args("patch" to patch))
        assertTrue(result is ToolResult.Error, "Expected error: $result")
        assertEquals(before, target.readText(), "部分失败不应写入任何变更")
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(mapOf(*pairs.map { it.first to JsonPrimitive(it.second) }.toTypedArray()))
}
