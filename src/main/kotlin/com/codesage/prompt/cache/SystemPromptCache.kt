package com.codesage.prompt.cache

import com.codesage.shared.utils.Logger
import java.io.File
import java.security.MessageDigest
import java.sql.Connection

/**
 * 系统提示缓存管理器
 *
 * 基于 SQLite 持久化存储系统提示，避免每次会话重复构建。
 * 支持按 content hash 命中缓存、版本管理和缓存失效。
 */
class SystemPromptCache(
    dbPath: String = File(System.getProperty("user.home"), ".codesage/prompt_cache.db").absolutePath
) {
    private val logger = Logger.getLogger<SystemPromptCache>()
    private var connection: Connection? = null

    init {
        connection = try {
            // 使用直接实例化驱动的方式，避免 IntelliJ 插件类加载器问题
            val driver = org.sqlite.JDBC()
            val conn = driver.connect("jdbc:sqlite:$dbPath", java.util.Properties())
            createTables(conn)
            logger.info("SystemPromptCache initialized at $dbPath")
            conn
        } catch (e: Exception) {
            logger.error("Failed to initialize SystemPromptCache", e)
            null
        }
    }

    private fun createTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS cached_prompts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    version TEXT,
                    content_hash TEXT UNIQUE NOT NULL,
                    system_prompt TEXT NOT NULL,
                    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
                """.trimIndent()
            )
        }
    }

    /**
     * 获取缓存的系统提示
     * @param systemPrompt 完整的系统提示文本（用于计算 hash）
     * @return 缓存的系统提示，未命中返回 null
     */
    fun getCachedPrompt(systemPrompt: String): String? {
        val conn = connection ?: return null
        val hash = sha256(systemPrompt)
        return try {
            conn.prepareStatement(
                "SELECT system_prompt FROM cached_prompts WHERE content_hash = ?"
            ).use { stmt ->
                stmt.setString(1, hash)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        logger.debug("System prompt cache hit: hash=${hash.take(8)}...")
                        rs.getString("system_prompt")
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to get cached prompt", e)
            null
        }
    }

    /**
     * 缓存系统提示
     * @param version 提示版本号（用于缓存失效）
     * @param systemPrompt 系统提示文本
     */
    fun cachePrompt(version: String, systemPrompt: String) {
        val conn = connection ?: return
        val hash = sha256(systemPrompt)
        try {
            conn.prepareStatement(
                "INSERT OR REPLACE INTO cached_prompts(version, content_hash, system_prompt) VALUES(?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, version)
                stmt.setString(2, hash)
                stmt.setString(3, systemPrompt)
                stmt.executeUpdate()
                logger.debug("System prompt cached: version=$version, hash=${hash.take(8)}...")
            }
        } catch (e: Exception) {
            logger.error("Failed to cache prompt", e)
        }
    }

    /**
     * 使缓存失效
     * @param version 指定版本失效，null 表示全部失效
     */
    fun invalidateCache(version: String? = null) {
        val conn = connection ?: return
        try {
            if (version != null) {
                conn.prepareStatement("DELETE FROM cached_prompts WHERE version = ?").use { stmt ->
                    stmt.setString(1, version)
                    val deleted = stmt.executeUpdate()
                    logger.info("Invalidated $deleted prompt cache entries for version: $version")
                }
            } else {
                conn.createStatement().use { stmt ->
                    val deleted = stmt.executeUpdate("DELETE FROM cached_prompts")
                    logger.info("Invalidated all $deleted prompt cache entries")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to invalidate cache", e)
        }
    }

    /**
     * 关闭连接
     */
    fun shutdown() {
        try {
            connection?.close()
            connection = null
            logger.info("SystemPromptCache shutdown complete")
        } catch (e: Exception) {
            logger.error("Failed to shutdown SystemPromptCache", e)
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
