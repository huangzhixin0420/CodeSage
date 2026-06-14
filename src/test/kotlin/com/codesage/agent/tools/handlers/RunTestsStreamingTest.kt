package com.codesage.agent.tools.handlers

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 6.8.1 run_tests 流式输出测试
 */
class RunTestsStreamingTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun `stream_output false returns synchronous result`() {
        setupGradleProject()
        val handler = TestToolHandlers.createRunTestsHandler(null)
        val events = mutableListOf<AgentStreamEvent>()

        val result = runBlocking {
            handler.execute(
                JsonObject(
                    mapOf(
                        "working_dir" to JsonPrimitive(tempDir.absolutePath),
                        "stream_output" to JsonPrimitive(false)
                    )
                )
            ) { events.add(it) }
        }

        assertTrue(result is ToolResult.Success)
        assertEquals(0, events.size, "stream_output=false 不应产生流式事件")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals("gradle", data["build_system"]?.jsonPrimitive?.content)
    }

    @Test
    fun `stream_output true emits command output events`() {
        setupGradleProject()
        val handler = TestToolHandlers.createRunTestsHandler(null)
        val events = mutableListOf<AgentStreamEvent>()

        val result = runBlocking {
            handler.execute(
                JsonObject(
                    mapOf(
                        "working_dir" to JsonPrimitive(tempDir.absolutePath),
                        "stream_output" to JsonPrimitive(true)
                    )
                )
            ) { events.add(it) }
        }

        assertTrue(result is ToolResult.Success)
        val outputEvents = events.filterIsInstance<AgentStreamEvent.CommandOutputStream>()
        assertTrue(outputEvents.isNotEmpty())
        val last = outputEvents.last()
        assertTrue(last.done)

        val data = (result as ToolResult.Success).data.jsonObject
        assertTrue(data["streamed"]?.jsonPrimitive?.booleanOrNull == true)
    }

    @Test
    fun `run_tests includes failure_locations for failing tests`() {
        setupGradleProjectWithFailingTest()
        val handler = TestToolHandlers.createRunTestsHandler(null)

        val result = runBlocking {
            handler.execute(
                JsonObject(mapOf("working_dir" to JsonPrimitive(tempDir.absolutePath)))
            )
        }

        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        val locations = data["failure_locations"]?.jsonArray ?: emptyList()
        assertTrue(locations.isNotEmpty(), "应有失败定位信息")
        val loc = locations.first().jsonObject
        assertTrue(loc["file_path"]?.jsonPrimitive?.content?.endsWith("SampleTest.kt") == true)
        assertTrue((loc["line"]?.jsonPrimitive?.int ?: 0) > 0)
    }

    /**
     * 构造一个最小 Gradle 项目，并用一个 fake gradlew 脚本模拟测试运行：
     * 生成一个成功 XML 报告并退出 0，避免真实下载 Gradle 依赖。
     */
    private fun setupGradleProject() {
        File(tempDir, "build.gradle.kts").writeText(
            """
            plugins { java }
        """.trimIndent()
        )
        File(tempDir, "gradlew").writeText(
            """#!/bin/bash
set -e
mkdir -p build/test-results/test
cat > build/test-results/test/TEST-com.example.SampleTest.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite tests="1" failures="0" errors="0" skipped="0" time="0.01">
  <testcase classname="com.example.SampleTest" name="ok" time="0.01"/>
</testsuite>
XML
echo "BUILD SUCCESSFUL"
            """.trimIndent()
        )
        File(tempDir, "gradlew").setExecutable(true)
    }

    /**
     * 构造一个最小 Gradle 项目，fake gradlew 生成一个失败 XML 报告，
     * 用于验证 failure_locations 的 end-to-end 集成。
     */
    private fun setupGradleProjectWithFailingTest() {
        File(tempDir, "build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.20" }
        """.trimIndent()
        )
        val testDir = File(tempDir, "src/test/kotlin/com/example").apply { mkdirs() }
        File(testDir, "SampleTest.kt").writeText(
            """
            package com.example

            class SampleTest {
                fun failingTest() {
                    throw AssertionError("boom")
                }
            }
        """.trimIndent()
        )
        File(tempDir, "gradlew").writeText(
            """#!/bin/bash
mkdir -p build/test-results/test
cat > build/test-results/test/TEST-com.example.SampleTest.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite tests="1" failures="1" errors="0" skipped="0" time="0.01">
  <testcase classname="com.example.SampleTest" name="failingTest" time="0.01">
    <failure message="boom" type="AssertionError">at com.example.SampleTest.failingTest(SampleTest.kt:6)</failure>
  </testcase>
</testsuite>
XML
echo "BUILD FAILED"
exit 1
            """.trimIndent()
        )
        File(tempDir, "gradlew").setExecutable(true)
    }
}
