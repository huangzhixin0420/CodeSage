package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * [DependencyTreeTool] 单元测试。
 *
 * 为避免依赖真实 Maven / Gradle 安装及网络下载，测试使用 fake wrapper 脚本
 * （`mvnw` / `gradlew`）输出预先准备好的 JSON / 文本，[BuildCommandResolver]
 * 会优先使用这些 wrapper。
 */
class DependencyTreeToolTest {

    private val tool = DependencyTreeTool(project = null)

    @Test
    fun `execute should parse maven dependency tree json`(@TempDir tempDir: Path) = runBlocking {
        createFakeMavenProject(tempDir.toFile())

        val result = tool.execute(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(tempDir.toString()),
                    "scope" to JsonPrimitive("compile")
                )
            )
        )

        assertTrue(result is ToolResult.Success, "Expected success but got $result")
        val data = (result as ToolResult.Success).data as JsonObject
        assertEquals("maven", data["build_system"]?.jsonPrimitive?.content)
        assertEquals("compile", data["scope"]?.jsonPrimitive?.content)

        val deps = data["dependencies"]?.jsonArray
        assertNotNull(deps)
        assertEquals(1, deps!!.size)

        val springContext = deps[0].jsonObject
        assertEquals("org.springframework", springContext["group_id"]?.jsonPrimitive?.content)
        assertEquals("spring-context", springContext["artifact_id"]?.jsonPrimitive?.content)
        assertEquals("5.3.21", springContext["version"]?.jsonPrimitive?.content)
        assertEquals(4, springContext["children"]?.jsonArray?.size)

        assertEquals(1, data["total_top_level"]?.jsonPrimitive?.intOrNull)
        assertTrue((data["total_transitive"]?.jsonPrimitive?.intOrNull ?: 0) > 0)
    }

    @Test
    fun `execute should respect max_depth for maven tree`(@TempDir tempDir: Path) = runBlocking {
        createFakeMavenProject(tempDir.toFile())

        val result = tool.execute(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(tempDir.toString()),
                    "scope" to JsonPrimitive("compile"),
                    "max_depth" to JsonPrimitive(1)
                )
            )
        )

        assertTrue(result is ToolResult.Success, "Expected success but got $result")
        val data = (result as ToolResult.Success).data as JsonObject
        val deps = data["dependencies"]?.jsonArray
        assertEquals(1, deps!!.size)
        assertNull(deps[0].jsonObject["children"])
        assertEquals(0, data["total_transitive"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `execute should parse gradle dependency tree text`(@TempDir tempDir: Path) = runBlocking {
        createFakeGradleProject(tempDir.toFile())

        val result = tool.execute(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(tempDir.toString()),
                    "scope" to JsonPrimitive("compile")
                )
            )
        )

        assertTrue(result is ToolResult.Success, "Expected success but got $result")
        val data = (result as ToolResult.Success).data as JsonObject
        assertEquals("gradle", data["build_system"]?.jsonPrimitive?.content)
        assertEquals("compileClasspath", data["configuration"]?.jsonPrimitive?.content)

        val deps = data["dependencies"]?.jsonArray
        assertNotNull(deps)
        assertEquals(1, deps!!.size)

        val springContext = deps[0].jsonObject
        assertEquals("org.springframework", springContext["group_id"]?.jsonPrimitive?.content)
        assertEquals("spring-context", springContext["artifact_id"]?.jsonPrimitive?.content)
        assertEquals("5.3.21", springContext["version"]?.jsonPrimitive?.content)
        assertEquals(4, springContext["children"]?.jsonArray?.size)

        assertEquals(1, data["total_top_level"]?.jsonPrimitive?.intOrNull)
        assertTrue((data["total_transitive"]?.jsonPrimitive?.intOrNull ?: 0) > 0)
    }

    @Test
    fun `execute should preserve gradle markers`(@TempDir tempDir: Path) = runBlocking {
        createFakeGradleProject(tempDir.toFile())

        val result = tool.execute(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(tempDir.toString()),
                    "scope" to JsonPrimitive("compile")
                )
            )
        )

        val data = (result as ToolResult.Success).data as JsonObject
        val root = data["dependencies"]?.jsonArray?.get(0)?.jsonObject
            ?: fail("missing root dependency")
        val coreNode = root["children"]?.jsonArray?.find {
            it.jsonObject["artifact_id"]?.jsonPrimitive?.content == "spring-core" &&
                    it.jsonObject["markers"]?.jsonPrimitive?.content == "(*)"
        }?.jsonObject ?: fail("missing spring-core node with marker")
        assertEquals("(*)", coreNode["markers"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute should return error for unsupported project`(@TempDir tempDir: Path) = runBlocking {
        val result = tool.execute(
            JsonObject(mapOf("path" to JsonPrimitive(tempDir.toString())))
        )

        assertTrue(result is ToolResult.Error, "Expected error for unsupported project")
        val message = (result as ToolResult.Error).message
        assertTrue(message.contains("No supported build system"), message)
    }

    @Test
    fun `execute should return error for non-existent path`() = runBlocking {
        val result = tool.execute(
            JsonObject(mapOf("path" to JsonPrimitive("/does/not/exist")))
        )

        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("does not exist"))
    }

    // region helpers

    private fun createFakeMavenProject(dir: File) {
        File(dir, "pom.xml").writeText("<project/>")
        val mvnw = File(dir, "mvnw")
        mvnw.writeText(
            """
            #!/bin/sh
            mkdir -p target
            cat > target/codesage-dependency-tree.json <<'JSON'
            {
              "groupId": "com.test",
              "artifactId": "demo",
              "version": "1.0",
              "type": "jar",
              "scope": "",
              "children": [
                {
                  "groupId": "org.springframework",
                  "artifactId": "spring-context",
                  "version": "5.3.21",
                  "type": "jar",
                  "scope": "compile",
                  "children": [
                    { "groupId": "org.springframework", "artifactId": "spring-aop", "version": "5.3.21", "type": "jar", "scope": "compile" },
                    { "groupId": "org.springframework", "artifactId": "spring-beans", "version": "5.3.21", "type": "jar", "scope": "compile" },
                    {
                      "groupId": "org.springframework",
                      "artifactId": "spring-core",
                      "version": "5.3.21",
                      "type": "jar",
                      "scope": "compile",
                      "children": [
                        { "groupId": "org.springframework", "artifactId": "spring-jcl", "version": "5.3.21", "type": "jar", "scope": "compile" }
                      ]
                    },
                    { "groupId": "org.springframework", "artifactId": "spring-expression", "version": "5.3.21", "type": "jar", "scope": "compile" }
                  ]
                }
              ]
            }
            JSON
            """.trimIndent()
        )
        assertTrue(mvnw.setExecutable(true), "failed to make mvnw executable")
    }

    private fun createFakeGradleProject(dir: File) {
        File(dir, "build.gradle").writeText("plugins { id 'java' }")
        val gradlew = File(dir, "gradlew")
        gradlew.writeText(
            """
            #!/bin/sh
            cat <<'TREE'
            compileClasspath - Compile classpath for source set 'main'.
            \--- org.springframework:spring-context:5.3.21
                 +--- org.springframework:spring-aop:5.3.21
                 |    +--- org.springframework:spring-beans:5.3.21
                 |    |    \--- org.springframework:spring-core:5.3.21
                 |    |         \--- org.springframework:spring-jcl:5.3.21
                 |    \--- org.springframework:spring-core:5.3.21 (*)
                 +--- org.springframework:spring-beans:5.3.21 (*)
                 +--- org.springframework:spring-core:5.3.21 (*)
                 \--- org.springframework:spring-expression:5.3.21
                      \--- org.springframework:spring-core:5.3.21 (*)

            TREE
            """.trimIndent()
        )
        assertTrue(gradlew.setExecutable(true), "failed to make gradlew executable")
    }

    // endregion
}
