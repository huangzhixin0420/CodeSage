package com.codesage.agent.tools.handlers

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 6.8.1 run_tests 结构化结果解析器测试
 */
class TestResultParserTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun `parse Gradle JUnit XML returns structured test list and summary`() {
        val reportDir = File(tempDir, "build/test-results/test").apply { mkdirs() }
        File(reportDir, "TEST-com.example.FooTest.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite tests="3" failures="1" errors="0" skipped="1" time="0.05">
                <testcase classname="com.example.FooTest" name="passingTest" time="0.01"/>
                <testcase classname="com.example.FooTest" name="failingTest" time="0.02">
                    <failure message="expected true but was false" type="AssertionError">stack...</failure>
                </testcase>
                <testcase classname="com.example.FooTest" name="skippedTest" time="0.00">
                    <skipped/>
                </testcase>
            </testsuite>
            """.trimIndent()
        )

        val result = TestResultParser.parseReports(tempDir.absolutePath, "gradle")

        assertEquals(3, result.testsRun)
        assertEquals(1, result.failures)
        assertEquals(0, result.errors)
        assertEquals(1, result.skipped)
        assertEquals(1, result.passed)
        assertEquals(3, result.tests.size)

        val failed = result.tests.first { it["status"]?.jsonPrimitive?.content == "failure" }
        assertEquals("failingTest", failed["name"]?.jsonPrimitive?.content)
        assertEquals("expected true but was false", failed["message"]?.jsonPrimitive?.content)
        assertTrue(failed["details"]?.jsonPrimitive?.content?.contains("stack") == true)
    }

    @Test
    fun `parse Maven Surefire XML returns structured test list and summary`() {
        val reportDir = File(tempDir, "target/surefire-reports").apply { mkdirs() }
        File(reportDir, "TEST-com.example.BarTest.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite tests="2" failures="0" errors="1" skipped="0" time="0.12">
                <testcase classname="com.example.BarTest" name="ok" time="0.05"/>
                <testcase classname="com.example.BarTest" name="explodes" time="0.07">
                    <error message="NullPointer" type="java.lang.NullPointerException">npe trace</error>
                </testcase>
            </testsuite>
            """.trimIndent()
        )

        val result = TestResultParser.parseReports(tempDir.absolutePath, "maven")

        assertEquals(2, result.testsRun)
        assertEquals(0, result.failures)
        assertEquals(1, result.errors)
        assertEquals(0, result.skipped)
        assertEquals(1, result.passed)

        val error = result.tests.first { it["status"]?.jsonPrimitive?.content == "error" }
        assertEquals("explodes", error["name"]?.jsonPrimitive?.content)
        assertEquals("NullPointer", error["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseReports returns empty when no XML found`() {
        val result = TestResultParser.parseReports(tempDir.absolutePath, "gradle")
        assertEquals(0, result.testsRun)
        assertTrue(result.tests.isEmpty())
    }

    @Test
    fun `maxResults limits returned test array`() {
        val reportDir = File(tempDir, "build/test-results/test").apply { mkdirs() }
        File(reportDir, "TEST-com.example.ManyTest.xml").writeText(
            buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine("""<testsuite tests="10" failures="0" errors="0" skipped="0" time="1.0">""")
                repeat(10) { i ->
                    appendLine("""  <testcase classname="com.example.ManyTest" name="t$i" time="0.1"/>""")
                }
                appendLine("</testsuite>")
            }
        )

        val result = TestResultParser.parseReports(tempDir.absolutePath, "gradle", maxResults = 3)
        assertEquals(10, result.testsRun)
        assertEquals(3, result.tests.size)
    }
}
