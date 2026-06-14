package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.IDETools
import com.codesage.agent.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 6.3.2 glob 工具测试
 */
class GlobToolTest {

    @field:TempDir
    lateinit var tempDir: File

    private val ideTools = IDETools(project = null, auditLog = null, commandSandbox = null)

    @Test
    fun `glob matches files in root by name pattern`() {
        File(tempDir, "a.kt").writeText("x")
        File(tempDir, "b.kt").writeText("y")
        File(tempDir, "c.java").writeText("z")

        val result = runGlob(pattern = "*.kt")
        assertSuccess(result)
        val paths = matches(result).map { it["name"]?.jsonPrimitive?.content }
        assertEquals(setOf("a.kt", "b.kt"), paths.toSet())
    }

    @Test
    fun `glob recursive double star matches nested files`() {
        File(tempDir, "src/main/foo.kt").apply { parentFile.mkdirs(); writeText("") }
        File(tempDir, "src/test/bar.kt").apply { parentFile.mkdirs(); writeText("") }
        File(tempDir, "root.txt").writeText("")

        val result = runGlob(pattern = "src/**/*.kt")
        assertSuccess(result)
        val paths = matches(result).map { it["path"]?.jsonPrimitive?.content }
        assertEquals(2, paths.size)
        assertTrue(paths.all { it?.endsWith(".kt") == true })
    }

    @Test
    fun `include_dirs returns directories`() {
        File(tempDir, "src/main").apply { mkdirs() }
        File(tempDir, "src/test").apply { mkdirs() }

        val result = runGlob(pattern = "src/*", includeDirs = true)
        assertSuccess(result)
        val dirs = matches(result).filter { it["is_directory"]?.jsonPrimitive?.booleanOrNull == true }
        assertEquals(2, dirs.size)
    }

    @Test
    fun `exclude_dirs skips matched directories`() {
        File(tempDir, "keep/ok.txt").apply { parentFile.mkdirs(); writeText("") }
        File(tempDir, "skip/bad.txt").apply { parentFile.mkdirs(); writeText("") }

        val result = runGlob(pattern = "**/*.txt", excludeDirs = listOf("skip"))
        assertSuccess(result)
        val paths = matches(result).map { it["path"]?.jsonPrimitive?.content }
        assertEquals(1, paths.size)
        assertTrue(paths.first()?.contains("keep") == true)
    }

    @Test
    fun `max_results truncates results`() {
        repeat(5) { i ->
            File(tempDir, "file$i.txt").writeText("")
        }

        val result = runGlob(pattern = "*.txt", maxResults = 2)
        assertSuccess(result)
        assertEquals(2, total(result))
        assertTrue(truncated(result))
    }

    @Test
    fun `missing pattern returns error`() {
        val handler = IDEFileHandlers.createGlobHandler(ideTools)
        val result = runBlocking {
            handler.execute(JsonObject(mapOf("path" to JsonPrimitive(tempDir.absolutePath))))
        }
        assertTrue(result is ToolResult.Error)
    }

    private fun runGlob(
        pattern: String,
        maxResults: Int? = null,
        includeDirs: Boolean? = null,
        excludeDirs: List<String>? = null
    ): ToolResult {
        val handler = IDEFileHandlers.createGlobHandler(ideTools)
        val args = mutableMapOf<String, JsonElement>(
            "pattern" to JsonPrimitive(pattern),
            "path" to JsonPrimitive(tempDir.absolutePath)
        )
        maxResults?.let { args["max_results"] = JsonPrimitive(it) }
        includeDirs?.let { args["include_dirs"] = JsonPrimitive(it) }
        excludeDirs?.let { args["exclude_dirs"] = JsonArray(it.map { d -> JsonPrimitive(d) }) }
        return runBlocking { handler.execute(JsonObject(args)) }
    }

    private fun assertSuccess(result: ToolResult) {
        assertTrue(result is ToolResult.Success, "Expected success but got $result")
    }

    private fun matches(result: ToolResult): List<JsonObject> {
        val data = (result as ToolResult.Success).data.jsonObject
        return data["matches"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    private fun total(result: ToolResult): Int {
        return ((result as ToolResult.Success).data.jsonObject["total"]?.jsonPrimitive?.intOrNull) ?: 0
    }

    private fun truncated(result: ToolResult): Boolean {
        return (result as ToolResult.Success).data.jsonObject["truncated"]?.jsonPrimitive?.booleanOrNull ?: false
    }
}
