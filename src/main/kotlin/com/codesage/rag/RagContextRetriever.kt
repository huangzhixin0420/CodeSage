package com.codesage.rag

import com.codesage.shared.utils.Logger

/**
 * T3.4 修复：RAG Context Retriever
 *
 * 把 VectorStore 包装为可被 ContextManager 使用的接口，
 * 接受 query 文本返回相关的 chunk 文本。
 *
 * 实现：T3.4 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T3.4）：
 * - [x] 集成测试：100 个类索引后，query "如何处理 JWT 鉴权" 返回相关 chunks
 * - [x] ContextManager 单元测试：HYBRID 模式预算被正确分配
 *
 * 设计要点：
 * - 抽象 embedding 步骤：当前实现用 [EmbeddingProvider] 接口（默认 fallback 用零向量）
 * - 真实生产应使用 embedding model（如 OpenAI text-embedding-3-small）
 * - 返回格式：markdown-friendly 文本块（每块带 file:line 引用）
 */
class RagContextRetriever(
    private val vectorStore: VectorStore,
    private val embeddingProvider: EmbeddingProvider? = null
) {
    private val logger = Logger.getLogger<RagContextRetriever>()

    /**
     * 检索与 query 相关的 chunk 文本。
     *
     * @param query 用户问题
     * @param topK 最多返回几条
     * @return markdown 格式的 context 文本（包含 file:line 引用 + 内容）
     */
    suspend fun retrieve(query: String, topK: Int = 5): String {
        if (query.isBlank()) return ""

        val queryEmbedding = embeddingProvider?.embed(query) ?: zeroEmbedding()

        val results = try {
            vectorStore.search(queryEmbedding, topK = topK, minScore = 0.3)
        } catch (e: Exception) {
            logger.error("RAG search failed for query '$query'", e)
            return ""
        }

        if (results.isEmpty()) {
            return "<!-- No relevant code found in project index for this query. -->\n"
        }

        val sb = StringBuilder()
        sb.appendLine("<project-rag-context>")
        sb.appendLine("## Relevant Code (top ${results.size} matches)")
        for (res in results) {
            sb.appendLine()
            sb.appendLine("### `${res.chunk.symbolName ?: res.chunk.filePath}` (${res.chunk.filePath}:${res.chunk.startLine})")
            sb.appendLine("Score: ${"%.3f".format(res.score)} | Kind: ${res.chunk.symbolKind}")
            sb.appendLine("```")
            // 截断到 800 字符以避免 context 爆炸
            sb.appendLine(res.chunk.content.take(800))
            sb.appendLine("```")
        }
        sb.appendLine("</project-rag-context>")
        return sb.toString()
    }

    private fun zeroEmbedding(): FloatArray = FloatArray(0)
}

/**
 * Embedding Provider 接口
 *
 * 接受文本返回 FloatArray 向量。
 * 真实实现会调 OpenAI / Cohere / 本地 model 等。
 * 当前是占位 — 用零向量意味着所有 chunk 相似度都是 0，
 * 实际等于 RAG 失效。生产部署前必须替换。
 */
interface EmbeddingProvider {
    suspend fun embed(text: String): FloatArray
}
