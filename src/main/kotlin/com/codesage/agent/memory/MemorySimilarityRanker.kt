package com.codesage.agent.memory

/**
 * 6.9.3 记忆与当前查询的相似度排序器。
 *
 * 优先使用 [MemoryEmbedding] 计算 query 与记忆内容的 embedding 余弦相似度；
 * 当 embedding 不可用或产生零向量时，回退到基于 token set Jaccard overlap 的文本相似度，
 * 避免引入新的 native/ONNX 依赖。
 *
 * @param embed 文本编码函数，默认使用 [MemoryEmbedding]。测试可注入空向量或异常来验证降级路径。
 */
class MemorySimilarityRanker(
    private val embed: (String) -> FloatArray? = { runCatching { MemoryEmbedding.embed(it) }.getOrNull() }
) {

    /** 带相似度分数的记忆记录。 */
    data class ScoredMemory(val record: BuiltInMemoryProvider.MemoryRecord, val score: Float)

    /**
     * 按与 [query] 的相似度对记忆降序排序。
     *
     * 实现会先对 [query] 编码一次，再逐条编码记忆内容并计算余弦相似度。
     * 若 [query] 编码失败或为零向量，则整体回退到 [textOverlapScore]。
     *
     * @param memories 候选记忆列表
     * @param query 当前用户查询文本
     * @return 按相似度分数降序排列的带分记忆
     */
    fun rankBySimilarity(
        memories: List<BuiltInMemoryProvider.MemoryRecord>,
        query: String
    ): List<ScoredMemory> {
        if (memories.isEmpty()) return emptyList()

        val queryVector = embed(query)

        return memories.map { record ->
            val score = queryVector
                ?.takeIf { it.any { value -> value != 0f } }
                ?.let { validQueryVector -> cosineSimilarityScore(validQueryVector, record.content) }
                ?: textOverlapScore(query, record.content)
            ScoredMemory(record, score)
        }.sortedByDescending { it.score }
    }

    /**
     * 计算 [queryVector] 与 [content] 的 embedding 余弦相似度。
     *
     * 若 content 编码失败或产生零向量，回退到文本 overlap，保证排序器始终返回有效分数。
     */
    private fun cosineSimilarityScore(queryVector: FloatArray, content: String): Float {
        return runCatching {
            val contentVector = embed(content)
            if (contentVector == null || contentVector.all { it == 0f }) {
                textOverlapScore(queryVector.joinToString(""), content)
            } else {
                MemoryEmbedding.cosineSimilarity(queryVector, contentVector)
            }
        }.getOrDefault(textOverlapScore(queryVector.joinToString(""), content))
    }

    companion object {

        /**
         * 轻量级文本相似度：基于 token set 的 Jaccard overlap。
         *
         * 不依赖任何向量模型，作为 embedding 不可用时的降级路径。
         * 对英文按空白/标点分词，中文按字符处理，与 [MemoryEmbedding] 的 tokenize 策略保持一致。
         *
         * @param query 查询文本
         * @param content 记忆内容
         * @return [0, 1] 区间的相似度分数
         */
        fun textOverlapScore(query: String, content: String): Float {
            val queryTokens = tokenize(query)
            val contentTokens = tokenize(content)
            if (queryTokens.isEmpty() || contentTokens.isEmpty()) return 0f

            val intersection = queryTokens.intersect(contentTokens).size
            val union = queryTokens.union(contentTokens).size
            return if (union == 0) 0f else intersection.toFloat() / union
        }

        private fun tokenize(text: String): Set<String> {
            return text.lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 1 }
                .flatMap { word ->
                    // 对中文进一步拆成单字，增加召回粒度
                    if (word.any { it.code in 0x4E00..0x9FFF }) {
                        listOf(word) + word.filter { it.code in 0x4E00..0x9FFF }.map { it.toString() }
                    } else {
                        listOf(word)
                    }
                }
                .toSet()
        }
    }
}
