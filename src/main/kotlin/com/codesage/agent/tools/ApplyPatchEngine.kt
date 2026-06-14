package com.codesage.agent.tools

/**
 * P0 优化 6.2.1：Codex 风格结构化 patch 解析与应用引擎。
 *
 * 设计目标：
 * - 纯函数、无 IO，便于单元测试。
 * - 先解析再应用，任一阶段失败都不写盘，避免半成品。
 * - 支持 Update / Add / Delete 三种文件操作。
 *
 * Patch 格式示例：
 * ```
 * *** Begin Patch
 * *** Update File: src/Foo.kt
 * @@ class Foo {
 * -    val x = 1
 * +    val x = 2
 * *** Add File: src/Bar.kt
 * package demo
 *
 * class Bar
 * *** Delete File: src/Old.kt
 * *** End Patch
 * ```
 */
object ApplyPatchEngine {

    /**
     * 单次文件操作抽象
     */
    sealed class PatchOperation {
        abstract val path: String

        data class UpdateFile(override val path: String, val hunks: List<Hunk>) : PatchOperation()
        data class AddFile(override val path: String, val content: String) : PatchOperation()
        data class DeleteFile(override val path: String) : PatchOperation()
    }

    /**
     * Hunk：一组上下文 + 删除 + 新增行
     */
    data class Hunk(val lines: List<HunkLine>)

    /**
     * Hunk 中的单行语义
     */
    sealed class HunkLine {
        abstract val text: String

        data class Context(override val text: String) : HunkLine()
        data class Remove(override val text: String) : HunkLine()
        data class Add(override val text: String) : HunkLine()
    }

    /**
     * 解析结果
     */
    sealed class PatchParseResult {
        data class Success(val plan: PatchPlan) : PatchParseResult()
        data class Error(val message: String) : PatchParseResult()
    }

    /**
     * 解析后的完整 patch 计划
     */
    data class PatchPlan(val operations: List<PatchOperation>)

    /**
     * 应用结果
     */
    sealed class PatchApplyResult {
        data class Success(
            val files: Map<String, String>,
            val deletedFiles: List<String>,
        ) : PatchApplyResult()

        data class Error(val message: String) : PatchApplyResult()
    }

    /**
     * 解析 patch 字符串。
     */
    fun parse(patch: String): PatchParseResult {
        val lines = patch.lines()
        var index = 0

        // 跳过开头的空行，直到遇见 *** Begin Patch
        while (index < lines.size && !lines[index].trim().startsWith("*** Begin Patch")) {
            index++
        }
        if (index >= lines.size) {
            return PatchParseResult.Error("Patch must contain '*** Begin Patch' marker")
        }
        index++ // 跳过 Begin Patch

        val operations = mutableListOf<PatchOperation>()
        var builder: OperationBuilder? = null

        fun flush() {
            builder?.build()?.let { operations.add(it) }
            builder = null
        }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            when {
                trimmed.startsWith("*** End Patch") -> {
                    flush()
                    break
                }

                trimmed.startsWith("*** Update File:") -> {
                    flush()
                    val path = line.substringAfter("*** Update File:").trim()
                    if (path.isBlank()) {
                        return PatchParseResult.Error("'*** Update File:' missing path")
                    }
                    builder = UpdateFileBuilder(path)
                }

                trimmed.startsWith("*** Add File:") -> {
                    flush()
                    val path = line.substringAfter("*** Add File:").trim()
                    if (path.isBlank()) {
                        return PatchParseResult.Error("'*** Add File:' missing path")
                    }
                    builder = AddFileBuilder(path)
                }

                trimmed.startsWith("*** Delete File:") -> {
                    flush()
                    val path = line.substringAfter("*** Delete File:").trim()
                    if (path.isBlank()) {
                        return PatchParseResult.Error("'*** Delete File:' missing path")
                    }
                    builder = DeleteFileBuilder(path)
                }

                builder == null -> {
                    return PatchParseResult.Error("Unexpected line before patch action: $line")
                }

                else -> builder!!.addLine(line)
            }
            index++
        }
        flush()

