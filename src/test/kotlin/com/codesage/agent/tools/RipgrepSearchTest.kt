package com.codesage.agent.tools

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * P0 优化 6.3.1：ripgrep 搜索测试。
 *
 * 若当前环境无 `rg`，这些测试会自然回退或被跳过；
 * 这里假设 CI/开发机已安装 ripgrep（macOS/Linux 常见）。
 */
class RipgrepSearchTest {

    private fun makeArgs(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

    @Test
    fun `search mode returns matches with ripgrep`(@TempDir tempDir: File) {
        File(tempDir, "Foo.kt").writeText("class Foo\nfun bar() = 1\n")
        File(tempDir, "Bar.kt").writeText("class Bar\n")

        val args = makeArgs(
            "query" to JsonPrimitive("class "),
            "path" to JsonPrimitive(tempDir.absolutePath),
            "max_results" to JsonPrimitive(10)
        )

        val result = RipgrepSearch.execute(args, RipgrepSearch.Mode.Search, tempDir.absolutePath)
        assertNotNull(result, "ripgrep 应可用；若不可用则此测试无意义")
        assertTrue(result is ToolResult.Success, "Expected success: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        val matches = data["matches"]?.jsonArray ?: fail("missing matches")
        assertTrue(matches.size >= 2, "应匹配两个 class 定义")
        assertTrue(matches.any { it.jsonObject["file"]?.jsonPrimitive?.content?.endsWith("Foo.kt") == true })
        assertTrue(matches.any { it.jsonObject["file"]?.jsonPrimitive?.content?.endsWith("Bar.kt") == true })
        assertEquals("ripgrep", data["engine"]?.jsonPrimitive?.content)
    }

    @Test
    fun `grep mode returns context around matches`(@TempDir tempDir: File) {
        File(tempDir, "A.kt").writeText("line1\nline2\ntarget\nline4\nline5\n")

        val args = makeArgs(
            "query" to JsonPrimitive("target"),
            "path" to JsonPrimitive(tempDir.absolutePath),
            "context_lines" to JsonPrimitive(2),
            "max_results" to JsonPrimitive(10)
        )

        val result = RipgrepSearch.execute(args, RipgrepSearch.Mode.Grep, tempDir.absolutePath)
        assertNotNull(result)
        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        val match = data["matches"]?.jsonArray?.firstOrNull()?.jsonObject ?: fail("no match")
        val context = match["context"]?.jsonPrimitive?.content ?: fail("missing context")
        assertTrue(context.contains("line1"), "上下文应包含前面行: $context")
        assertTrue(context.contains("target"), "上下文应包含匹配行: $context")
        assertTrue(context.contains("line5"), "上下文应包含后面行: $context")
    }

    @Test
    fun `file_pattern filters results`(@TempDir tempDir: File) {
        File(tempDir, "a.kt").writeText("foo\n")
        File(tempDir, "a.java").writeText("foo\n")

        val args = makeArgs(
            "query" to JsonPrimitive("foo"),
            "path" to JsonPrimitive(tempDir.absolutePath),
            "file_pattern" to JsonPrimitive("*.kt"),
            "max_results" to JsonPrimitive(10)
        )

        val result = RipgrepSearch.execute(args, RipgrepSearch.Mode.Search, tempDir.absolutePath)
        assertNotNull(result)
        assertTrue(result is ToolResult.Success)
        val matches = (result as ToolResult.Success).data.jsonObject["matches"]?.jsonArray ?: fail("no matches")
        assertEquals(1, matches.size)
        assertTrue(matches[0].jsonObject["file"]?.jsonPrimitive?.content?.endsWith("a.kt") == true)
    }

    @Test
    fun `max_results truncates ripgrep output`(@TempDir tempDir: File) {
        repeat(5) { i ->
            File(tempDir, "f$i.txt").writeText("token\n")
        }

        val args = makeArgs(
            "query" to JsonPrimitive("token"),
            "path" to JsonPrimitive(tempDir.absolutePath),
            "max_results" to JsonPrimitive(2)
        )

        val result = RipgrepSearch.execute(args, RipgrepSearch.Mode.Search, tempDir.absolutePath)
        assertNotNull(result)
        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(2, data["matches"]?.jsonArray?.size)
        assertTrue(data["truncated"]?.jsonPrimitive?.booleanOrNull == true)
    }

    @Test
    fun `returns null and falls back when custom exclude_dirs is provided`(@TempDir tempDir: File) {
        val args = makeArgs(
            "query" to JsonPrimitive("foo"),
            "path" to JsonPrimitive(tempDir.absolutePath),
            "exclude_dirs" to JsonArray(listOf(JsonPrimitive("build")))
        )

        val result = RipgrepSearch.execute(args, RipgrepSearch.Mode.Search, tempDir.absolutePath)
        assertNull(result, "自定义 exclude_dirs 时应回退到 VFS 扫描")
    }
}
