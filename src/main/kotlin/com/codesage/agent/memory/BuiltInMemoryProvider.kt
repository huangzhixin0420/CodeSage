package com.codesage.agent.memory

import com.codesage.model.dto.Message
import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import com.codesage.shared.utils.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/**
 * 内置记忆提供者（SQLite + FTS5）
 *
 * 基于 IntelliJ 内置的 SQLite JDBC 驱动，无需额外依赖。
 * 支持跨会话记忆存储、FTS5 全文检索、关键事实自动提取。
 *
 * 表结构：
 * - sessions: 会话元数据
 * - turns: 每轮对话记录
 * - memories: 提取的关键记忆（fact/preference/pattern）
 * - fts_search: FTS5 虚拟表，用于全文检索
 */
class BuiltInMemoryProvider : MemoryProvider {

    override val name: String = "builtin"

    private val logger = Logger.getLogger<BuiltInMemoryProvider>()
    private var connection: Connection? = null
    private var currentSessionId: String = ""
    private var homeDir: String = ""
    private var fts5Available: Boolean = true

    // 内存缓存：加速 prefetch
    private val prefetchCache = ConcurrentHashMap<String, String>()

    // 预编译语句缓存
    private val statementCache = ConcurrentHashMap<String, PreparedStatement>()

    // 协程作用域（用于后台预取）
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 6.9.3 prefetch token 预算（可配置，便于测试）
    var prefetchTokenBudget: Int = DEFAULT_PREFETCH_TOKEN_BUDGET
    var prefetchUseTokenBudget: Boolean = DEFAULT_PREFETCH_USE_TOKEN_BUDGET

    init {
        // 兜底：主动加载 org.sqlite.JDBC 并注册到 DriverManager。
        // 在 IDE 插件环境中，DriverManager 的平台类加载器可能看不到插件类加载器加载的
        // sqlite-jdbc，导致 DriverManager.getConnection("jdbc:sqlite:...") 抛出
        // "No suitable driver"。主路径仍然走下面的 `org.sqlite.JDBC().connect()`，
        // 但通过显式 Class.forName 兼有 ServiceLoader 备份能力，进程内任何地方
        // 使用 DriverManager.getConnection("jdbc:sqlite:...") 都能拿到驱动。
        try {
            Class.forName("org.sqlite.JDBC")
            // DriverManager.registerDriver 的 idempotent 检查在 sqlite-jdbc 3.45+ 已提供
            // (driver.isRegistered())，这里仅作为 defenisve 保险。
        } catch (e: ClassNotFoundException) {
            // 驱动不在 classpath（仅出现在纯单元测试场景），不中断构建
        } catch (e: Exception) {
            // 其他原因（linkage error 等）同样不中断
        }
    }

    override fun isAvailable(): Boolean = true

    override fun initialize(sessionId: String, homeDir: String, platform: String) {
        this.currentSessionId = sessionId
        this.homeDir = homeDir

        // 确保目录存在
        val dbDir = File(homeDir, "memory")
        dbDir.mkdirs()

        val dbPath = File(dbDir, "codesage_memory.db").absolutePath
        logger.info("Initializing BuiltInMemoryProvider, dbPath=$dbPath")

        // 在 IntelliJ 插件环境中，DriverManager 使用平台类加载器查找驱动，
        // 而 sqlite-jdbc 由插件类加载器加载，导致 DriverManager.getConnection() 找不到驱动。
        // 解决方案：直接实例化 org.sqlite.JDBC 并调用其 connect() 方法。
        connection = try {
            val driver = org.sqlite.JDBC()
            val conn = driver.connect("jdbc:sqlite:$dbPath", java.util.Properties())
            logger.info("SQLite connection established directly via org.sqlite.JDBC")
            conn
        } catch (e: Exception) {
            logger.error("Failed to create SQLite connection directly", e)
            null
        }

        connection?.let { conn ->
            // 启用外键约束，使 memory_embeddings 的 CASCADE 生效
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            fts5Available = checkFts5Available(conn)
            if (!fts5Available) {
                logger.warn("FTS5 extension not available, falling back to LIKE search")
            }
            createTables(conn)
            // 创建或更新当前会话记录
            insertSession(conn, sessionId)
        }
    }

