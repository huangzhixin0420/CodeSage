package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.IDETools
import com.codesage.agent.tools.UnifiedTool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import kotlinx.serialization.json.JsonObject

/**
 * P0 优化 6.2.1：Codex 风格结构化 patch 工具。
 *
 * 相比 `edit_file` 的 `old_string` 单点替换，`apply_patch` 的优势：
 * - 一次调用可修改多个文件。
 * - 使用上下文锚点定位，降低因缩进或局部重复导致的失败率。
 * - 与 `git diff` 语义一致，便于审阅和回滚。
 */
class ApplyPatchTool(private val ideTools: IDETools) : UnifiedTool(
    name = "apply_patch",
    description = """
        Summary: Apply a structured Codex-style patch to one or more files in a single atomic operation.
        Args: patch (string, required): patch text bounded by `*** Begin Patch` / `*** End Patch`; allow_overwrite (boolean): whether Add File may replace an existing file, default false.
        Do: Use for multi-file or multi-location edits; ensure hunk context is unique; verify with tests after applying.
        Don't: Don't use without readable context anchors; don't rely on patch to handle binary files.
        Parallel: No, same-file patches must be serialized.
        Cap: Max 50 hunks per file; parse or apply failures return detailed errors without writing partial changes.
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "patch" to ToolProperty(
                type = "string",
                description = """
                    Codex-style patch. Supported actions:
                    *** Update File: path
                    @@ context anchor
                    - old line
                    + new line
                    *** Add File: path
                    content...
                    *** Delete File: path
                """.trimIndent()
            ),
            "allow_overwrite" to ToolProperty(
                type = "boolean",
                description = "If true, Add File actions can overwrite existing files. Default false."
            )
        ),
        required = listOf("patch")
    )
) {
    override suspend fun execute(args: JsonObject): com.codesage.agent.tools.ToolResult =
        ideTools.applyPatch(args)
}
