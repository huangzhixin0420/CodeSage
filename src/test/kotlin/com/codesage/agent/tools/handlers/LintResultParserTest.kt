package com.codesage.agent.tools.handlers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 6.8.2 run_linter 结构化问题解析器测试
 */
class LintResultParserTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun `parse Checkstyle XML returns issues`() {
        File(tempDir, "target").mkdirs()
        File(tempDir, "target/checkstyle-result.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <checkstyle version="10.0">
                <file name="src/main/java/Foo.java">
                    <error line="10" column="5" severity="error" message="Line is too long" source="com.puppycrawl.tools.checkstyle.checks.sizes.LineLengthCheck"/>
                    <error line="20" severity="warning" message="Missing Javadoc" source="Javadoc"/>
                </file>
            </checkstyle>
            """.trimIndent()
        )

        val (source, issues) = LintResultParser.parseReports(tempDir.absolutePath)
        assertTrue(source.startsWith("checkstyle"))
        assertEquals(2, issues.size)

        val first = issues.first()
        assertEquals("src/main/java/Foo.java", first.file)
        assertEquals(10, first.line)
        assertEquals(5, first.column)
        assertEquals("error", first.severity)
        assertEquals("Line is too long", first.message)
        assertTrue(first.rule?.contains("LineLengthCheck") == true)
    }

    @Test
    fun `parse ESLint JSON returns issues`() {
        File(tempDir, "eslint-report.json").writeText(
            """
            [
                {
                    "filePath": "/project/src/app.js",
                    "messages": [
                        { "line": 3, "column": 1, "severity": 2, "message": "Unexpected var", "ruleId": "no-var" },
                        { "line": 5, "column": 10, "severity": 1, "message": "Missing semicolon", "ruleId": "semi" }
                    ]
                }
            ]
            """.trimIndent()
        )

        val (source, issues) = LintResultParser.parseReports(tempDir.absolutePath)
        assertTrue(source.startsWith("eslint"))
        assertEquals(2, issues.size)

        val error = issues.first { it.severity == "error" }
        assertEquals(3, error.line)
        assertEquals("no-var", error.rule)

        val warning = issues.first { it.severity == "warning" }
        assertEquals(5, warning.line)
        assertEquals("semi", warning.rule)
    }

    @Test
    fun `parse flake8 JSON returns issues`() {
        File(tempDir, "flake8-report.json").writeText(
            """
            [
                { "filename": "app.py", "line_number": 7, "column_number": 80, "text": "line too long", "code": "E501" }
            ]
            """.trimIndent()
        )

        val (source, issues) = LintResultParser.parseReports(tempDir.absolutePath)
        assertTrue(source.startsWith("flake8"))
        assertEquals(1, issues.size)

        val issue = issues.single()
        assertEquals("app.py", issue.file)
        assertEquals(7, issue.line)
        assertEquals(80, issue.column)
        assertEquals("error", issue.severity)
        assertEquals("E501", issue.rule)
    }

    @Test
    fun `parseReports returns none when no report exists`() {
        val (source, issues) = LintResultParser.parseReports(tempDir.absolutePath)
        assertEquals("none", source)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `RunLinterTool returns error for unsupported build system`() {
        val result = runBlocking {
            RunLinterTool().execute(
                JsonObject(mapOf("working_dir" to JsonPrimitive(tempDir.absolutePath)))
            )
        }
        assertTrue(result is com.codesage.agent.tools.ToolResult.Error)
        val msg = (result as com.codesage.agent.tools.ToolResult.Error).message
        assertTrue("No supported build system" in msg)
    }
}
