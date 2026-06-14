package com.codesage.agent.tools.handlers

import kotlinx.serialization.json.*
import java.io.File

/**
 * 6.8.1 失败测试自动定位。
 *
 * 对 `run_tests` 解析出的失败/错误用例，尝试在磁盘上定位对应测试源文件，
 * 并提取失败行附近的源码片段，帮助模型快速定位问题。
 *
 * 设计原则：
 * - 不依赖 PSI / 项目索引，保证在测试环境也能工作。
 * - 失败堆栈通常已包含文件名与行号，优先从堆栈解析。
 * - 若堆栈无法解析，按 `classname` 推导文件路径（src/test/...）。
 * - 仅读取少量上下文（默认失败行前后各 3 行），避免撑爆上下文。
 */
object TestFailureLocator {

    data class FailureLocation(
        val filePath: String,
        val line: Int,
        val column: Int?,
        val snippet: String
    )

    /**
     * 批量定位失败用例。
     *
     * @param workingDir 项目根目录
     * @param tests `TestResultParser` 输出的 tests[] 列表
     * @param contextLines 失败行前后保留行数
     * @return 仅包含成功定位到的失败/错误用例的位置信息
     */
    fun locateFailures(
        workingDir: String,
        tests: List<JsonObject>,
        contextLines: Int = 3
    ): List<JsonObject> {
        return tests.mapNotNull { test ->
            val status = test["status"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (status != "failure" && status != "error") return@mapNotNull null

            val className = test["classname"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val testName = test["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val details = test["details"]?.jsonPrimitive?.content ?: ""

            val location = resolveLocation(workingDir, className, testName, details)
                ?: return@mapNotNull null

            val snippet = readSnippet(location.filePath, location.line, contextLines)
                ?: return@mapNotNull null

            JsonObject(
                mapOf(
                    "classname" to JsonPrimitive(className),
                    "name" to JsonPrimitive(testName),
                    "file_path" to JsonPrimitive(location.filePath),
                    "line" to JsonPrimitive(location.line),
                    "column" to JsonPrimitive(location.column),
                    "snippet" to JsonPrimitive(snippet)
                )
            )
        }
    }

    /**
     * 解析失败详情中的文件路径与行号。
     *
     * 支持的典型堆栈格式：
     * - Java/Kotlin JUnit: `at com.example.FooTest.method(FooTest.java:42)`
     * - Gradle 测试失败详情: `FooTest.method(FooTest.kt:42)`
     */
    private fun resolveLocation(
        workingDir: String,
        className: String,
        testName: String,
        details: String
    ): FailureLocation? {
        // 1. 从堆栈中匹配形如 (FileName.java:42) 或 (FileName.kt:42) 的位置
        val stackPattern = Regex("""\(([^()]*)\.(java|kt|scala|groovy):(\d+)\)""")
        stackPattern.findAll(details)
            .mapNotNull { match ->
                val fileName = match.groupValues[1] + "." + match.groupValues[2]
                val line = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                findFile(workingDir, fileName)?.let { FailureLocation(it.absolutePath, line, null, "") }
            }
            .firstOrNull()
            ?.let { return it }

        // 2. 按 className 推导文件路径
        val simpleClassName = className.substringAfterLast('.')
        val extensions = listOf("java", "kt", "scala", "groovy")
        for (ext in extensions) {
            val fileName = "$simpleClassName.$ext"
            val file = findFile(workingDir, fileName)
            if (file != null) {
                val line = findTestMethodLine(file, testName)
                if (line != null) {
                    return FailureLocation(file.absolutePath, line, null, "")
                }
            }
        }

        return null
    }

    private fun findFile(workingDir: String, fileName: String): File? {
        val root = File(workingDir)
        if (!root.isDirectory) return null
        return root.walkTopDown()
            .filter { it.isFile && it.name == fileName }
            .firstOrNull()
    }

    /**
     * 在测试源文件中定位测试方法所在行。
     */
    private fun findTestMethodLine(file: File, testName: String): Int? {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        // 匹配：fun testName( 或 void testName( 或 @Test ... testName(
        val pattern = Regex("""fun\s+$testName\s*\(|\s$testName\s*\(""")
        val idx = pattern.find(text)?.range?.first ?: return null
        return text.substring(0, idx).count { it == '\n' } + 1
    }

    private fun readSnippet(filePath: String, line: Int, contextLines: Int): String? {
        val file = File(filePath)
        if (!file.exists()) return null
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return null
        if (line < 1 || line > lines.size) return null
        val start = (line - 1 - contextLines).coerceAtLeast(0)
        val end = (line - 1 + contextLines).coerceAtMost(lines.size - 1)
        return buildString {
            for (i in start..end) {
                appendLine("${i + 1}: ${lines[i]}")
            }
        }.trimEnd()
    }
}
