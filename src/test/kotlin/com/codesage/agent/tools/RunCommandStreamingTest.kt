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
}
