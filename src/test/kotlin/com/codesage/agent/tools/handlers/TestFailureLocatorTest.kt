package com.codesage.agent.tools.handlers

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 6.8.1 失败测试自动定位测试
 */
class TestFailureLocatorTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun `locateFailures extracts file and line from stack trace`() {
        val srcDir = File(tempDir, "src/test/kotlin/com/example").apply { mkdirs() }
        File(srcDir, "FooTest.kt").writeText(
            """
            package com.example

            class FooTest {
                fun failingTest() {
                    throw AssertionError("boom")
                }
            }
            """.trimIndent()
        )

        val tests = listOf(
            JsonObject(
                mapOf(
                    "classname" to JsonPrimitive("com.example.FooTest"),
                    "name" to JsonPrimitive("failingTest"),
                    "status" to JsonPrimitive("failure"),
                    "details" to JsonPrimitive("at com.example.FooTest.failingTest(FooTest.kt:5)")
                )
            )
        )

        val locations = TestFailureLocator.locateFailures(tempDir.absolutePath, tests)

        assertEquals(1, locations.size)
        val loc = locations.first()
        assertTrue(loc["file_path"]?.jsonPrimitive?.content?.endsWith("FooTest.kt") == true)
        assertEquals(5, loc["line"]?.jsonPrimitive?.int)
        assertTrue(loc["snippet"]?.jsonPrimitive?.content?.contains("throw AssertionError") == true)
    }

    @Test
    fun `locateFailures falls back to method search when stack lacks file`() {
        val srcDir = File(tempDir, "src/test/java/com/example").apply { mkdirs() }
        File(srcDir, "BarTest.java").writeText(
            """
            package com.example;

            public class BarTest {
                public void testBar() {
                    assertTrue(false);
                }
            }
            """.trimIndent()
        )

        val tests = listOf(
            JsonObject(
                mapOf(
                    "classname" to JsonPrimitive("com.example.BarTest"),
                    "name" to JsonPrimitive("testBar"),
                    "status" to JsonPrimitive("error"),
                    "details" to JsonPrimitive("java.lang.AssertionError: expected true")
                )
            )
        )

        val locations = TestFailureLocator.locateFailures(tempDir.absolutePath, tests)

        assertEquals(1, locations.size)
        val loc = locations.first()
        assertTrue(loc["file_path"]?.jsonPrimitive?.content?.endsWith("BarTest.java") == true)
        assertTrue((loc["line"]?.jsonPrimitive?.int ?: 0) > 0)
    }

    @Test
    fun `locateFailures ignores passed tests`() {
        val tests = listOf(
            JsonObject(
                mapOf(
                    "classname" to JsonPrimitive("com.example.FooTest"),
                    "name" to JsonPrimitive("ok"),
                    "status" to JsonPrimitive("passed")
                )
            )
        )

        val locations = TestFailureLocator.locateFailures(tempDir.absolutePath, tests)
        assertTrue(locations.isEmpty())
    }
}
