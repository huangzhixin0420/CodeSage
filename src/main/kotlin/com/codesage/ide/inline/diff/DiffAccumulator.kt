package com.codesage.ide.inline.diff

/**
 * Diff 累积器
 *
 * 在 AI 流式生成过程中，实时解析文本中的代码块，计算 Diff，驱动增量渲染。
 */
class DiffAccumulator(private val originalCode: String) {

    private val buffer = StringBuilder()
    private var codeBlockExtracted = false
    private var lastDiffResult: DiffResult = DiffResult.EMPTY

    /**
     * 追加新的文本片段（流式输出）
     *
     * @return 如果解析到新代码块并计算出 Diff，返回 DiffResult；否则返回 null
     */
    fun append(text: String): DiffResult? {
        buffer.append(text)

        // 尝试提取代码块
        val codeContent = extractCodeBlock(buffer.toString())
        if (codeContent != null) {
            codeBlockExtracted = true
            val newDiff = computeLineDiff(originalCode, codeContent)
            if (newDiff != lastDiffResult) {
                lastDiffResult = newDiff
                return newDiff
            }
        }

        return null
    }

    /**
     * 标记流式输出完成，返回最终 Diff
     */
    fun finalize(): DiffResult {
        val codeContent = extractCodeBlock(buffer.toString())
        return if (codeContent != null) {
            computeLineDiff(originalCode, codeContent)
        } else {
            // 没有代码块标记，将整个 buffer 视为代码
            computeLineDiff(originalCode, buffer.toString())
        }
    }

    /**
     * 获取当前缓冲区内容
     */
    fun getBuffer(): String = buffer.toString()

    /**
     * 是否已提取到代码块
     */
    fun hasExtractedCodeBlock(): Boolean = codeBlockExtracted

    companion object {

        /** 大文件 Diff 行数阈值，超出时使用简化 Diff */
        const val MAX_DIFF_LINES = 500

        /**
         * 从文本中提取 ``` 包裹的代码块
         *
         * 支持多种格式：
         * - ```language\ncode\n```
         * - ```\ncode```
         * - ```language\ncode```
         *
         * 当有多个代码块时，返回最后一个（通常是最终答案）。
         */
        fun extractCodeBlock(text: String): String? {
            val regex = "```(?:\\w+)?\\s*\\n?(.*?)\\n?```".toRegex(RegexOption.DOT_MATCHES_ALL)
            val matches = regex.findAll(text).toList()
            return if (matches.isNotEmpty()) {
                matches.last().groupValues[1].trim()
            } else {
                null
            }
        }

        /**
         * 计算两行文本的行级 Diff（简单 LCS 算法）
         *
         * 对于超过 [MAX_DIFF_LINES] 行的大文件，使用简化 Diff 避免 O(n×m) 性能问题。
         */
        fun computeLineDiff(oldCode: String, newCode: String): DiffResult {
            val oldLines = if (oldCode.isEmpty()) emptyList() else oldCode.lines()
            val newLines = if (newCode.isEmpty()) emptyList() else newCode.lines()

            if (oldLines == newLines) {
                return DiffResult(oldLines.mapIndexed { idx, line ->
                    DiffLine(DiffType.CONTEXT, idx, line)
                })
            }

            // 大文件：超过行数限制时使用简化 Diff
            if (oldLines.size > MAX_DIFF_LINES || newLines.size > MAX_DIFF_LINES) {
                return computeSimplifiedDiff(oldLines, newLines)
            }

            val lcs = computeLCS(oldLines, newLines)
            val result = mutableListOf<DiffLine>()

            var oldIdx = 0
            var newIdx = 0

            for (commonLine in lcs) {
                // 输出 old 中在 commonLine 之前的删除行
                while (oldIdx < oldLines.size && oldLines[oldIdx] != commonLine) {
                    result.add(DiffLine(DiffType.REMOVED, oldIdx, oldLines[oldIdx]))
                    oldIdx++
                }

                // 输出 new 中在 commonLine 之前的增加行
                while (newIdx < newLines.size && newLines[newIdx] != commonLine) {
                    result.add(DiffLine(DiffType.ADDED, newIdx, newLines[newIdx]))
                    newIdx++
                }

                // 输出公共行
                if (oldIdx < oldLines.size && newIdx < newLines.size) {
                    result.add(DiffLine(DiffType.CONTEXT, oldIdx, commonLine))
                    oldIdx++
                    newIdx++
                }
            }

            // 剩余的删除行
            while (oldIdx < oldLines.size) {
                result.add(DiffLine(DiffType.REMOVED, oldIdx, oldLines[oldIdx]))
                oldIdx++
            }

            // 剩余的增加行
            while (newIdx < newLines.size) {
                result.add(DiffLine(DiffType.ADDED, newIdx, newLines[newIdx]))
                newIdx++
            }

            return DiffResult(result)
        }

        /**
         * 简化 Diff：大文件时跳过 LCS，将旧代码标记为 REMOVED，新代码标记为 ADDED。
         * 避免 O(n×m) 的内存和 CPU 开销。
         */
        private fun computeSimplifiedDiff(oldLines: List<String>, newLines: List<String>): DiffResult {
            val result = mutableListOf<DiffLine>()
            oldLines.forEachIndexed { idx, line ->
                result.add(DiffLine(DiffType.REMOVED, idx, line))
            }
            newLines.forEachIndexed { idx, line ->
                result.add(DiffLine(DiffType.ADDED, idx, line))
            }
            return DiffResult(result)
        }

        /**
         * 最长公共子序列（LCS）
         */
        private fun computeLCS(a: List<String>, b: List<String>): List<String> {
            val m = a.size
            val n = b.size
            if (m == 0 || n == 0) return emptyList()

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

            // 回溯
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
}
