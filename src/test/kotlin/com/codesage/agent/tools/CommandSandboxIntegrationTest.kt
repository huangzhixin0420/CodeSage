package com.codesage.agent.tools

import com.codesage.shared.security.CommandSandbox
import com.codesage.shared.security.PathBasedSandbox
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Phase 3: 验证 IDETools / ExtendedTools 在注入 OS 级沙箱后正确执行命令。
 */
class CommandSandboxIntegrationTest {

    private fun makeArgs(vararg pairs: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*pairs))

    @Test
    fun `IDETools runCommand should use sandbox and report sandboxed flag`(@TempDir tempDir: File) {
        val sandbox = PathBasedSandbox(tempDir, CommandSandbox.Mode.WORKSPACE_WRITE)
        val tools = IDETools(project = null, commandSandbox = sandbox)

        val result = runBlocking {
            tools.runCommand(
                makeArgs(
                    "command" to JsonPrimitive("echo HelloSandbox"),
                    "working_dir" to JsonPrimitive(tempDir.absolutePath)
                )
            )
        }

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(0, data["exit_code"]?.jsonPrimitive?.int)
        assertTrue(data["stdout"]?.jsonPrimitive?.content?.contains("HelloSandbox") == true)
        assertEquals(false, data["sandboxed"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `ExtendedTools execShell should use sandbox and report sandboxed flag`(@TempDir tempDir: File) {
        val sandbox = PathBasedSandbox(tempDir, CommandSandbox.Mode.WORKSPACE_WRITE)
        val tools = ExtendedTools(project = null, commandSandbox = sandbox)

        val result = runBlocking {
            tools.execShell(
                makeArgs(
                    "command" to JsonPrimitive("echo HelloSandbox"),
                    "working_dir" to JsonPrimitive(tempDir.absolutePath)
                )
            )
        }

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(0, data["exit_code"]?.jsonPrimitive?.int)
        assertTrue(data["stdout"]?.jsonPrimitive?.content?.contains("HelloSandbox") == true)
        assertEquals(false, data["sandboxed"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `sandboxed command should respect timeout through tool layer`(@TempDir tempDir: File) {
        val sandbox = PathBasedSandbox(tempDir, CommandSandbox.Mode.WORKSPACE_WRITE)
        val tools = ExtendedTools(project = null, commandSandbox = sandbox)

        val start = System.currentTimeMillis()
        val result = runBlocking {
            tools.execShell(
                makeArgs(
                    "command" to JsonPrimitive("sleep 5"),
                    "timeout" to JsonPrimitive(500L)
                )
            )
        }
        val duration = System.currentTimeMillis() - start

        assertTrue(result is ToolResult.Error, "Expected timeout error but got: $result")
        assertTrue((result as ToolResult.Error).message.contains("timed out", ignoreCase = true))
        assertTrue(duration < 3000, "Should timeout quickly, but took ${duration}ms")
    }
}
