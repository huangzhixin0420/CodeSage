package com.codesage.agent.memory

import kotlin.math.sqrt

/**
 * 6.9.1 向量记忆：本地轻量级 embedding。
 *
 * 不依赖外部 embedding 服务，使用确定性词袋哈希生成固定维度向量，
 * 使语义相关（共享关键词）的记忆在余弦相似度上接近。
 *
 * 设计取舍：
 * - 维度 128，每条记忆约 512 字节（float array）。
 * - 对英文按空白/标点分词，中文按字符处理，兼顾本仓库双语场景。
 * - 向量归一化，cosine similarity 即点积。
 */
object MemoryEmbedding {

    /** 向量维度 */
    const val DIMENSION: Int = 128

    /**
     * 将文本编码为归一化浮点向量。
     */
    fun embed(text: String): FloatArray {
        val vector = FloatArray(DIMENSION) { 0f }
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return vector

        for (token in tokens) {
            // 用多个 hash 种子把 token 映射到不同维度，降低碰撞
            val h1 = token.hashCode()
            val h2 = h1 * 31 + token.length
            val idx1 = Math.floorMod(h1, DIMENSION)
            val idx2 = Math.floorMod(h2, DIMENSION)
            val weight = 1.0f + 0.2f * token.length.coerceAtMost(10)
            vector[idx1] += weight
            vector[idx2] += weight * 0.5f
        }

        return normalize(vector)
    }

    /**
     * 计算两个归一化向量的余弦相似度。
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == DIMENSION && b.size == DIMENSION)
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return dot.coerceIn(-1f, 1f)
    }

    private fun tokenize(text: String): List<String> {
        return text
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 }
            .flatMap { word ->
                // 对中文进一步拆成单字，增加语义召回粒度
                if (word.any { it.code in 0x4E00..0x9FFF }) {
                    listOf(word) + word.filter { it.code in 0x4E00..0x9FFF }.map { it.toString() }
                } else {
                    listOf(word)
                }
            }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (v in vector) sum += v * v
        if (sum == 0f) return vector
        val norm = sqrt(sum.toDouble()).toFloat()
        return FloatArray(vector.size) { i -> vector[i] / norm }
    }
}