    private val defaultSystemPromptBlock = """
        [MEMORY SYSTEM]
        You have access to persistent memory across sessions.
        Relevant memories from past conversations may be injected into the context.
        You can also use memory_search, memory_add, and memory_update tools to manage memories explicitly.
    """.trimIndent()

    override fun systemPromptBlock(): String {
        val conn = connection ?: return defaultSystemPromptBlock
        return try {
            val prefs = getRecentPreferences(conn, limit = 8)
            if (prefs.isEmpty()) {
                defaultSystemPromptBlock
            } else {
                buildString {
                    appendLine(defaultSystemPromptBlock)
                    appendLine()
                    appendLine("[USER PREFERENCES]")
                    prefs.forEach { appendLine("- $it") }
                    appendLine("[END USER PREFERENCES]")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to build preference block", e)
            defaultSystemPromptBlock
        }
    }

    override fun prefetch(query: String, sessionId: String): String {
        val cacheKey = "$sessionId:$query"
        prefetchCache[cacheKey]?.let { return it }

        val conn = connection ?: return ""

        return try {
            // 1. FTS5/向量召回候选记忆（扩大候选集，保证后续排序与预算仍有足够选择）
            val memories = searchMemories(conn, query, limit = 20)

            // 2. 获取最近几轮对话作为短期记忆
            val recentTurns = getRecentTurns(conn, sessionId, limit = 3)

            if (memories.isEmpty() && recentTurns.isEmpty()) {
                prefetchCache[cacheKey] = ""
                return ""
            }

            // 3. 按查询相似度与类型优先级排序，并应用 token/字符预算
            val result = buildPrefetchContext(memories, recentTurns, query)

            prefetchCache[cacheKey] = result
            result
        } catch (e: Exception) {
            logger.error("Prefetch failed", e)
            ""
        }
    }

    // === 6.9.3 记忆上下文排序与预算 ===

    /**
     * 6.9.3 对召回记忆按类型优先级与查询相似度综合排序。
     *
     * 优先级：preference / pattern > fact / project > 其他。
     * 同优先级内再按与 [query] 的余弦相似度降序排列。
     */
    private fun rankMemoriesForInjection(
        memories: List<MemoryRecord>,
        query: String
    ): List<MemoryRecord> {
        val scored = MemorySimilarityRanker().rankBySimilarity(memories, query)
        return scored.sortedWith(
            compareByDescending<MemorySimilarityRanker.ScoredMemory> { MEMORY_TYPE_PRIORITY[it.record.type] ?: 1 }
                .thenByDescending { it.score }
        ).map { it.record }
    }

    /**
     * 6.9.3 构建 prefetch 文本块。
     *
     * 流程：
     * 1. 按相似度与类型优先级排序记忆；
     * 2. 若启用 token 预算，按估算 token 数依次选取 Top-K，超出部分用提示告知模型；
     * 3. 追加最近对话；
     * 4. 最终按 [PREFETCH_TOTAL_MAX_LEN] 做字符级兜底截断。
     */
    private fun buildPrefetchContext(
        memories: List<MemoryRecord>,
        recentTurns: List<TurnRecord>,
        query: String
    ): String {
        val rankedMemories = rankMemoriesForInjection(memories, query)

        var selectedMemories = rankedMemories
        var omittedCount = 0

        if (prefetchUseTokenBudget) {
            val budgetResult = applyTokenBudget(rankedMemories, recentTurns)
            selectedMemories = budgetResult.selectedMemories
            omittedCount = budgetResult.omittedCount
        }

        val builder = StringBuilder()
        builder.appendLine("<memory-context>")

        if (selectedMemories.isNotEmpty()) {
            builder.appendLine("## Relevant Memories")
            selectedMemories.forEach { mem ->
                val sanitized = sanitizeMemoryContent(mem.content)
                builder.appendLine("- [${mem.type}] $sanitized")
            }
            if (omittedCount > 0) {
                builder.appendLine("[$omittedCount more memories omitted due to context budget]")
            }
        }

        if (recentTurns.isNotEmpty()) {
            builder.appendLine("## Recent Conversation")
            recentTurns.forEach { turn ->
                val userSanitized = sanitizeMemoryContent(turn.userMsg)
                val assistantSanitized = sanitizeMemoryContent(turn.assistantMsg)
                builder.appendLine("User: $userSanitized")
                builder.appendLine("Assistant: $assistantSanitized")
            }
        }

        builder.appendLine("</memory-context>")

        // 总长度兜底：即使单条截断，多条累积也可能超过 16KB
        val resultRaw = builder.toString()
        return if (resultRaw.length > PREFETCH_TOTAL_MAX_LEN) {
            resultRaw.take(PREFETCH_TOTAL_MAX_LEN) + "\n[...truncated...]"
        } else {
            resultRaw
        }
    }

    /**
     * 6.9.3 token 预算应用：在总预算内保留 Top-K 条记忆。
     *
     * 预算分配：
     * - 先扣除 `<memory-context>` wrapper、最近对话、区标题与省略提示的固定开销；
     * - 剩余预算按顺序累加每条记忆的估算 token 数；
     * - 超出预算的记忆被计入 [BudgetResult.omittedCount]，并在结果中提示模型。
     */
    private fun applyTokenBudget(
        memories: List<MemoryRecord>,
        recentTurns: List<TurnRecord>
    ): BudgetResult {
        val fixedOverhead = estimateTokens(MEMORY_CONTEXT_WRAPPER)
        val recentTurnsTokens = recentTurns.sumOf { turn ->
            estimateTokens("User: ${turn.userMsg}\n") + estimateTokens("Assistant: ${turn.assistantMsg}\n")
        }
        val headerTokens = estimateTokens("## Relevant Memories\n")
        val hintTokens = estimateTokens("[99 more memories omitted due to context budget]\n")

        var memoryBudget = prefetchTokenBudget - fixedOverhead - recentTurnsTokens - headerTokens - hintTokens
        if (memoryBudget < 0) memoryBudget = 0

        val selected = mutableListOf<MemoryRecord>()
        var usedTokens = 0
        var omitted = 0

        for (memory in memories) {
            val sanitized = sanitizeMemoryContent(memory.content)
            val itemTokens = estimateTokens("- [${memory.type}] $sanitized\n")
            // 至少保留一条最相关的记忆，避免预算过小时丢失全部上下文
            if (selected.isEmpty() || usedTokens + itemTokens <= memoryBudget) {
                selected.add(memory)
                usedTokens += itemTokens
            } else {
                omitted++
            }
        }

        return BudgetResult(selected, omitted)
    }

    /** 6.9.3 token 预算结果。 */
    private data class BudgetResult(val selectedMemories: List<MemoryRecord>, val omittedCount: Int)

    override fun queuePrefetch(query: String, sessionId: String) {
        coroutineScope.launch {
            try {
                prefetch(query, sessionId)
            } catch (e: Exception) {
                logger.error("Queue prefetch failed", e)
            }
        }
    }

    override fun syncTurn(userContent: String, assistantContent: String, sessionId: String) {
        val conn = connection ?: return

        try {
            // 1. 写入 turns 表
            val tokens = estimateTokens(userContent) + estimateTokens(assistantContent)
            insertTurn(conn, sessionId, userContent, assistantContent, tokens)

            // 2. 异步提取关键事实（轻量级规则引擎）
            val facts = extractFacts(userContent, assistantContent)
            facts.forEach { fact ->
                val similar = findSimilarMemory(conn, fact.content)
                if (similar == null) {
                    insertMemory(conn, sessionId, fact.content, fact.type)
                } else {
                    logger.debug("Skipping duplicate memory: ${fact.content.take(50)}")
                }
            }

            // 3. 自动清理旧消息
            cleanupOldTurns(sessionId, keepCount = 100)

            // 4. 清除相关缓存
            prefetchCache.keys.filter { it.startsWith("$sessionId:") }.forEach {
                prefetchCache.remove(it)
            }
        } catch (e: Exception) {
            logger.error("Sync turn failed", e)
        }
    }

    override fun getToolSchemas(): List<Tool> {
        return listOf(
            Tool(
                name = "memory_search",
                description = "Search persistent memory for relevant information from past conversations. Combines FTS5 keyword search and local vector semantic recall.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string", "Search query describing what you want to recall"),
                        "limit" to ToolProperty("integer", "Maximum number of results (default 5)")
                    ),
                    required = listOf("query")
                )
            ),
            Tool(
                name = "memory_add",
                description = "Add a new fact, preference, or pattern to persistent memory.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "content" to ToolProperty("string", "The memory content to store"),
                        "type" to ToolProperty("string", "Memory type: fact, preference, or pattern")
                    ),
                    required = listOf("content", "type")
                )
            ),
            Tool(
                name = "memory_update",
                description = "Update an existing memory by ID.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "id" to ToolProperty("integer", "Memory ID to update"),
                        "content" to ToolProperty("string", "New content")
                    ),
                    required = listOf("id", "content")
                )
            )
        )
    }

    override fun handleToolCall(toolName: String, args: Map<String, Any>): String {
        val conn = connection ?: return "{\"success\":false,\"error\":\"Memory not initialized\"}"

        return try {
            when (toolName) {
                "memory_search" -> {
                    val query = args["query"] as? String ?: return "{\"success\":false,\"error\":\"Missing query\"}"
                    val limit = (args["limit"] as? Number)?.toInt() ?: 5
                    val results = searchMemories(conn, query, limit)
                    formatToolResult(results.map { mapOf("id" to it.id, "content" to it.content, "type" to it.type) })
                }

                "memory_add" -> {
                    val content =
                        args["content"] as? String ?: return "{\"success\":false,\"error\":\"Missing content\"}"
                    val type = args["type"] as? String ?: "fact"
                    val similar = findSimilarMemory(conn, content)
                    val id = if (similar == null) {
                        insertMemory(conn, currentSessionId, content, type)
                    } else {
                        similar.id
                    }
                    "{\"success\":true,\"id\":$id}"
                }

                "memory_update" -> {
                    val id = (args["id"] as? Number)?.toLong()
                        ?: return "{\"success\":false,\"error\":\"Missing or invalid id\"}"
                    val content =
                        args["content"] as? String ?: return "{\"success\":false,\"error\":\"Missing content\"}"
                    updateMemory(conn, id, content)
                    "{\"success\":true}"
                }

                else -> "{\"success\":false,\"error\":\"Unknown memory tool: $toolName\"}"
            }
        } catch (e: Exception) {
            logger.error("Memory tool failed: $toolName", e)
            "{\"success\":false,\"error\":\"${e.message}\"}"
        }
    }

    override fun onSessionEnd(messages: List<Message>) {
        val conn = connection ?: return
        try {
            // 6.9.2 自动生成结构化会话摘要并提取关键事实
            val summary = SessionSummarizer.summarize(messages)
            updateSessionSummary(conn, currentSessionId, summary.summary)

            summary.keyFacts.forEach { fact ->
                val similar = findSimilarMemory(conn, fact)
                if (similar == null) {
                    insertMemory(conn, currentSessionId, fact, "fact")
                }
            }

            logger.info(
                "Session end processed: session=$currentSessionId, " +
                        "facts=${summary.keyFacts.size}, messages=${messages.size}"
            )
        } catch (e: Exception) {
            logger.error("Session end processing failed", e)
        }
    }

    override fun shutdown() {
        try {
            coroutineScope.cancel()
            statementCache.values.forEach { it.close() }
            statementCache.clear()
            connection?.close()
            connection = null
            logger.info("BuiltInMemoryProvider shutdown complete")
        } catch (e: Exception) {
            logger.error("Shutdown failed", e)
        }
    }

    // === 内部数据结构 ===

    data class MemoryRecord(val id: Long, val content: String, val type: String, val createdAt: Long)
    data class TurnRecord(val userMsg: String, val assistantMsg: String, val createdAt: Long)
    data class ExtractedFact(val content: String, val type: String)

    // === 数据库操作 ===

    private fun createTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            // 会话表
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                    system_prompt TEXT,
                    summary TEXT
                )
            """.trimIndent()
            )

            // 对话轮次表
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS turns (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    user_msg TEXT,
                    assistant_msg TEXT,
                    tokens INTEGER DEFAULT 0,
                    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
            """.trimIndent()
            )

            // 记忆表
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    type TEXT DEFAULT 'fact' CHECK(type IN ('fact', 'preference', 'pattern')),
                    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
            """.trimIndent()
            )

            // FTS5 虚拟表（用于全文搜索记忆内容）
            stmt.execute(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS fts_search USING fts5(
                    content,
                    type,
                    memory_id UNINDEXED,
                    tokenize='porter'
                )
            """.trimIndent()
            )

            // FTS 触发器：自动同步 memories 到 fts_search
            stmt.execute(
                """
                CREATE TRIGGER IF NOT EXISTS memories_fts_insert AFTER INSERT ON memories BEGIN
                    INSERT INTO fts_search(content, type, memory_id)
                    VALUES (new.content, new.type, new.id);
                END
            """.trimIndent()
            )

            stmt.execute(
                """
                CREATE TRIGGER IF NOT EXISTS memories_fts_update AFTER UPDATE ON memories BEGIN
                    UPDATE fts_search SET content = new.content, type = new.type
                    WHERE memory_id = new.id;
                END
            """.trimIndent()
            )

            stmt.execute(
                """
                CREATE TRIGGER IF NOT EXISTS memories_fts_delete AFTER DELETE ON memories BEGIN
                    DELETE FROM fts_search WHERE memory_id = old.id;
                END
            """.trimIndent()
            )

            // 6.9.1 向量记忆：embedding 表
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS memory_embeddings (
                    memory_id INTEGER PRIMARY KEY,
                    vector BLOB NOT NULL,
                    FOREIGN KEY(memory_id) REFERENCES memories(id) ON DELETE CASCADE
                )
            """.trimIndent()
            )

            stmt.execute(
                """
                CREATE TRIGGER IF NOT EXISTS memory_embeddings_delete AFTER DELETE ON memories BEGIN
                    DELETE FROM memory_embeddings WHERE memory_id = old.id;
                END
            """.trimIndent()
            )

            // 索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_turns_session ON turns(session_id)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_session ON memories(session_id)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_type ON memories(type)")
        }
    }

    private fun insertSession(conn: Connection, sessionId: String) {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO sessions(id) VALUES(?)"
        ).use { stmt ->
            stmt.setString(1, sessionId)
            stmt.executeUpdate()
        }
    }

    private fun insertTurn(conn: Connection, sessionId: String, userMsg: String, assistantMsg: String, tokens: Int) {
        conn.prepareStatement(
            "INSERT INTO turns(session_id, user_msg, assistant_msg, tokens) VALUES(?, ?, ?, ?)"
        ).use { stmt ->
            stmt.setString(1, sessionId)
            stmt.setString(2, userMsg)
            stmt.setString(3, assistantMsg)
            stmt.setInt(4, tokens)
            stmt.executeUpdate()
        }
    }

    private fun insertMemory(conn: Connection, sessionId: String, content: String, type: String): Long {
        conn.prepareStatement(
            "INSERT INTO memories(session_id, content, type) VALUES(?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        ).use { stmt ->
            stmt.setString(1, sessionId)
            stmt.setString(2, content)
            stmt.setString(3, type)
            stmt.executeUpdate()
            stmt.generatedKeys.use { rs ->
                val id = if (rs.next()) rs.getLong(1) else -1
                if (id > 0) {
                    insertMemoryEmbedding(conn, id, content)
                }
                return id
            }
        }
    }

    private fun insertMemoryEmbedding(conn: Connection, memoryId: Long, content: String) {
        try {
            val vector = MemoryEmbedding.embed(content)
            conn.prepareStatement(
                "INSERT INTO memory_embeddings(memory_id, vector) VALUES(?, ?)"
            ).use { stmt ->
                stmt.setLong(1, memoryId)
                stmt.setBytes(2, vectorToBytes(vector))
                stmt.executeUpdate()
            }
        } catch (e: Exception) {
            logger.warn("Failed to store memory embedding for memory_id=$memoryId: ${e.message}")
        }
    }

    private fun updateMemory(conn: Connection, id: Long, content: String) {
        conn.prepareStatement(
            "UPDATE memories SET content = ? WHERE id = ?"
        ).use { stmt ->
            stmt.setString(1, content)
            stmt.setLong(2, id)
            stmt.executeUpdate()
        }
    }

    private fun searchMemories(conn: Connection, query: String, limit: Int): List<MemoryRecord> {
        val terms = query.split(Regex("""\s+""")).filter { it.length > 1 }
        if (terms.isEmpty()) {
            // 无有效关键词时退化到纯向量语义召回
            return searchMemoriesVector(conn, query, limit)
        }
        return rankedSearch(conn, query, terms, limit)
    }

    /**
     * 6.9.1 融合 FTS 关键词排名与向量语义相似度，返回综合排序后的记忆。
     */
    private fun rankedSearch(
        conn: Connection,
        query: String,
        terms: List<String>,
        limit: Int
    ): List<MemoryRecord> {
        val ftsResults =
            if (fts5Available) searchWithFts5(conn, terms, limit * 3) else searchWithLike(conn, terms, limit * 3)
        val vectorResults = searchMemoriesVector(conn, query, limit * 3)

        val scores = mutableMapOf<Long, Pair<Float, MemoryRecord>>()

        ftsResults.forEachIndexed { index, record ->
            val ftsScore = 1.0f / (index + 1)
            scores[record.id] = scores[record.id]?.let { (existing, rec) ->
                Pair(existing + 0.35f * ftsScore, rec)
            } ?: Pair(0.35f * ftsScore, record)
        }

        vectorResults.forEachIndexed { index, record ->
            // vectorResults 已按 cosine similarity 降序排列
            val vectorScore = 1.0f / (index + 1)
            scores[record.id] = scores[record.id]?.let { (existing, rec) ->
                Pair(existing + 0.65f * vectorScore, rec)
            } ?: Pair(0.65f * vectorScore, record)
        }

        return scores.values
            .sortedByDescending { it.first }
            .take(limit)
            .map { it.second }
    }

    /**
     * 6.9.1 向量语义召回：按 query embedding 与记忆 embedding 的余弦相似度排序。
     */
    private fun searchMemoriesVector(conn: Connection, query: String, limit: Int): List<MemoryRecord> {
        val queryVector = MemoryEmbedding.embed(query)
        val results = mutableListOf<Pair<Float, MemoryRecord>>()

        conn.prepareStatement(
            """
            SELECT m.id, m.content, m.type, m.created_at, e.vector
            FROM memories m
            JOIN memory_embeddings e ON m.id = e.memory_id
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val vectorBytes = rs.getBytes("vector") ?: continue
                    val memoryVector = bytesToVector(vectorBytes)
                    val similarity = MemoryEmbedding.cosineSimilarity(queryVector, memoryVector)
                    if (similarity > 0.0f) {
                        results.add(
                            similarity to MemoryRecord(
                                id = rs.getLong("id"),
                                content = rs.getString("content"),
                                type = rs.getString("type"),
                                createdAt = rs.getLong("created_at")
                            )
                        )
                    }
                }
            }
        }

        return results.sortedByDescending { it.first }.take(limit).map { it.second }
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

    private fun searchWithFts5(conn: Connection, terms: List<String>, limit: Int): List<MemoryRecord> {
        val results = mutableListOf<MemoryRecord>()
        val ftsQuery = terms.joinToString(" OR ") { "$it*" }

        try {
            conn.prepareStatement(
                """
                    SELECT m.id, m.content, m.type, m.created_at
                    FROM memories m
                    JOIN fts_search f ON m.id = f.memory_id
                    WHERE f.content MATCH ?
                    ORDER BY rank
                    LIMIT ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, ftsQuery)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(
                            MemoryRecord(
                                id = rs.getLong("id"),
                                content = rs.getString("content"),
                                type = rs.getString("type"),
                                createdAt = rs.getLong("created_at")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("FTS5 search failed, falling back to LIKE: ${e.message}")
            return searchWithLike(conn, terms, limit)
        }
        return results
    }

    private fun searchWithLike(conn: Connection, terms: List<String>, limit: Int): List<MemoryRecord> {
        val results = mutableListOf<MemoryRecord>()
        val likePattern = "%${terms.joinToString("%") { it.lowercase() }}%"

        conn.prepareStatement(
            """
                SELECT id, content, type, created_at
                FROM memories
                WHERE LOWER(content) LIKE ?
                ORDER BY created_at DESC
                LIMIT ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, likePattern)
            stmt.setInt(2, limit)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(
                        MemoryRecord(
                            id = rs.getLong("id"),
                            content = rs.getString("content"),
                            type = rs.getString("type"),
                            createdAt = rs.getLong("created_at")
                        )
                    )
                }
            }
        }
        return results
    }

    private fun checkFts5Available(conn: Connection): Boolean {
        return try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='fts_search'")
                    .use { rs -> rs.next() }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getRecentTurns(conn: Connection, sessionId: String, limit: Int): List<TurnRecord> {
        val results = mutableListOf<TurnRecord>()
        conn.prepareStatement(
            "SELECT user_msg, assistant_msg, created_at FROM turns WHERE session_id = ? ORDER BY id DESC LIMIT ?"
        ).use { stmt ->
            stmt.setString(1, sessionId)
            stmt.setInt(2, limit)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(
                        TurnRecord(
                            userMsg = rs.getString("user_msg"),
                            assistantMsg = rs.getString("assistant_msg"),
                            createdAt = rs.getLong("created_at")
                        )
                    )
                }
            }
        }
        return results.reversed()
    }

    private fun updateSessionSummary(conn: Connection, sessionId: String, summary: String) {
        conn.prepareStatement(
            "UPDATE sessions SET summary = ? WHERE id = ?"
        ).use { stmt ->
            stmt.setString(1, summary.take(2000))
            stmt.setString(2, sessionId)
            stmt.executeUpdate()
        }
    }

    // === 轻量级事实提取 ===

    private fun extractFacts(userContent: String, assistantContent: String): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        val combined = "$userContent $assistantContent"

        // 规则 1: 用户偏好（包含 "prefer", "like", "want", "use" 等）
        val preferencePatterns = listOf(
            Regex("(?i)I (?:prefer|like|want|need|always use|usually|don't like|hate)", RegexOption.IGNORE_CASE),
            Regex("(?i)(?:使用|喜欢|偏好|习惯|想要|需要)")
        )
        preferencePatterns.forEach { pattern ->
            pattern.find(combined)?.let { match ->
                val start = maxOf(0, match.range.first - 20)
                val end = minOf(combined.length, match.range.last + 80)
                facts.add(ExtractedFact(combined.substring(start, end).trim(), "preference"))
            }
        }

        // 规则 2: 架构决策（包含 "decide", "choose", "use X for Y", "架构" 等）
        val decisionPatterns = listOf(
            Regex(
                """(?i)(?:decided?|chosen?|opted?|will use|going with)\s+(?:to\s+)?(.{0,100})""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""(?i)(?:决定|选择|采用|使用)\s+(.{0,50})""")
        )
        decisionPatterns.forEach { pattern ->
            pattern.find(combined)?.groupValues?.getOrNull(1)?.let { decision ->
                if (decision.length > 5) {
                    facts.add(ExtractedFact(decision.trim(), "pattern"))
                }
            }
        }

        // 规则 3: 项目相关事实（文件路径、技术栈提及）
        val techPattern = Regex("(?i)(?:Kotlin|Java|Python|React|Vue|Spring|Gradle|Maven|Docker|Kubernetes)")
        techPattern.findAll(combined).forEach { match ->
            facts.add(ExtractedFact("Project uses ${match.value}", "fact"))
        }

        return facts.take(5) // 每轮最多提取 5 条
    }

    // === 会话历史管理 ===

    /**
     * 从 SQLite 加载会话历史消息（支持分页）
     */
    fun loadSessionHistory(sessionId: String, limit: Int = 50, offset: Int = 0): List<Message> {
        val conn = connection ?: return emptyList()
        val messages = mutableListOf<Message>()

        conn.prepareStatement(
            """
            SELECT user_msg, assistant_msg, created_at
            FROM turns
            WHERE session_id = ?
            ORDER BY id ASC
            LIMIT ? OFFSET ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, sessionId)
            stmt.setInt(2, limit)
            stmt.setInt(3, offset)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val userMsg = rs.getString("user_msg")
                    val assistantMsg = rs.getString("assistant_msg")
                    if (!userMsg.isNullOrBlank()) {
                        messages.add(Message.userMessage(userMsg))
                    }
                    if (!assistantMsg.isNullOrBlank()) {
                        messages.add(Message.assistantMessage(assistantMsg))
                    }
                }
            }
        }
        return messages
    }

    /**
     * 清理旧消息，仅保留最近 N 条
     */
    fun cleanupOldTurns(sessionId: String, keepCount: Int = 100) {
        val conn = connection ?: return
        try {
            conn.prepareStatement(
                """
                DELETE FROM turns
                WHERE session_id = ?
                AND id NOT IN (
                    SELECT id FROM turns
                    WHERE session_id = ?
                    ORDER BY id DESC
                    LIMIT ?
                )
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, sessionId)
                stmt.setString(2, sessionId)
                stmt.setInt(3, keepCount)
                val deleted = stmt.executeUpdate()
                if (deleted > 0) {
                    logger.info("Cleaned up $deleted old turns for session $sessionId")
                }
            }
        } catch (e: Exception) {
            logger.error("Cleanup old turns failed", e)
        }
    }

    // === 偏好管理 ===

    /**
     * 获取最近的偏好记忆（用于系统提示注入）
     */
    private fun getRecentPreferences(conn: Connection, limit: Int): List<String> {
        val results = mutableListOf<String>()
        conn.prepareStatement(
            """
            SELECT content
            FROM memories
            WHERE type = 'preference'
            ORDER BY created_at DESC
            LIMIT ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setInt(1, limit)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(rs.getString("content"))
                }
            }
        }
        return results
    }

    // === 去重 ===

    /**
     * 查找内容完全相同的已有记忆
     */
    private fun findSimilarMemory(conn: Connection, content: String): MemoryRecord? {
        val normalized = content.trim().lowercase()
        if (normalized.length < 5) return null

        conn.prepareStatement(
            """
            SELECT id, content, type, created_at
            FROM memories
            WHERE LOWER(content) = ?
            LIMIT 1
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, normalized)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return MemoryRecord(
                        id = rs.getLong("id"),
                        content = rs.getString("content"),
                        type = rs.getString("type"),
                        createdAt = rs.getLong("created_at")
                    )
                }
            }
        }
        return null
    }

    // === 工具方法 ===

    private fun estimateTokens(text: String): Int {
        // 粗略估算：中文按 1 字 ≈ 1 token，英文按 4 字符 ≈ 1 token
        val chineseChars = text.count { it.code > 0x4E00 && it.code < 0x9FFF }
        val otherChars = text.length - chineseChars
        return chineseChars + (otherChars / 4)
    }

    private fun formatToolResult(data: List<Map<String, Any>>): String {
        return try {
            val resultsArray = data.joinToString(",") { item ->
                val entries = item.entries.joinToString(",") { (k, v) ->
                    val valueStr = when (v) {
                        is String -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                        is Number -> v.toString()
                        else -> "\"${v.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    }
                    "\"$k\":$valueStr"
                }
                "{$entries}"
            }
            "{\"success\":true,\"results\":[$resultsArray]}"
        } catch (e: Exception) {
            logger.error("Failed to format tool result", e)
            "{\"success\":true,\"results\":[]}"
        }
    }

    /**
     * H1 修复：单条 memory 内容做长度截断 + 转义 `</memory-context>` 关闭标签。
     * 防止用户曾粘贴的超大日志 / 包含 prompt 注入片段的内容污染 system prompt。
     */
    private fun sanitizeMemoryContent(content: String): String {
        return content
            .take(PREFETCH_ITEM_MAX_LEN)
            .replace("</memory-context>", "<\\/memory-context>")
    }

    companion object {
        /** 单条 memory 注入的最大字符数（H1 修复） */
        private const val PREFETCH_ITEM_MAX_LEN: Int = 4 * 1024

        /** prefetch 整段的最大字符数（H1 修复） */
        private const val PREFETCH_TOTAL_MAX_LEN: Int = 16 * 1024

        /** 6.9.3 默认 prefetch token 总预算。 */
        private const val DEFAULT_PREFETCH_TOKEN_BUDGET: Int = 2048

        /** 6.9.3 是否默认启用 token 预算（可通过 `codesage.memory.prefetch.tokenBudget.enabled` 系统属性覆盖）。 */
        private val DEFAULT_PREFETCH_USE_TOKEN_BUDGET: Boolean =
            System.getProperty("codesage.memory.prefetch.tokenBudget.enabled", "true").toBoolean()

        /** 6.9.3 `<memory-context>` wrapper 的估算 token 开销。 */
        private const val MEMORY_CONTEXT_WRAPPER: String =
            "<memory-context>\n## Relevant Memories\n## Recent Conversation\n</memory-context>\n"

        /** 6.9.3 记忆类型优先级：数字越大优先级越高。 */
        private val MEMORY_TYPE_PRIORITY: Map<String, Int> = mapOf(
            "preference" to 3,
            "pattern" to 3,
            "fact" to 2,
            "project" to 2
        )
    }
}
