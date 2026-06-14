package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.ExtendedTools
import com.codesage.agent.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 6.6.1 git_push 工具测试
 */
class GitPushToolTest {

    @field:TempDir
    lateinit var tempDir: File

    private val extended = ExtendedTools(project = null, commandSandbox = null)

    @Test
    fun `push to local bare repo sets upstream and succeeds`() {
        val repo = File(tempDir, "repo").apply { mkdirs() }
        val bare = File(tempDir, "bare.git").apply { mkdirs() }

        exec("git", "init", "--bare", workingDir = bare)
        exec("git", "init", workingDir = repo)
        exec("git", "config", "user.email", "test@codesage.ai", workingDir = repo)
        exec("git", "config", "user.name", "Test", workingDir = repo)
        File(repo, "hello.txt").writeText("hello")
        exec("git", "add", "hello.txt", workingDir = repo)
        exec("git", "commit", "-m", "init", workingDir = repo)
        exec("git", "remote", "add", "origin", bare.absolutePath, workingDir = repo)

        val handler = ExtendedToolHandlers.createGitPushHandler(extended)
        val result = runBlocking {
            handler.execute(
                JsonObject(
                    mapOf(
                        "working_dir" to JsonPrimitive(repo.absolutePath),
                        "remote" to JsonPrimitive("origin")
                    )
                )
            )
        }

        assertTrue(result is ToolResult.Success, "Expected success but got $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(true, data["pushed"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("origin", data["remote"]?.jsonPrimitive?.content)
        assertEquals("main", data["branch"]?.jsonPrimitive?.content)
        assertEquals(true, data["upstream_set"]?.jsonPrimitive?.booleanOrNull)

        // verify branch actually pushed
        val ls = exec("git", "ls-remote", "--heads", "origin", workingDir = repo)
        assertTrue("refs/heads/main" in ls, "Expected main branch on remote")
    }

    @Test
    fun `push without remote returns error`() {
        val repo = File(tempDir, "no-remote").apply { mkdirs() }
        exec("git", "init", workingDir = repo)
        exec("git", "config", "user.email", "test@codesage.ai", workingDir = repo)
        exec("git", "config", "user.name", "Test", workingDir = repo)
        File(repo, "a.txt").writeText("a")
        exec("git", "add", "a.txt", workingDir = repo)
        exec("git", "commit", "-m", "init", workingDir = repo)

        val handler = ExtendedToolHandlers.createGitPushHandler(extended)
        val result = runBlocking {
            handler.execute(JsonObject(mapOf("working_dir" to JsonPrimitive(repo.absolutePath))))
        }

        assertTrue(result is ToolResult.Error, "Expected error but got $result")
        val msg = (result as ToolResult.Error).message
        assertTrue("git push failed" in msg, "Expected git push failed but was: $msg")
    }

    @Test
    fun `push in non-git directory returns error`() {
        val handler = ExtendedToolHandlers.createGitPushHandler(extended)
        val result = runBlocking {
            handler.execute(JsonObject(mapOf("working_dir" to JsonPrimitive(tempDir.absolutePath))))
        }

        assertTrue(result is ToolResult.Error, "Expected error but got $result")
        val msg = (result as ToolResult.Error).message
        assertTrue("Not a Git repository" in msg, "Expected not git repo but was: $msg")
    }

    private fun exec(vararg cmd: String, workingDir: File): String {
        val process = ProcessBuilder(*cmd)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            throw AssertionError("Command ${cmd.joinToString(" ")} failed with $code: $output")
        }
        return output
    }
}
