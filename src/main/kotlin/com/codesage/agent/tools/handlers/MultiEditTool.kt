package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.IDETools
import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.UnifiedTool
import com.codesage.tools.guardrails.SensitiveActionPolicy
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import kotlinx.serialization.json.JsonObject

/**
 * P1 6.2.2：同一文件多位置原子编辑工具。
 *
 * 相比 `edit_file` 一次只能替换一处，`multi_edit` 接受 `edits: [{old_string, new_string}]`
 * 数组，先批量校验所有 old_string 唯一且存在，再一次性写回文件。
 * 任一校验或应用失败则整体不写入。
 */
class MultiEditTool(private val ideTools: IDETools) : UnifiedTool(
    name = "multi_edit",
    description = """
        Summary: Apply multiple old_string->new_string replacements to a single file atomically.
        Args: path (string, required): file path; edits (array, required): list of {old_string, new_string} objects.
        Do: Use when you need to change several places in the same file in one turn; each old_string must be unique in the file.
        Don't: Don't use for multi-file edits (use apply_patch); don't submit edits whose old_strings overlap or depend on each other's results unless order is intentional.
        Parallel: No, same-file edits must be serialized.
        Cap: All old_strings are validated before any write; if any old_string is missing or non-unique, no changes are persisted.
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty(
                type = "string",
                description = "File path (relative to project root or absolute)"
            ),
            "edits" to ToolProperty(
                type = "array",
                description = "List of edits. Each edit must be an object with 'old_string' and 'new_string' string properties."
            ),
            "fuzzy_match" to ToolProperty(
                type = "boolean",
                description = "Ignore leading/trailing whitespace and use surrounding context to disambiguate non-unique old_string entries."
            )
        ),
        required = listOf("path", "edits")
    ),
    riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS
) {
    override suspend fun execute(args: JsonObject): ToolResult =
        ideTools.multiEdit(args)
}
