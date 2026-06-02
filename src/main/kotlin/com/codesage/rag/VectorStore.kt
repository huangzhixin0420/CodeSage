package com.codesage.rag

import kotlinx.serialization.Serializable

/**
 * T3.1 修复：VectorStore 接口
 *
 * 抽象的向量存储接口。T3.2 实现 SqliteVectorStore（基于 SQLite + 内存余弦相似度）。
 * 未来可替换为 sqlite-vec / hnswlib / Pinecone 等。
 *
 * 设计要点：
 * - 纯数据类 Chunk + SearchResult，无 IDE 上下文依赖
 * - 接口与实现解耦，单元测试可使用假实现
 * - embedding 以 FloatArray 传入，不限制具体 embedding model
 */
interface VectorStore {

    /**
     * 文档块：项目代码的语义单元（一个类、一个方法、一段上下文等）
     *
     * @property id 唯一标识（通常是 filePath + symbolName + chunkIndex）
     * @property embedding 由外部 embedding model 计算的向量
     */
    @Serializable
    data class Chunk(
        val id: String,
        val filePath: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
        val symbolKind: String = "GENERAL",  // CLASS / METHOD / FIELD / FILE
        val symbolName: String? = null,
        val embedding: FloatArray = FloatArray(0)
    ) {
        // FloatArray 在 data class 中 equals/hashCode 不会比较内容
        // 重写以便测试时能用 == 比较
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Chunk) return false
            return id == other.id &&
                    filePath == other.filePath &&
                    startLine == other.startLine &&
                    endLine == other.endLine &&
                    content == other.content &&
                    symbolKind == other.symbolKind &&
                    symbolName == other.symbolName &&
                    embedding.contentEquals(other.embedding)
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + filePath.hashCode()
            result = 31 * result + startLine
            result = 31 * result + endLine
            result = 31 * result + content.hashCode()
            result = 31 * result + symbolKind.hashCode()
            result = 31 * result + (symbolName?.hashCode() ?: 0)
            result = 31 * result + embedding.contentHashCode()
            return result
        }
    }

    /**
     * 检索结果
     */
    @Serializable
    data class SearchResult(
        val chunk: Chunk,
        val score: Double  // 余弦相似度（0~1，1 表示完全相似）
    )

    /**
     * 添加/更新 chunks
     */
    suspend fun upsert(chunks: List<Chunk>)

    /**
     * 按向量相似度检索
     */
    suspend fun search(query: FloatArray, topK: Int = 5, minScore: Double = 0.0): List<SearchResult>

    /**
     * 按 id 精确查询
     */
    suspend fun getById(id: String): Chunk?

    /**
     * 按 filePath 删除该文件的所有 chunks（文件改动时增量索引用）
     */
    suspend fun deleteByFilePath(filePath: String)

    /**
     * 清空所有 chunks
     */
    suspend fun clear()

    /**
     * 统计 chunk 数量
     */
    suspend fun count(): Long

    /**
     * 关闭并释放资源
     */
    fun close()
}
