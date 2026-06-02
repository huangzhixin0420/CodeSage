package com.codesage.rag.sqlite

import com.codesage.rag.VectorStore
import com.codesage.rag.VectorStore.Chunk
import com.codesage.rag.VectorStore.SearchResult
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager

/**
 * T3.2 修复：SQLite 向量存储实现
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T3.2）：
 * - [x] 单元测试：1000 个 chunk 的插入 + 检索 < 200ms
 * - [x] 单元测试：删除操作正确清理
 *
 * 实现细节：
 * - 表 chunks(id PK, file_path, start_line, end_line, content, symbol_kind, symbol_name, embedding BLOB)
 * - 检索：暴力扫描所有 chunk，在内存中计算余弦相似度，排序后返回 topK
 * - 不引入新依赖（项目已有 sqlite-jdbc 3.45.1.0）
 * - embedding 以 BLOB 存储（Float32 little-endian）
 *
 * 性能边界：
 * - 1000 chunk 暴力扫描 ~10ms（毫秒级）
 * - 10k chunk ~100ms
 * - 100k+ chunk 需用 sqlite-vec 或 hnswlib
 *
 * 余弦相似度：
 *   sim(a, b) = dot(a, b) / (||a|| * ||b||)
 *   范围 [-1, 1]，归一化到 [0, 1] 输出：(sim + 1) / 2
 */
class SqliteVectorStore(
    dbPath: String = "${System.getProperty("user.home")}/.codesage/vectors.db"
) : VectorStore {

    private val logger = Logger.getLogger<SqliteVectorStore>()

    private val connection: Connection

    init {
        File(dbPath).parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection.autoCommit = true
        createTable()
    }

    private fun createTable() {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS chunks (
                    id TEXT PRIMARY KEY,
                    file_path TEXT NOT NULL,
                    start_line INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    symbol_kind TEXT NOT NULL DEFAULT 'GENERAL',
                    symbol_name TEXT,
                    embedding BLOB NOT NULL,
                    indexed_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)
                )
                """.trimIndent()
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunks_file_path ON chunks(file_path)")
        }
    }

    override suspend fun upsert(chunks: List<Chunk>) = withContext(Dispatchers.IO) {
        if (chunks.isEmpty()) return@withContext
        connection.prepareStatement(
            """
            INSERT OR REPLACE INTO chunks (id, file_path, start_line, end_line, content, symbol_kind, symbol_name, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            for (chunk in chunks) {
                stmt.setString(1, chunk.id)
                stmt.setString(2, chunk.filePath)
                stmt.setInt(3, chunk.startLine)
                stmt.setInt(4, chunk.endLine)
                stmt.setString(5, chunk.content)
                stmt.setString(6, chunk.symbolKind)
                stmt.setString(7, chunk.symbolName)
                stmt.setBytes(8, floatArrayToBytes(chunk.embedding))
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        Unit
    }

    override suspend fun search(query: FloatArray, topK: Int, minScore: Double): List<VectorStore.SearchResult> =
        withContext(Dispatchers.IO) {
            if (query.isEmpty()) return@withContext emptyList()
            val results = mutableListOf<VectorStore.SearchResult>()
            connection.prepareStatement(
                "SELECT id, file_path, start_line, end_line, content, symbol_kind, symbol_name, embedding FROM chunks"
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val chunk = VectorStore.Chunk(
                            id = rs.getString("id"),
                            filePath = rs.getString("file_path"),
                            startLine = rs.getInt("start_line"),
                            endLine = rs.getInt("end_line"),
                            content = rs.getString("content"),
                            symbolKind = rs.getString("symbol_kind"),
                            symbolName = rs.getString("symbol_name"),
                            embedding = bytesToFloatArray(rs.getBytes("embedding"))
                        )
                        val score = cosineSimilarity(query, chunk.embedding)
                        if (score >= minScore) {
                            results.add(VectorStore.SearchResult(chunk, normalizeScore(score)))
                        }
                    }
                }
            }
            results.sortedByDescending { it.score }.take(topK)
        }

    override suspend fun getById(id: String): VectorStore.Chunk? = withContext(Dispatchers.IO) {
        connection.prepareStatement(
            "SELECT id, file_path, start_line, end_line, content, symbol_kind, symbol_name, embedding FROM chunks WHERE id = ?"
        ).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    VectorStore.Chunk(
                        id = rs.getString("id"),
                        filePath = rs.getString("file_path"),
                        startLine = rs.getInt("start_line"),
                        endLine = rs.getInt("end_line"),
                        content = rs.getString("content"),
                        symbolKind = rs.getString("symbol_kind"),
                        symbolName = rs.getString("symbol_name"),
                        embedding = bytesToFloatArray(rs.getBytes("embedding"))
                    )
                } else null
            }
        }
    }

    override suspend fun deleteByFilePath(filePath: String): Unit = withContext(Dispatchers.IO) {
        connection.prepareStatement("DELETE FROM chunks WHERE file_path = ?").use { stmt ->
            stmt.setString(1, filePath)
            stmt.executeUpdate()
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        connection.createStatement().use { it.execute("DELETE FROM chunks") }
    }

    override suspend fun count(): Long = withContext(Dispatchers.IO) {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM chunks").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    override fun close() {
        try {
            connection.close()
        } catch (e: Exception) {
            logger.warn("Failed to close VectorStore connection: ${e.message}")
        }
    }

    // === 工具方法 ===

    /**
     * 余弦相似度：dot(a, b) / (||a|| * ||b||)
     * 返回值在 [-1, 1]
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val minLen = minOf(a.size, b.size)
        if (minLen == 0) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until minLen) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    /**
     * 将 [-1, 1] 归一化到 [0, 1]，方便阈值比较
     */
    private fun normalizeScore(score: Double): Double = (score + 1.0) / 2.0

    private fun floatArrayToBytes(arr: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in arr) buffer.putFloat(f)
        return buffer.array()
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty()) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(bytes.size / 4)
        for (i in result.indices) result[i] = buffer.getFloat(i * 4)
        return result
    }
}
