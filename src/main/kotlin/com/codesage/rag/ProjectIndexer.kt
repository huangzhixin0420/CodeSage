package com.codesage.rag

import com.codesage.rag.chunker.DocumentChunker
import com.codesage.rag.chunker.LineBasedChunker
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T3.5 修复：项目索引器
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T3.5）：
 * - [x] 单元测试：启动时 1000 个 Kotlin 文件，索引完成 < 30s
 * - [x] 单元测试：文件修改后 < 1s 内更新索引
 * - [x] 单元测试：删除文件后 chunks 被清理
 *
 * 设计要点：
 * - 监听文件变化（生产中用 PsiTreeChangeListener，测试中模拟）
 * - 增量更新：upsert 整个文件的新 chunks
 * - 删除处理：调用 vectorStore.deleteByFilePath
 * - 默认 chunker：LineBasedChunker（PSI 不可用时 fallback）
 */
class ProjectIndexer(
    private val vectorStore: VectorStore,
    private val chunker: DocumentChunker = LineBasedChunker()
) {
    private val logger = Logger.getLogger<ProjectIndexer>()

    /**
     * 把整个项目的内容映射索引到 vector store
     */
    suspend fun indexAll(fileContents: Map<String, String>) = withContext(Dispatchers.IO) {
        var totalChunks = 0
        for ((filePath, content) in fileContents) {
            val chunks = chunker.chunk(filePath, content)
            vectorStore.upsert(chunks)
            totalChunks += chunks.size
        }
        logger.info("Indexed ${fileContents.size} files, $totalChunks chunks total")
    }

    /**
     * 增量索引单个文件
     */
    suspend fun indexFile(filePath: String, content: String) = withContext(Dispatchers.IO) {
        val chunks = chunker.chunk(filePath, content)
        vectorStore.upsert(chunks)
    }

    /**
     * 从索引中删除文件
     */
    suspend fun removeFile(filePath: String) = withContext(Dispatchers.IO) {
        vectorStore.deleteByFilePath(filePath)
    }
}
