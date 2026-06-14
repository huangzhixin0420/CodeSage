package com.codesage.agent.core

import kotlinx.serialization.json.*

/**
 * 6.10.1 delegate_task 结构化结果格式化器
 *
 * 把子 Agent 的自然语言最终 turn 解析为结构化 JSON，便于父 Agent 消费。
 */
object SubAgentResultFormatter {

    data class ParsedFinalTurn(
        val result: String,
        val files: List<String>,
        val blockers: String
    )

    /**
     * 将 [SubAgentResult] 转换为返回给父 LLM 的 JSON 对象。
     */
    fun toJson(result: SubAgentResult, sessionId: String): JsonObject {
        val parsed = parseFinalTurn(result.output)
        return buildJsonObject {
            put("success", result.success)
            put("cancelled", result.cancelled)
            put("result", parsed.result)
            put("files", JsonArray(parsed.files.map { JsonPrimitive(it) }))
            put("blockers", parsed.blockers)
            put("iterations_used", result.iterationsUsed)
            put("tools_used", JsonArray(result.toolsUsed.map { JsonPrimitive(it) }))
            put(
                "completed_tool_calls",
                JsonArray(
                    result.completedToolCalls.map { tc ->
                        buildJsonObject {
                            put("name", tc.name)
                            put("arg_summary", tc.argSummary)
                            put("result_length", tc.resultLength)
                            put("success", tc.success)
                        }
                    }
                )
            )
            put("session_id", sessionId)
            put("raw_output", result.output.take(2000))
            put("worktree_diff", result.worktreeDiff ?: "")
            put("worktree_changes", result.worktreeChanges ?: buildJsonObject { })
        }
    }

    /**
     * 解析子 Agent 最终 turn 中的结构化字段。
     *
     * 期望格式（来自 buildSubAgentPrompt 的 Final-Turn Output Contract）：
     * ```
     * **Result**: <summary>
     * **Files**: <file1>, <file2>
     * **Blockers**: <issues or none>
     * ```
     *
     * 解析失败时整段文本作为 [result]，files/blockers 为空。
     */
    fun parseFinalTurn(output: String): ParsedFinalTurn {
        val resultBuilder = StringBuilder()
        val files = mutableListOf<String>()
        var blockers = ""
        var parsedAny = false

        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("**Result**:") -> {
                    parsedAny = true
                    resultBuilder.appendLine(line.substringAfter("**Result**:").trim())
                }

                line.startsWith("**Files**:") -> {
                    parsedAny = true
                    val filesStr = line.substringAfter("**Files**:").trim()
                    filesStr.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() && it != "none" }
                        .forEach { files.add(it) }
                }

                line.startsWith("**Blockers**:") -> {
                    parsedAny = true
                    blockers = line.substringAfter("**Blockers**:").trim()
                }
            }
        }

        return if (parsedAny) {
            ParsedFinalTurn(
                result = resultBuilder.toString().trim(),
                files = files,
                blockers = blockers
            )
        } else {
            ParsedFinalTurn(
                result = output.trim(),
                files = emptyList(),
                blockers = ""
            )
        }
    }
}
