package com.codesage.agent.tools

import com.codesage.agent.tools.handlers.KillProcessTool
import com.codesage.agent.tools.handlers.ReadProcessOutputTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BackgroundProcessManagerTest {

    @Test
    fun `start and read background command output`() {
        val id = BackgroundProcessManager.start("echo HelloBackground", System.getProperty("user.dir"))
        assertTrue(id.isNotBlank())

        // 等待进程结束
        var attempts = 0
        while (BackgroundProcessManager.readOutput(id, 1000)
                ?.let { (it as ToolResult.Success).data.jsonObject["running"]?.jsonPrimitive?.booleanOrNull } != false
        ) {
            if (++attempts > 50) break
            Thread.sleep(100)
        }

        val result = BackgroundProcessManager.readOutput(id, 1000)
        assertNotNull(result)
        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(false, data["running"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(data["stdout"]?.jsonPrimitive?.content?.contains("HelloBackground") == true)
        assertEquals(0, data["exit_code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `kill running background process`() {
        val id = BackgroundProcessManager.start("sleep 30", System.getProperty("user.dir"))
        val readBefore = BackgroundProcessManager.readOutput(id, 1000)
        assertTrue(readBefore is ToolResult.Success)
        assertEquals(true, (readBefore as ToolResult.Success).data.jsonObject["running"]?.jsonPrimitive?.booleanOrNull)

        val killResult = BackgroundProcessManager.kill(id)
        assertTrue(killResult is ToolResult.Success)
        assertTrue((killResult as ToolResult.Success).data.jsonObject["killed"]?.jsonPrimitive?.booleanOrNull == true)

        val readAfter = BackgroundProcessManager.readOutput(id, 1000)
        assertNull(readAfter, "kill 后进程应从管理器中移除")
    }

    @Test
    fun `kill_process tool returns error for unknown id`() = runBlocking {
        val result = KillProcessTool().execute(JsonObject(mapOf("process_id" to JsonPrimitive("no-such-id"))))
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("not found", ignoreCase = true))
    }

    @Test
    fun `read_process_output tool returns error for unknown id`() = runBlocking {
        val result = ReadProcessOutputTool().execute(JsonObject(mapOf("process_id" to JsonPrimitive("no-such-id"))))
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("not found", ignoreCase = true))
    }
}
