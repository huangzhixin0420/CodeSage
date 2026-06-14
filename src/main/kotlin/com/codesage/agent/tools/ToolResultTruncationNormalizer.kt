package com.codesage.agent.tools

import kotlinx.serialization.json.*

/**
 * 工具结果截断标记归一化器。
 *
 * 6.12.1：各工具 historically 使用不同的截断字段（`truncated`/`original_length`、
 * `stdout_truncated`/`stderr_truncated`、`partial_scan_files` 等）。本对象负责在
 * `ToolExecutor` 层统一转换为 `{truncated, total_items, returned_items, next_offset, hint}`
 * 协议，使模型无需关心底层工具的具体字段差异。
 */
object ToolResultTruncationNormalizer {

    /**
     * 从一次工具执行结果中提取并归一化截断元数据。
     *
     * @param toolName 工具名
     * @param result 工具返回的原始结果（尚未经过 guardrails 截断）
     * @param toolArgs 工具调用参数，用于计算 `next_offset`
     * @return 归一化后的截断元数据；若无截断信息则返回空元数据
     */
    fun extract(toolName: String, result: ToolResult, toolArgs: JsonObject): ToolResultMetadata {
        return when (result) {
            is ToolResult.Success -> extractFromSuccess(toolName, result.data, toolArgs)
            is ToolResult.Error -> ToolResultMetadata.EMPTY
        }
    }

    private fun extractFromSuccess(
        toolName: String,
        data: JsonElement,
        toolArgs: JsonObject
    ): ToolResultMetadata {
        val obj = data as? JsonObject ?: return ToolResultMetadata.EMPTY

        return when (toolName) {
            "read_file" -> extractReadFile(obj, toolArgs)
            "read_multiple_files" -> extractReadMultipleFiles(obj)
            "run_command", "exec_shell" -> extractRunCommand(obj)
            "search_code", "grep_code" -> extractSearch(obj)
            "find_file" -> extractFindFile(obj)
            "list_directory" -> extractListDirectory(obj)
            else -> extractGeneric(obj)
        }
    }

    private fun extractReadFile(obj: JsonObject, toolArgs: JsonObject): ToolResultMetadata {
        val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull == true
                || obj["original_length"] != null
        val totalLines = obj["total_lines"]?.jsonPrimitive?.intOrNull
        val startLine = obj["start_line"]?.jsonPrimitive?.intOrNull
        val endLine = obj["end_line"]?.jsonPrimitive?.intOrNull
        val content = obj["content"]?.jsonPrimitive?.contentOrNull

        val returnedItems = when {
            startLine != null && endLine != null -> (endLine - startLine).coerceAtLeast(0)
            content != null -> content.lines().size.coerceAtLeast(1)
            else -> null
        }

        val nextOffset = when {
            endLine != null && totalLines != null && endLine < totalLines -> endLine
            totalLines != null && returnedItems != null && returnedItems < totalLines -> returnedItems
            else -> null
        }

        val hint = buildHint(truncated, totalLines, returnedItems, nextOffset, "offset/limit")

        return ToolResultMetadata(
            truncated = truncated,
            totalItems = totalLines,
            returnedItems = returnedItems,
            nextOffset = nextOffset,
            hint = hint
        )
    }

    private fun extractReadMultipleFiles(obj: JsonObject): ToolResultMetadata {
        val files = obj["files"]?.jsonArray
        val anyTruncated = files?.any { file ->
            val f = file as? JsonObject ?: return@any false
            f["truncated"]?.jsonPrimitive?.booleanOrNull == true || f["original_length"] != null
        } == true

        val totalFiles = files?.size
        val hint = if (anyTruncated) {
            "Some files were truncated to ${IDETools.MAX_CONTENT_LENGTH} chars each; use read_file with offset/limit for full content."
        } else null

        return ToolResultMetadata(
            truncated = anyTruncated,
            totalItems = totalFiles,
            returnedItems = totalFiles,
            hint = hint
        )
    }