        if (operations.isEmpty()) {
            return PatchParseResult.Error("Patch contains no file operations")
        }
        return PatchParseResult.Success(PatchPlan(operations))
    }

    /**
     * 将解析后的 patch 应用到原始内容映射上。
     *
     * @param originals 每个 UpdateFile 操作对应的原始内容；AddFile 对应空字符串即可。
     */
    fun apply(plan: PatchPlan, originals: Map<String, String>): PatchApplyResult {
        val result = mutableMapOf<String, String>()
        val deleted = mutableListOf<String>()

        for (op in plan.operations) {
            when (op) {
                is PatchOperation.UpdateFile -> {
                    val original = originals[op.path]
                        ?: return PatchApplyResult.Error("Original content missing for ${op.path}")
                    val current = result[op.path] ?: original
                    val lines = applyHunks(current.lines(), op.hunks)
                        ?: return PatchApplyResult.Error("Failed to apply patch to ${op.path}")
                    result[op.path] = lines.joinToString("\n")
                }

                is PatchOperation.AddFile -> {
                    result[op.path] = op.content
                }

                is PatchOperation.DeleteFile -> {
                    result.remove(op.path)
                    deleted.add(op.path)
                }
            }
        }
        return PatchApplyResult.Success(result, deleted)
    }

    private fun applyHunks(fileLines: List<String>, hunks: List<Hunk>): List<String>? {
        var lines = fileLines
        for (hunk in hunks) {
            val applied = applyHunk(lines, hunk) ?: return null
            lines = applied
        }
        return lines
    }

    private fun applyHunk(fileLines: List<String>, hunk: Hunk): List<String>? {
        val entries = hunk.lines
        if (entries.isEmpty()) return fileLines

        val matchEntries = entries.filterNot { it is HunkLine.Add }
        val matchCount = matchEntries.size
        if (matchCount == 0) {
            // 只有新增行时无法定位插入点；保守失败，提示补充上下文。
            return null
        }

        val candidates = mutableListOf<Int>()
        for (start in 0..fileLines.size - matchCount) {
            var filePos = start
            var ok = true
            for (entry in entries) {
                if (entry is HunkLine.Add) continue
                if (fileLines.getOrNull(filePos) != entry.text) {
                    ok = false
                    break
                }
                filePos++
            }
            if (ok) candidates.add(start)
        }

        when {
            candidates.isEmpty() -> return null
            candidates.size > 1 -> return null
        }

        val start = candidates.single()
        var filePos = start
        val replacement = mutableListOf<String>()
        for (entry in entries) {
            when (entry) {
                is HunkLine.Context -> {
                    replacement.add(entry.text)
                    filePos++
                }

                is HunkLine.Remove -> {
                    filePos++
                }

                is HunkLine.Add -> {
                    replacement.add(entry.text)
                }
            }
        }
        return fileLines.subList(0, start) + replacement + fileLines.subList(filePos, fileLines.size)
    }

    private interface OperationBuilder {
        fun addLine(line: String)
        fun build(): PatchOperation
    }

    private class UpdateFileBuilder(private val path: String) : OperationBuilder {
        private val hunks = mutableListOf<Hunk>()
        private var currentHunk: HunkBuilder? = null

        override fun addLine(line: String) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("@@")) {
                // 开启新 hunk，@@ 后的文本作为上下文锚点
                currentHunk?.let { hunks.add(it.build()) }
                val anchorText = trimmed.removePrefix("@@").let { tail ->
                    when {
                        tail.startsWith(" ") -> tail.drop(1)
                        tail.startsWith("\t") -> tail.drop(1)
                        else -> tail
                    }
                }
                currentHunk = HunkBuilder().apply { addContext(anchorText) }
            } else {
                currentHunk?.addLine(line)
                    ?: throw IllegalStateException("Line outside hunk in Update File: $path")
            }
        }

        override fun build(): PatchOperation {
            currentHunk?.let { hunks.add(it.build()) }
            return PatchOperation.UpdateFile(path, hunks)
        }
    }

    private class AddFileBuilder(private val path: String) : OperationBuilder {
        private val lines = mutableListOf<String>()

        override fun addLine(line: String) {
            lines.add(line)
        }

        override fun build(): PatchOperation {
            val content = if (lines.all { it.isBlank() || it.startsWith("+") }) {
                // 兼容所有行以 '+' 开头的 diff 风格新增内容
                lines.joinToString("\n") { stripDiffPrefix(it, '+') }
            } else {
                lines.joinToString("\n")
            }
            return PatchOperation.AddFile(path, content)
        }
    }

    private class DeleteFileBuilder(private val path: String) : OperationBuilder {
        override fun addLine(line: String) {
            // Delete 操作忽略内容行
        }

        override fun build(): PatchOperation = PatchOperation.DeleteFile(path)
    }

    private class HunkBuilder {
        private val lines = mutableListOf<HunkLine>()

        fun addContext(text: String) {
            lines.add(HunkLine.Context(text))
        }

        fun addLine(line: String) {
            lines.add(
                when {
                    line.startsWith("+") -> HunkLine.Add(stripDiffPrefix(line, '+'))
                    line.startsWith("-") -> HunkLine.Remove(stripDiffPrefix(line, '-'))
                    else -> HunkLine.Context(line)
                }
            )
        }

        fun build(): Hunk = Hunk(lines)
    }

    private fun stripDiffPrefix(line: String, prefix: Char): String {
        // diff 行标记只有行首一个字符（- / +），后面的所有字符都是原始内容，
        // 包括前导空格，因此只去掉第一个字符。
        return if (line.isNotEmpty() && line[0] == prefix) line.substring(1) else line
    }
}
