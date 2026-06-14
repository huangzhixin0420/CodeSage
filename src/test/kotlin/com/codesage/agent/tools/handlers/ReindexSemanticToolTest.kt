package com.codesage.agent.tools.handlers

import com.codesage.analysis.CodeInsightExecutor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy

class ReindexSemanticToolTest {

    private fun createStubProject(basePath: String): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "TestProject"
                "getBasePath" -> basePath
                "isDisposed" -> false
                "toString" -> "TestProject"
                else -> null
            }
        } as Project
    }

    @Test
    fun `reindex_semantic indexes project files and returns chunk count`(@TempDir tmpDir: File) {
        val project = createStubProject(tmpDir.absolutePath)

        // Create a few source files
        File(tmpDir, "src").mkdirs()
        File(tmpDir, "src/UserService.kt").writeText(
            """
            package com.example

            /**
             * Service for user operations.
             */
            class UserService {
                fun getUserById(id: Long): User = User(id)
                fun authenticate(email: String, password: String): Boolean = true
            }

            data class User(val id: Long)
            """.trimIndent()
        )

        val executor = CodeInsightExecutor(project)
        val args = JsonObject(mapOf("force" to JsonPrimitive(true)))

        val result = runBlocking { ReindexSemanticTool(executor).execute(args) }

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success, "Expected success but got $result")
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data as? JsonObject
        assertNotNull(data)

        val filesIndexed = data?.get("files_indexed")?.toString()?.toIntOrNull() ?: 0
        val chunksIndexed = data?.get("chunks_indexed")?.toString()?.toIntOrNull() ?: 0

        assertTrue(filesIndexed > 0, "Should have indexed at least one file")
        assertTrue(chunksIndexed > 0, "Should have created at least one chunk")
    }

    @Test
    fun `reindex_semantic returns error for nonexistent path`(@TempDir tmpDir: File) {
        val project = createStubProject(tmpDir.absolutePath)
        val executor = CodeInsightExecutor(project)

        val args = JsonObject(mapOf("path" to JsonPrimitive("/does/not/exist")))
        val result = runBlocking { ReindexSemanticTool(executor).execute(args) }

        assertTrue(result is com.codesage.agent.tools.ToolResult.Error, "Expected error for invalid path")
    }
}
