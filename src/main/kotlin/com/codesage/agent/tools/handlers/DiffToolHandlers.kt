package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.*
import com.codesage.model.dto.Tool
import kotlinx.serialization.json.*
import java.io.File

/**
 * Diff 工具 Handler
 */
object DiffToolHandlers {

    fun createDiffFilesHandler(): ToolHandler = FunctionalToolHandler(diffFilesTool()) { args ->
        val source = args["source"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'source' parameter")
        val target = args["target"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'target' parameter")
        val isPaths = args["is_paths"]?.jsonPrimitive?.booleanOrNull ?: true
        val contextLines = args["context_lines"]?.jsonPrimitive?.intOrNull ?: 3

        val sourceText = if (isPaths) {
            val file = File(source)
            if (!file.exists()) return@FunctionalToolHandler ToolResult.Error("Source file not found: $source")
            file.readText(Charsets.UTF_8)
        } else source

        val targetText = if (isPaths) {
            val file = File(target)
            if (!file.exists()) return@FunctionalToolHandler ToolResult.Error("Target file not found: $target")
            file.readText(Charsets.UTF_8)
        } else target

        val diffLines = computeUnifiedDiff(
            sourceText.lines(),
            targetText.lines(),
            if (isPaths) source else "source",
            if (isPaths) target else "target",
            contextLines
        )

        ToolResult.Success(
            JsonObject(
                mapOf(
                    "diff" to JsonPrimitive(diffLines.joinToString("\n")),
                    "has_differences" to JsonPrimitive(diffLines.any { it.startsWith("+") || it.startsWith("-") }),
                    "source_lines" to JsonPrimitive(sourceText.lines().size),
                    "target_lines" to JsonPrimitive(targetText.lines().size)
                )
            )
        )
    }

    /**
     * 简化版统一 diff 算法（Myers diff 的简化实现）
     */
    private fun computeUnifiedDiff(
        oldLines: List<String>,
        newLines: List<String>,
        oldLabel: String,
        newLabel: String,
        context: Int
    ): List<String> {
        if (oldLines == newLines) {
            return listOf("--- $oldLabel", "+++ $newLabel", "No differences")
        }

        val result = mutableListOf<String>()
        result.add("--- $oldLabel")
        result.add("+++ $newLabel")

        val lcs = computeLCS(oldLines, newLines)
        var oldIndex = 0
        var newIndex = 0
        var lcsIndex = 0

        val hunks = mutableListOf<Triple<Int, Int, List<String>>>()
        var currentHunk = mutableListOf<String>()
        var hunkOldStart = 0
        var hunkNewStart = 0

        fun flushHunk() {
            if (currentHunk.isNotEmpty()) {
                hunks.add(Triple(hunkOldStart, hunkNewStart, currentHunk.toList()))
                currentHunk.clear()
            }
        }

        while (oldIndex < oldLines.size || newIndex < newLines.size) {
            if (lcsIndex < lcs.size && oldIndex < oldLines.size && oldLines[oldIndex] == lcs[lcsIndex]
                && newIndex < newLines.size && newLines[newIndex] == lcs[lcsIndex]
            ) {
                currentHunk.add(" ${oldLines[oldIndex]}")
                oldIndex++
                newIndex++
                lcsIndex++
            } else if (lcsIndex < lcs.size && oldIndex < oldLines.size && oldLines[oldIndex] == lcs[lcsIndex]) {
                currentHunk.add("-${oldLines[oldIndex]}")
                oldIndex++
            } else if (lcsIndex < lcs.size && newIndex < newLines.size && newLines[newIndex] == lcs[lcsIndex]) {
                currentHunk.add("+${newLines[newIndex]}")
                newIndex++
            } else {
                if (oldIndex < oldLines.size) {
                    currentHunk.add("-${oldLines[oldIndex]}")
                    oldIndex++
                }
                if (newIndex < newLines.size) {
                    currentHunk.add("+${newLines[newIndex]}")
                    newIndex++
                }
            }
        }

        flushHunk()

        // 输出所有 hunk
        hunks.forEach { (oStart, nStart, lines) ->
            val oldCount = lines.count { !it.startsWith("+") }
            val newCount = lines.count { !it.startsWith("-") }
            result.add("@@ -${oStart + 1},$oldCount +${nStart + 1},$newCount @@")
            result.addAll(lines)
        }

        return result
    }

    private fun computeLCS(a: List<String>, b: List<String>): List<String> {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val lcs = mutableListOf<String>()
        var i = m
        var j = n
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] -> {
                    lcs.add(0, a[i - 1])
                    i--
                    j--
                }

                dp[i - 1][j] > dp[i][j - 1] -> i--
                else -> j--
            }
        }
        return lcs
    }
}
