package com.codesage.agent.tools

import kotlinx.serialization.json.*

/**
 * 6.6.2 Git unified diff 结构化解析器
 *
 * 把 `git diff` 输出的原始文本解析为文件 / hunk / 行的层级结构，
 * 便于模型直接按文件、行号、增删类型消费差异，而无需自己解析 diff 语法。
 *
 * 支持的 diff 类型：
 * - 修改（modified）
 * - 新增（added）
 * - 删除（deleted）
 * - 重命名（renamed）
 * - 复制（copied）
 * - 二进制（binary）
 *
 * 解析基于统一 diff 标准格式，不依赖外部库。
 */
internal object GitDiffParser {

    /**
     * 解析完整 diff 文本
     */
    fun parse(diffText: String): StructuredDiff {
        if (diffText.isBlank()) {
            return StructuredDiff(emptyList(), 0, 0, false)
        }

        val files = mutableListOf<DiffFile>()
        val lines = diffText.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("diff --git")) {
                val fileResult = parseFile(lines, i)
                files.add(fileResult.value)
                i = fileResult.nextIndex
            } else {
                i++
            }
        }

        val totalAdditions = files.sumOf { it.additions }
        val totalDeletions = files.sumOf { it.deletions }
        return StructuredDiff(
            files = files,
            totalAdditions = totalAdditions,
            totalDeletions = totalDeletions,
            hasChanges = files.isNotEmpty()
        )
    }

    private fun parseFile(lines: List<String>, startIndex: Int): ParseResult<DiffFile> {
        val diffGitLine = lines[startIndex]
        var oldPath: String? = null
        var newPath: String? = null

        val gitPaths = parseDiffGitLine(diffGitLine)
        val oldPathFromGit = gitPaths?.first
        val newPathFromGit = gitPaths?.second

        var changeType = "modified"
        var isBinary = false
        var i = startIndex + 1

        // 解析扩展头部，直到 --- / +++
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("new file mode") -> changeType = "added"
                line.startsWith("deleted file mode") -> changeType = "deleted"
                line.startsWith("similarity index") -> { /* renamed or copied, determined by rename/copy headers */
                }

                line.startsWith("rename from ") -> {
                    changeType = "renamed"
                    oldPath = line.removePrefix("rename from ").cleanQuotedPath()
                }

                line.startsWith("rename to ") -> {
                    changeType = "renamed"
                    newPath = line.removePrefix("rename to ").cleanQuotedPath()
                }

                line.startsWith("copy from ") -> {
                    changeType = "copied"
                    oldPath = line.removePrefix("copy from ").cleanQuotedPath()
                }

                line.startsWith("copy to ") -> {
                    changeType = "copied"
                    newPath = line.removePrefix("copy to ").cleanQuotedPath()
                }

                line.startsWith("Binary files ") -> {
                    isBinary = true
                    changeType = "binary"
                }

                line.startsWith("--- ") -> break
                line.startsWith("diff --git") -> break
            }
            i++
        }

        // 解析 --- / +++ 行（重命名/复制已从扩展头部获得路径，不再覆盖）
        if (changeType !in setOf("renamed", "copied")) {
            if (i < lines.size && lines[i].startsWith("--- ")) {
                val rawOld = lines[i].removePrefix("--- ").cleanQuotedPath()
                oldPath = if (rawOld == "/dev/null") null else (pathFromDiffPrefix(rawOld) ?: oldPathFromGit)
                i++
            } else {
                oldPath = oldPathFromGit
            }
            if (i < lines.size && lines[i].startsWith("+++ ")) {
                val rawNew = lines[i].removePrefix("+++ ").cleanQuotedPath()
                newPath = if (rawNew == "/dev/null") null else (pathFromDiffPrefix(rawNew) ?: newPathFromGit)
                i++
            } else {
                newPath = newPathFromGit
            }
        }

        val hunks = mutableListOf<DiffHunk>()
        var additions = 0
        var deletions = 0

        // 解析 hunk
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("diff --git")) break

            if (line.startsWith("Binary files ")) {
                isBinary = true
                changeType = "binary"
                i++
                continue
            }

            if (line.startsWith("@@")) {
                val hunkResult = parseHunk(lines, i)
                hunks.add(hunkResult.value)
                additions += hunkResult.additions
                deletions += hunkResult.deletions
                i = hunkResult.nextIndex
            } else {
                i++
            }
        }

        // 重命名/复制的二进制判定：如果 hunks 为空且被判定为 renamed/copied，保持原类型
        if (hunks.isEmpty() && changeType !in setOf("renamed", "copied", "binary")) {
            // 没有 hunk 的普通修改通常意味着空 diff，保留 modified
        }

        val file = DiffFile(
            oldPath = oldPath,
            newPath = newPath,
            changeType = changeType,
            hunks = hunks,
            additions = additions,
            deletions = deletions,
            isBinary = isBinary
        )
        return ParseResult(file, i)
    }

    private fun parseHunk(lines: List<String>, startIndex: Int): ParseResultWithCounts<DiffHunk> {
        val headerLine = lines[startIndex]
        val match = HUNK_HEADER_REGEX.matchEntire(headerLine)
            ?: return ParseResultWithCounts(
                DiffHunk(0, 0, 0, 0, headerLine, emptyList()),
                startIndex + 1,
                0,
                0
            )

        val oldStart = match.groupValues[1].toInt()
        val oldLines = match.groupValues[2].toIntOrNull() ?: 1
        val newStart = match.groupValues[3].toInt()
        val newLines = match.groupValues[4].toIntOrNull() ?: 1
        val headerContext = match.groupValues[5]

        var oldLineNumber = oldStart
        var newLineNumber = newStart
        var i = startIndex + 1
        val diffLines = mutableListOf<DiffLine>()
        var additions = 0
        var deletions = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("diff --git") || line.startsWith("@@")) break
            if (line == "\\ No newline at end of file") {
                i++
                continue
            }

            when {
                line.startsWith(" ") || line.isEmpty() -> {
                    // 空行通常是前导空格被截断的 context 行，按 context 处理
                    diffLines.add(
                        DiffLine(
                            type = "context",
                            content = if (line.startsWith(" ")) line.substring(1) else line,
                            oldLineNumber = oldLineNumber,
                            newLineNumber = newLineNumber
                        )
                    )
                    oldLineNumber++
                    newLineNumber++
                }

                line.startsWith("+") -> {
                    diffLines.add(
                        DiffLine(
                            type = "add",
                            content = line.substring(1),
                            oldLineNumber = null,
                            newLineNumber = newLineNumber
                        )
                    )
                    newLineNumber++
                    additions++
                }

                line.startsWith("-") -> {
                    diffLines.add(
                        DiffLine(
                            type = "remove",
                            content = line.substring(1),
                            oldLineNumber = oldLineNumber,
                            newLineNumber = null
                        )
                    )
                    oldLineNumber++
                    deletions++
                }

                else -> {
                    // 非 diff 行（理论上不应出现），作为上下文吞掉
                }
            }
            i++
        }

        val hunk = DiffHunk(
            oldStart = oldStart,
            oldLines = oldLines,
            newStart = newStart,
            newLines = newLines,
            header = headerContext,
            lines = diffLines
        )
        return ParseResultWithCounts(hunk, i, additions, deletions)
    }

    private fun parseDiffGitLine(line: String): Pair<String, String>? {
        val match = DIFF_GIT_REGEX.matchEntire(line) ?: return null
        val oldPath = match.groupValues[1].cleanQuotedPath().removePrefix("a/")
        val newPath = match.groupValues[2].cleanQuotedPath().removePrefix("b/")
        return oldPath to newPath
    }

    private fun pathFromDiffPrefix(raw: String): String? {
        if (raw == "/dev/null") return null
        return raw.removePrefix("a/").removePrefix("b/")
    }

    private fun String.cleanQuotedPath(): String {
        return removeSurrounding("\"").replace("\\\"", "\"")
    }

    private val DIFF_GIT_REGEX = Regex("""^diff --git "?a/(.+?)"? "?b/(.+?)"?\s*$""")
    private val HUNK_HEADER_REGEX = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$""")

    // ========== 数据模型 ==========

    data class StructuredDiff(
        val files: List<DiffFile>,
        val totalAdditions: Int,
        val totalDeletions: Int,
        val hasChanges: Boolean
    ) {
        fun toJson(): JsonObject = JsonObject(
            mapOf(
                "files" to JsonArray(files.map { it.toJson() }),
                "total_additions" to JsonPrimitive(totalAdditions),
                "total_deletions" to JsonPrimitive(totalDeletions),
                "total_changes" to JsonPrimitive(totalAdditions + totalDeletions),
                "has_changes" to JsonPrimitive(hasChanges)
            )
        )
    }

    data class DiffFile(
        val oldPath: String?,
        val newPath: String?,
        val changeType: String,
        val hunks: List<DiffHunk>,
        val additions: Int,
        val deletions: Int,
        val isBinary: Boolean
    ) {
        fun toJson(): JsonObject = JsonObject(
            mapOf(
                "old_path" to JsonPrimitive(oldPath ?: ""),
                "new_path" to JsonPrimitive(newPath ?: ""),
                "change_type" to JsonPrimitive(changeType),
                "is_binary" to JsonPrimitive(isBinary),
                "additions" to JsonPrimitive(additions),
                "deletions" to JsonPrimitive(deletions),
                "hunks" to JsonArray(hunks.map { it.toJson() })
            )
        )
    }

    data class DiffHunk(
        val oldStart: Int,
        val oldLines: Int,
        val newStart: Int,
        val newLines: Int,
        val header: String,
        val lines: List<DiffLine>
    ) {
        fun toJson(): JsonObject = JsonObject(
            mapOf(
                "old_start" to JsonPrimitive(oldStart),
                "old_lines" to JsonPrimitive(oldLines),
                "new_start" to JsonPrimitive(newStart),
                "new_lines" to JsonPrimitive(newLines),
                "header" to JsonPrimitive(header),
                "lines" to JsonArray(lines.map { it.toJson() })
            )
        )
    }

    data class DiffLine(
        val type: String,
        val content: String,
        val oldLineNumber: Int?,
        val newLineNumber: Int?
    ) {
        fun toJson(): JsonObject = JsonObject(
            mapOf(
                "type" to JsonPrimitive(type),
                "content" to JsonPrimitive(content),
                "old_line_number" to JsonPrimitive(oldLineNumber ?: -1),
                "new_line_number" to JsonPrimitive(newLineNumber ?: -1)
            )
        )
    }

    private data class ParseResult<T>(val value: T, val nextIndex: Int)
    private data class ParseResultWithCounts<T>(
        val value: T,
        val nextIndex: Int,
        val additions: Int,
        val deletions: Int
    )
}
