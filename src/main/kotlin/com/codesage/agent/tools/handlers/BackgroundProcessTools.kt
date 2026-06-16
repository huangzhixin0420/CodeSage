package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.BackgroundProcessManager
import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.UnifiedTool
import com.codesage.tools.guardrails.SensitiveActionPolicy
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import kotlinx.serialization.json.*

/**
 * P0 优化 6.4.2：后台进程管理工具。
 */

class KillProcessTool : UnifiedTool(
    name = "kill_process",
    description = """
        Summary: Terminate a background process started by run_command with run_in_background=true.
        Args: process_id (string, required): the ID returned by run_command.
        Do: Use when the background task is no longer needed or to clean up before starting a conflicting process.
        Don't: Don't kill processes you didn't start unless you know they are safe to terminate.
        Parallel: Yes.
        Cap: Returns killed=true if the process was alive when killed; exit_code may be -1 if the process refused to report it.
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "process_id" to ToolProperty("string", "Process ID returned by run_command")
        ),
        required = listOf("process_id")
    ),
    riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS
) {
    override suspend fun execute(args: JsonObject): ToolResult {
        val processId = args["process_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'process_id' parameter")
        return BackgroundProcessManager.kill(processId)
            ?: ToolResult.Error("Process not found: $processId")
    }
}

class ReadProcessOutputTool : UnifiedTool(
    name = "read_process_output",
    description = """
        Summary: Read the latest stdout/stderr of a background process started by run_command.
        Args: process_id (string, required): the ID returned by run_command; max_output_chars (int): per-stream cap, default 100000.
        Do: Use to poll long-running commands (dev server, test watcher) or capture their final output.
        Don't: Don't set max_output_chars too high for very noisy processes.
        Parallel: Yes.
        Cap: Returns running=true while the process is alive; exit_code is null until it exits.
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "process_id" to ToolProperty("string", "Process ID returned by run_command"),
            "max_output_chars" to ToolProperty("integer", "Maximum characters to read per stream, default 100000")
        ),
        required = listOf("process_id")
    )
) {
    override suspend fun execute(args: JsonObject): ToolResult {
        val processId = args["process_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'process_id' parameter")
        val maxChars = args["max_output_chars"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 1_000_000) ?: 100_000
        return BackgroundProcessManager.readOutput(processId, maxChars)
            ?: ToolResult.Error("Process not found: $processId")
    }
}
