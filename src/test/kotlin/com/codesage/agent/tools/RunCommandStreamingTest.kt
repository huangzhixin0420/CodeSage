package com.codesage.agent.tools

import com.codesage.agent.core.AgentStreamEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * run_command 流式输出测试
 */
class RunCommandStreamingTest {

    @Test
    fun `stream_output false falls back to synchronous result`() = runBlocking {
        val ideTools = IDETools(null)
        val events = mutableListOf<AgentStreamEvent>()
        val result = ideTools.runCommand(
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive("echo hello"),
                    "stream_output" to JsonPrimitive(false)
                )
            )
        ) { events.add(it) }

        assertTrue(result is ToolResult.Success)
        assertEquals(0, events.size, "stream_output=false 不应产生流式事件")
        val data = (result as ToolResult.Success).data.jsonObject
        assertTrue(data["stdout"]?.jsonPrimitive?.content?.contains("hello") == true)
        assertEquals(0, data["exit_code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `stream_output true emits stdout chunks and final done`() = runBlocking {
        val ideTools = IDETools(null)
        val events = mutableListOf<AgentStreamEvent>()
        val result = ideTools.runCommand(
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive("echo line1 && echo line2"),
                    "stream_output" to JsonPrimitive(true)
                )
            )
        ) { events.add(it) }

        assertTrue(result is ToolResult.Success)
        val outputEvents = events.filterIsInstance<AgentStreamEvent.CommandOutputStream>()
        assertTrue(outputEvents.isNotEmpty(), "应至少产生一个 CommandOutputStream 事件")

        val combinedStdout = outputEvents.joinToString("") { it.stdout }
        assertTrue(combinedStdout.contains("line1"), "应包含 line1")
        assertTrue(combinedStdout.contains("line2"), "应包含 line2")

        val last = outputEvents.last()
        assertTrue(last.done, "最后一个事件应标记 done=true")
        assertEquals(0, last.exitCode)

        val data = (result as ToolResult.Success).data.jsonObject
        assertTrue(data["streamed"]?.jsonPrimitive?.booleanOrNull == true)
        assertTrue(data["stdout"]?.jsonPrimitive?.content?.contains("line1") == true)
    }

    @Test
    fun `stream_output captures stderr`() = runBlocking {
        val ideTools = IDETools(null)
        val events = mutableListOf<AgentStreamEvent>()
        val result = ideTools.runCommand(
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive("echo err >&2"),
                    "stream_output" to JsonPrimitive(true)
                )
            )
        ) { events.add(it) }

        assertTrue(result is ToolResult.Success)
        val combinedStderr = events.filterIsInstance<AgentStreamEvent.CommandOutputStream>()
            .joinToString("") { it.stderr }
        assertTrue(combinedStderr.contains("err"), "stderr 应包含 err")
    }

    @Test
    fun `stream_output propagates non-zero exit code`() = runBlocking {
        val ideTools = IDETools(null)
        val events = mutableListOf<AgentStreamEvent>()
        val result = ideTools.runCommand(
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive("exit 7"),
                    "stream_output" to JsonPrimitive(true)
                )
            )
        ) { events.add(it) }

        assertTrue(result is ToolResult.Success)
        val last = events.filterIsInstance<AgentStreamEvent.CommandOutputStream>().last()
        assertTrue(last.done)
        assertEquals(7, last.exitCode)
        assertEquals(7, (result as ToolResult.Success).data.jsonObject["exit_code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `stream_output times out and emits done`() = runBlocking {
        val ideTools = IDETools(null)
        val events = mutableListOf<AgentStreamEvent>()
        val result = ideTools.runCommand(
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive("sleep 10"),
                    "timeout" to JsonPrimitive(200L),
                    "stream_output" to JsonPrimitive(true)
                )
            )
        ) { events.add(it) }

        assertTrue(result is ToolResult.Error)
        val last = events.filterIsInstance<AgentStreamEvent.CommandOutputStream>().last()
        assertTrue(last.done)
        assertTrue(last.stderr.contains("timed out"))
    }

    @Test
    fun `background command with stream_output emits output stream events`() = runBlocking {
        val ideTools = IDETools(null)
        val events = mutableListOf<AgentStreamEvent>()
        val result = ideTools.runCommand(
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive("echo bg1 && echo bg2"),
                    "run_in_background" to JsonPrimitive(true),
                    "stream_output" to JsonPrimitive(true)
                )
            )
        ) { events.add(it) }

        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(true, data["stream_output"]?.jsonPrimitive?.booleanOrNull)
        val processId = data["process_id"]?.jsonPrimitive?.content
        assertNotNull(processId)

        waitForBackgroundProcess(processId!!)

        val outputEvents = events.filterIsInstance<AgentStreamEvent.CommandOutputStream>()
        val combinedStdout = outputEvents.joinToString("") { it.stdout }
        assertTrue(combinedStdout.contains("bg1"), "流式 stdout 应包含 bg1")
        assertTrue(combinedStdout.contains("bg2"), "流式 stdout 应包含 bg2")
        assertTrue(outputEvents.any { it.done }, "应收到 done=true 事件")
    }

    @Test
    fun `background command with stream_output can still be read via read_process_output`() = runBlocking {
        val ideTools = IDETools(null)
        val events = mutableListOf<AgentStreamEvent>()
        val result = ideTools.runCommand(
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive("echo persistent-output"),
                    "run_in_background" to JsonPrimitive(true),
                    "stream_output" to JsonPrimitive(true)
                )
            )
        ) { events.add(it) }

        assertTrue(result is ToolResult.Success)
        val processId = (result as ToolResult.Success).data.jsonObject["process_id"]?.jsonPrimitive?.content
        assertNotNull(processId)

        waitForBackgroundProcess(processId!!)

        val readResult = BackgroundProcessManager.readOutput(processId, 1000)
        assertNotNull(readResult, "read_process_output 应能读取流式后台进程输出")
        val readData = (readResult as ToolResult.Success).data.jsonObject
        assertEquals(false, readData["running"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(
            readData["stdout"]?.jsonPrimitive?.content?.contains("persistent-output") == true,
            "read_process_output 应包含持久化输出"
        )
    }

    @Test
    fun `background command without stream_output still works`() = runBlocking {
        val ideTools = IDETools(null)
        val events = mutableListOf<AgentStreamEvent>()
        val result = ideTools.runCommand(
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive("echo no-stream"),
                    "run_in_background" to JsonPrimitive(true),
                    "stream_output" to JsonPrimitive(false)
                )
            )
        ) { events.add(it) }

        assertTrue(result is ToolResult.Success)
        assertEquals(0, events.size, "stream_output=false 的后台命令不应产生流式事件")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(false, data["stream_output"]?.jsonPrimitive?.booleanOrNull)
        val processId = data["process_id"]?.jsonPrimitive?.content
        assertNotNull(processId)

        waitForBackgroundProcess(processId!!)

        val readResult = BackgroundProcessManager.readOutput(processId, 1000)
        assertNotNull(readResult)
        val readData = (readResult as ToolResult.Success).data.jsonObject
        assertTrue(readData["stdout"]?.jsonPrimitive?.content?.contains("no-stream") == true)
    }

    private fun waitForBackgroundProcess(processId: String) {
        var attempts = 0
        while (BackgroundProcessManager.readOutput(processId, 1000)
                ?.let { (it as ToolResult.Success).data.jsonObject["running"]?.jsonPrimitive?.booleanOrNull } != false
        ) {
            if (++attempts > 50) break
            Thread.sleep(100)
        }
    }
}
