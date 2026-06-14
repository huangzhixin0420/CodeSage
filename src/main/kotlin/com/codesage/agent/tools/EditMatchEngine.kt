package com.codesage.agent.tools

/**
 * P1 6.2.3：编辑工具匹配引擎。
 *
 * 负责在文件内容中定位 `old_string` 的替换区域，支持：
 * - 精确匹配：保留原有 `edit_file` 的 substring 语义（可匹配行内片段）
 * - `fuzzy_match = true` 时忽略行首/行尾空白差异
 * - 当 `old_string` 不唯一时，自动用前后最多 2 行上下文去歧
 * - 去歧失败时返回所有候选位置（行号 + 片段），帮助模型修正
 *
 * 纯函数、无 IO，便于单元测试。
 */
internal object EditMatchEngine {

    private const val DEFAULT_CONTEXT_LINES = 2

    /**
     * 匹配结果。
     */
    sealed class FindResult {
        /**
         * 唯一匹配，可安全替换。
         */
        data class Unique(val match: MatchCandidate) : FindResult()

        /**
         * 未找到。
         */
        data object NotFound : FindResult()

        /**
         * 多个候选，无法确定替换哪一处。
         */
        data class Ambiguous(val candidates: List<MatchCandidate>) : FindResult()
    }

    /**
     * 候选匹配。
     *
     * @param start 匹配区域在内容中的起始索引（inclusive）
     * @param endExclusive 匹配区域在内容中的结束索引（exclusive）
     * @param lineNumber 匹配区域首行的 1-based 行号
     * @param matchedText 实际匹配到的原始文本（含空白）
     * @param contextSnippet 用于展示/去歧的上下文片段
     */
    data class MatchCandidate(
        val start: Int,
        val endExclusive: Int,
        val lineNumber: Int,
        val matchedText: String,
        val contextSnippet: String
    )

    /**
     * 在 [content] 中查找 [oldString] 的唯一替换区域。
     *
     * @param fuzzy 是否忽略行首/行尾空白差异
     */
    fun findReplacementRegion(
        content: String,
        oldString: String,
        fuzzy: Boolean = false
    ): FindResult {
        if (oldString.isEmpty()) return FindResult.NotFound

        val candidates = if (fuzzy) {
            findFuzzyCandidates(content, oldString)
        } else {
            findExactCandidates(content, oldString)
        }

        if (candidates.isEmpty()) return FindResult.NotFound
        if (candidates.size == 1) return FindResult.Unique(candidates.first())

        // 精确匹配且未开启 fuzzy 时保持旧行为：直接报 Ambiguous。
        if (!fuzzy) return FindResult.Ambiguous(candidates)

        // 多个匹配：尝试用上下文去歧。
        // 一个候选被认定“可去歧”需同时满足：
        // 1. 扩展后的上下文在整份内容中只出现一次。
        // 2. 该上下文内部只包含一次 old_string，避免把包含多个同名片段的整块内容误判为唯一。
        val disambiguated = candidates.filter { candidate ->
            val contextualQuery = expandContext(content, candidate)
            countExact(content, contextualQuery) == 1 &&
                    countExact(contextualQuery, oldString) == 1
        }

        return if (disambiguated.isNotEmpty()) {
            FindResult.Unique(disambiguated.first())
        } else {
            FindResult.Ambiguous(candidates)
        }
    }

    /**
     * 生成供错误消息使用的候选位置描述。
     */
    fun formatAmbiguousMessage(candidates: List<MatchCandidate>): String {
        val lines = candidates.map { "line ${it.lineNumber}: `${truncate(it.matchedText, 80)}`" }
        return "old_string matches ${candidates.size} locations; provide more context:\n${lines.joinToString("\n")}"
    }

    /**
     * 将 [content] 中 [candidate] 区域替换为 [newString]。
     */
    fun applyReplacement(content: String, candidate: MatchCandidate, newString: String): String {
        return content.replaceRange(candidate.start, candidate.endExclusive, newString)
    }

    /**
     * 精确 substring 匹配（兼容旧 edit_file 行为）。
     */
    private fun findExactCandidates(content: String, oldString: String): List<MatchCandidate> {
        val candidates = mutableListOf<MatchCandidate>()
        var fromIndex = 0
        while (true) {
            val idx = content.indexOf(oldString, fromIndex)
            if (idx < 0) break
            candidates.add(buildCandidate(content, idx, oldString.length))
            fromIndex = idx + 1
        }
        return candidates
    }

    /**
     * 模糊匹配：按行窗口比较，忽略每行首/尾空白。
     */
    private fun findFuzzyCandidates(content: String, oldString: String): List<MatchCandidate> {
        val contentLines = content.split("\n")
        val oldLines = oldString.split("\n")
        if (oldLines.isEmpty() || oldLines.size > contentLines.size) return emptyList()

        val normalizedOld = oldLines.map { it.trimStart().trimEnd() }
        val candidates = mutableListOf<MatchCandidate>()

        for (i in 0..contentLines.size - oldLines.size) {
            val window = contentLines.subList(i, i + oldLines.size)
            val normalizedWindow = window.map { it.trimStart().trimEnd() }
            if (normalizedWindow == normalizedOld) {
                val start = contentLines.take(i).sumOf { it.length + 1 }
                val raw = window.joinToString("\n")
                candidates.add(buildCandidate(content, start, raw.length, raw))
            }
        }
        return candidates
    }

    private fun buildCandidate(
        content: String,
        start: Int,
        length: Int,
        matchedText: String? = null
    ): MatchCandidate {
        val raw = matchedText ?: content.substring(start, start + length)
        val lineNumber = content.substring(0, start).count { it == '\n' } + 1
        return MatchCandidate(
            start = start,
            endExclusive = start + length,
            lineNumber = lineNumber,
            matchedText = raw,
            contextSnippet = expandContext(content, lineNumber, raw.count { it == '\n' } + 1)
        )
    }

    private fun expandContext(content: String, candidate: MatchCandidate): String {
        return expandContext(content, candidate.lineNumber, candidate.matchedText.count { it == '\n' } + 1)
    }

    private fun expandContext(
        content: String,
        matchStartLine: Int,
        matchLineCount: Int
    ): String {
        val lines = content.split("\n")
        val start = (matchStartLine - 1 - DEFAULT_CONTEXT_LINES).coerceAtLeast(0)
        val end = (matchStartLine - 1 + matchLineCount + DEFAULT_CONTEXT_LINES).coerceAtMost(lines.size)
        return lines.subList(start, end).joinToString("\n")
    }

    private fun countExact(content: String, query: String): Int {
        if (query.isEmpty()) return 0
        var count = 0
        var fromIndex = 0
        while (true) {
            val idx = content.indexOf(query, fromIndex)
            if (idx < 0) break
            count++
            fromIndex = idx + 1
        }
        return count
    }

    private fun truncate(text: String, maxLength: Int): String {
        val normalized = text.replace("\n", "\\n")
        return if (normalized.length > maxLength) normalized.take(maxLength) + "..." else normalized
    }
}
