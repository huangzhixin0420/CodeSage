package com.codesage.analysis

import com.codesage.agent.memory.EmbeddingMath
import com.codesage.shared.utils.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * 6.3.3 语义搜索向量索引（项目级 SQLite）。
 *
 * 存储结构：
 * - `semantic_chunks(id, file_path, start_line, end_line, content, symbol_name, symbol_type, updated_at)`
 * - `semantic_embeddings(chunk_id PRIMARY KEY, vector BLOB)`
 *
 * 采用 BLOB + 内存余弦计算方案，避免引入 `sqlite-vec` 等额外 native 依赖。
 * 当前实现适用于数万级 chunk；若项目规模极大，可后续迁移到专用向量数据库。
 */
class SemanticIndexRepository(dbFile: File) {

    private val logger = Logger.getLogger<SemanticIndexRepository>()

    private var connection: Connection? = null
    private val statementCache = ConcurrentHashMap<String, PreparedStatement>()

    init {
        try {
            dbFile.parentFile?.mkdirs()
            connection = org.sqlite.JDBC().connect("jdbc:sqlite:${dbFile.absolutePath}", Properties())
            connection?.let { conn ->
                conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
                createTables(conn)
                logger.info("Semantic index repository initialized: ${dbFile.absolutePath}")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize semantic index DB: ${dbFile.absolutePath}", e)
        }
    }

    private fun createTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS semantic_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_path TEXT NOT NULL,
                    start_line INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    symbol_name TEXT,
                    symbol_type TEXT,
                    updated_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
                """.trimIndent()
            )

            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS semantic_embeddings (
                    chunk_id INTEGER PRIMARY KEY,
                    vector BLOB NOT NULL,
                    FOREIGN KEY(chunk_id) REFERENCES semantic_chunks(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_semantic_chunks_file ON semantic_chunks(file_path)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_semantic_chunks_symbol ON semantic_chunks(symbol_name)")
            stmt.execute("CREATE TRIGGER IF NOT EXISTS semantic_embeddings_delete AFTER DELETE ON semantic_chunks BEGIN DELETE FROM semantic_embeddings WHERE chunk_id = old.id; END")
        }
    }

    /**
     * 插入一个 chunk 及其向量，返回生成的主键；失败返回 -1。
     */
    fun insertChunk(chunk: SemanticChunk, vector: FloatArray): Long {
        val conn = connection ?: return -1
        return try {
            conn.prepareStatement(
                """
                INSERT INTO semantic_chunks(file_path, start_line, end_line, content, symbol_name, symbol_type)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                stmt.setString(1, chunk.filePath)
                stmt.setInt(2, chunk.startLine)
                stmt.setInt(3, chunk.endLine)
                stmt.setString(4, chunk.content)
                stmt.setString(5, chunk.symbolName)
                stmt.setString(6, chunk.symbolType)
                stmt.executeUpdate()

                stmt.generatedKeys.use { rs ->
                    if (rs.next()) {
                        val id = rs.getLong(1)
                        insertEmbedding(conn, id, vector)
                        id
                    } else {
                        -1
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to insert semantic chunk: ${chunk.filePath}", e)
            -1
        }
    }

    private fun insertEmbedding(conn: Connection, chunkId: Long, vector: FloatArray) {
        conn.prepareStatement(
            "INSERT INTO semantic_embeddings(chunk_id, vector) VALUES (?, ?)"
        ).use { stmt ->
            stmt.setLong(1, chunkId)
            stmt.setBytes(2, vectorToBytes(vector))
            stmt.executeUpdate()
        }
    }

    /**
     * 删除指定文件的所有 chunk（级联删除 embeddings）。
     */
    fun deleteChunksForFile(filePath: String): Int {
        val conn = connection ?: return 0
        return try {
            conn.prepareStatement("DELETE FROM semantic_chunks WHERE file_path = ?").use { stmt ->
                stmt.setString(1, filePath)
                stmt.executeUpdate()
            }
        } catch (e: Exception) {
            logger.warn("Failed to delete chunks for file: $filePath", e)
            0
        }
    }

    /**
     * 清空整个索引。
     */
    fun clearAll() {
        val conn = connection ?: return
        try {
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM semantic_embeddings")
                stmt.execute("DELETE FROM semantic_chunks")
            }
        } catch (e: Exception) {
            logger.warn("Failed to clear semantic index", e)
        }
    }

    /**
     * 基于 query 向量做全量余弦相似度召回，返回 Top-K 结果。
     */
    fun search(queryVector: FloatArray, limit: Int = 20, minScore: Float = 0.2f): List<ChunkSearchResult> {
        val conn = connection ?: return emptyList()
        val results = mutableListOf<ChunkSearchResult>()

        try {
            conn.prepareStatement(
                """
                SELECT c.id, c.file_path, c.start_line, c.end_line, c.content, c.symbol_name, c.symbol_type, e.vector
                FROM semantic_chunks c
                JOIN semantic_embeddings e ON c.id = e.chunk_id
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val vectorBytes = rs.getBytes("vector") ?: continue
                        val chunkVector = bytesToVector(vectorBytes)
                        if (chunkVector.size != queryVector.size) continue

                        val score = EmbeddingMath.cosineSimilarity(queryVector, chunkVector)
                        if (score >= minScore) {
                            results.add(
                                ChunkSearchResult(
                                    chunk = SemanticChunk(
                                        id = rs.getLong("id"),
                                        filePath = rs.getString("file_path"),
                                        startLine = rs.getInt("start_line"),
                                        endLine = rs.getInt("end_line"),
                                        content = rs.getString("content"),
                                        symbolName = rs.getString("symbol_name"),
                                        symbolType = rs.getString("symbol_type")
                                    ),
                                    score = score.toDouble()
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Semantic vector search failed", e)
        }

        return results.sortedByDescending { it.score }.take(limit)
    }

    /**
     * 当前索引中的 chunk 数量。
     */
    fun count(): Int {
        val conn = connection ?: return 0
        return try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM semantic_chunks").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to count semantic chunks", e)
            0
        }
    }

    /**
     * 已索引的文件路径集合。
     */
    fun getIndexedFilePaths(): Set<String> {
        val conn = connection ?: return emptySet()
        return try {
            val paths = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT DISTINCT file_path FROM semantic_chunks").use { rs ->
                    while (rs.next()) {
                        paths.add(rs.getString("file_path"))
                    }
                }
            }
            paths
        } catch (e: Exception) {
            logger.warn("Failed to list indexed file paths", e)
            emptySet()
        }
    }

    fun close() {
        try {
            statementCache.values.forEach { it.close() }
            statementCache.clear()
            connection?.close()
            connection = null
        } catch (e: Exception) {
            logger.warn("Failed to close semantic index repository", e)
        }
    }

    private fun vectorToBytes(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun bytesToVector(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        buffer.asFloatBuffer().get(floats)
        return floats
    }
}

/**
 * 语义搜索 chunk 数据类。
 */
data class SemanticChunk(
    val id: Long = -1,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val content: String,
    val symbolName: String? = null,
    val symbolType: String? = null
)

/**
 * 向量召回结果。
 */
data class ChunkSearchResult(
    val chunk: SemanticChunk,
    val score: Double
)