    private fun extractRunCommand(obj: JsonObject): ToolResultMetadata {
        val stdoutTruncated = obj["stdout_truncated"]?.jsonPrimitive?.booleanOrNull == true
        val stderrTruncated = obj["stderr_truncated"]?.jsonPrimitive?.booleanOrNull == true
        val maxOutputChars = obj["max_output_chars"]?.jsonPrimitive?.intOrNull
            ?: IDETools.MAX_COMMAND_OUTPUT_CHARS

        if (!stdoutTruncated && !stderrTruncated) return ToolResultMetadata.EMPTY

        return ToolResultMetadata(
            truncated = true,
            hint = "Command output exceeded ${maxOutputChars} chars and was truncated; " +
                    "use pipes like `| head -n N` or redirect to a file, then read it with read_file."
        )
    }

    private fun extractSearch(obj: JsonObject): ToolResultMetadata {
        val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull == true
        val total = obj["total"]?.jsonPrimitive?.intOrNull
        val maxResults = obj["max_results"]?.jsonPrimitive?.intOrNull
        val partialScan = obj["partial_scan_files"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }

        if (!truncated && partialScan == null) return ToolResultMetadata.EMPTY

        val hintParts = mutableListOf<String>()
        if (truncated) {
            hintParts.add("results capped at max_results=${maxResults}; refine query or increase max_results")
        }
        if (partialScan != null) {
            hintParts.add("$partialScan large file(s) only partially scanned; use read_file with offset/limit for full content")
        }

        return ToolResultMetadata(
            truncated = true,
            totalItems = total,
            returnedItems = total,
            hint = hintParts.joinToString("; ")
        )
    }

    private fun extractFindFile(obj: JsonObject): ToolResultMetadata {
        val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull == true
        val total = obj["total"]?.jsonPrimitive?.intOrNull
        val maxResults = obj["max_results"]?.jsonPrimitive?.intOrNull

        if (!truncated) return ToolResultMetadata.EMPTY

        return ToolResultMetadata(
            truncated = true,
            totalItems = total,
            returnedItems = total,
            hint = "File matches capped at max_results=${maxResults}; refine pattern or increase max_results."
        )
    }

    private fun extractListDirectory(obj: JsonObject): ToolResultMetadata {
        val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull == true
        val entries = obj["entries"]?.jsonArray
        val maxDepth = obj["max_depth"]?.jsonPrimitive?.intOrNull

        if (!truncated) return ToolResultMetadata.EMPTY

        val count = entries?.size
        return ToolResultMetadata(
            truncated = true,
            totalItems = count,
            returnedItems = count,
            hint = "Directory listing truncated at max_depth=${maxDepth}; increase max_depth or list subdirectories directly."
        )
    }

    private fun extractGeneric(obj: JsonObject): ToolResultMetadata {
        val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull == true
        val total = obj["total"]?.jsonPrimitive?.intOrNull
        val totalItems = total ?: obj["total_items"]?.jsonPrimitive?.intOrNull
        val returnedItems = obj["returned_items"]?.jsonPrimitive?.intOrNull
            ?: obj["count"]?.jsonPrimitive?.intOrNull
        val nextOffset = obj["next_offset"]?.jsonPrimitive?.intOrNull

        if (!truncated && totalItems == null && returnedItems == null && nextOffset == null) {
            return ToolResultMetadata.EMPTY
        }

        return ToolResultMetadata(
            truncated = truncated,
            totalItems = totalItems,
            returnedItems = returnedItems,
            nextOffset = nextOffset,
            hint = buildHint(truncated, totalItems, returnedItems, nextOffset, "offset/limit")
        )
    }

    private fun buildHint(
        truncated: Boolean,
        totalItems: Int?,
        returnedItems: Int?,
        nextOffset: Int?,
        protocol: String
    ): String? {
        if (!truncated) return null
        val parts = mutableListOf<String>()
        parts.add("Output was truncated")
        if (totalItems != null) parts.add("total_items=$totalItems")
        if (returnedItems != null) parts.add("returned_items=$returnedItems")
        if (nextOffset != null) parts.add("next_offset=$nextOffset")
        parts.add("use $protocol to continue reading")
        return parts.joinToString(", ") + "."
    }
}
