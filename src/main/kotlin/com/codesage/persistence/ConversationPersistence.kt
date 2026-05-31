package com.codesage.persistence

import com.codesage.agent.core.AgentSession
import com.codesage.model.dto.Message
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 对话持久化管理器
 * 将会话历史保存到磁盘，支持JSON/YAML格式
 */
class ConversationPersistence(
    private val storageDir: File = File(System.getProperty("user.home"), ".codesage/conversations")
) {
    private val logger = Logger.getLogger<ConversationPersistence>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    // 内存缓存
    private val sessionCache = ConcurrentHashMap<String, PersistedSession>()

    // 异步写入线程池
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "codesage-persistence").apply { isDaemon = true }
    }

    // 跟踪已被删除的会话ID，防止异步写入已删除的会话
    private val deletedSessionIds = ConcurrentHashMap.newKeySet<String>()

    init {
        storageDir.mkdirs()
        loadAllSessions()
    }

    /**
     * 持久化会话（异步，不阻塞调用线程）
     */
    fun saveSession(session: AgentSession, messages: List<Message>) {
        val persisted = PersistedSession(
            id = session.id,
            name = session.name,
            createdAt = session.createdAt,
            lastActivityAt = session.lastActivityAt,
            isActive = session.isActive,
            messages = messages.map { it.toPersistedMessage() },
            metadata = SessionMetadata(
                messageCount = messages.size,
                saveVersion = CURRENT_VERSION
            )
        )

        sessionCache[session.id] = persisted
        deletedSessionIds.remove(session.id)

        ioExecutor.execute {
            // 如果该会话已被删除，跳过写入
            if (session.id in deletedSessionIds) {
                logger.debug("Skipping save for deleted session: ${session.id}")
                return@execute
            }
            try {
                val file = getSessionFile(session.id)
                val tempFile = File(file.parent, "${file.name}.tmp")
                tempFile.writeText(json.encodeToString(persisted))
                tempFile.renameTo(file)
                logger.debug("Saved session: ${session.id} (${messages.size} messages)")
            } catch (e: Exception) {
                logger.error("Failed to save session: ${session.id}", e)
            }
        }
    }

    /**
     * 同步持久化会话（用于关键路径需要确认保存的场景）
     */
    suspend fun saveSessionSync(session: AgentSession, messages: List<Message>) = withContext(Dispatchers.IO) {
        val persisted = PersistedSession(
            id = session.id,
            name = session.name,
            createdAt = session.createdAt,
            lastActivityAt = session.lastActivityAt,
            isActive = session.isActive,
            messages = messages.map { it.toPersistedMessage() },
            metadata = SessionMetadata(
                messageCount = messages.size,
                saveVersion = CURRENT_VERSION
            )
        )

        sessionCache[session.id] = persisted

        try {
            val file = getSessionFile(session.id)
            val tempFile = File(file.parent, "${file.name}.tmp")
            tempFile.writeText(json.encodeToString(persisted))
            tempFile.renameTo(file)
            logger.debug("Saved session sync: ${session.id} (${messages.size} messages)")
        } catch (e: Exception) {
            logger.error("Failed to save session: ${session.id}", e)
        }
    }

    /**
     * 加载会话
     */
    fun loadSession(sessionId: String): PersistedSession? {
        // 先查缓存
        sessionCache[sessionId]?.let { return it }

        // 从磁盘加载
        return try {
            val file = getSessionFile(sessionId)
            if (!file.exists()) return null

            val persisted = json.decodeFromString<PersistedSession>(file.readText())
            sessionCache[sessionId] = persisted
            persisted
        } catch (e: Exception) {
            logger.error("Failed to load session: $sessionId", e)
            null
        }
    }

    /**
     * 加载所有会话（合并磁盘和缓存数据）
     */
    fun loadAllSessions(): List<PersistedSession> {
        return try {
            val diskSessions = storageDir.listFiles { f -> f.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        file.nameWithoutExtension to json.decodeFromString<PersistedSession>(file.readText())
                    } catch (e: Exception) {
                        logger.warn("Failed to parse session file: ${file.name}", e)
                        null
                    }
                }
                ?.toMap()
                ?: emptyMap()

            // 合并缓存（缓存中的数据可能更新）
            val merged = mutableMapOf<String, PersistedSession>()
            merged.putAll(diskSessions)
            merged.putAll(sessionCache)

            merged.values.sortedByDescending { it.lastActivityAt }
        } catch (e: Exception) {
            logger.error("Failed to load all sessions", e)
            emptyList()
        }
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String): Boolean {
        sessionCache.remove(sessionId)
        deletedSessionIds.add(sessionId)
        return try {
            val file = getSessionFile(sessionId)
            if (file.exists()) {
                file.delete()
            } else {
                true // 文件不存在但缓存已删除，视为成功
            }
        } catch (e: Exception) {
            logger.error("Failed to delete session: $sessionId", e)
            false
        }
    }

    /**
     * 清理旧会话（保留最近N个）
     */
    fun cleanupOldSessions(keepCount: Int = 50) {
        val all = loadAllSessions()
        if (all.size <= keepCount) return

        val toDelete = all.sortedByDescending { it.lastActivityAt }.drop(keepCount)
        toDelete.forEach { deleteSession(it.id) }
        logger.info("Cleaned up ${toDelete.size} old sessions, keeping $keepCount")
    }

    /**
     * 获取会话文件
     */
    private fun getSessionFile(sessionId: String): File {
        return File(storageDir, "$sessionId.json")
    }

    /**
     * 关闭持久化管理器
     */
    fun shutdown() {
        ioExecutor.shutdown()
    }

    companion object {
        private const val CURRENT_VERSION = 1
    }
}

// === 序列化数据类 ===

@Serializable
data class PersistedSession(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastActivityAt: Long,
    val isActive: Boolean,
    val messages: List<PersistedMessage>,
    val metadata: SessionMetadata
)

@Serializable
data class PersistedMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<PersistedToolCall>? = null,
    val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

@Serializable
data class SessionMetadata(
    val messageCount: Int,
    val saveVersion: Int
)

// === 扩展函数 ===

private fun Message.toPersistedMessage(): PersistedMessage {
    return PersistedMessage(
        role = this.role.name,
        content = this.content,
        toolCalls = this.toolCalls?.map {
            PersistedToolCall(
                id = it.id,
                name = it.name,
                arguments = it.arguments
            )
        },
        toolCallId = this.toolCallId
    )
}

fun PersistedMessage.toMessage(): Message {
    return Message(
        role = com.codesage.model.dto.Role.valueOf(this.role),
        content = this.content ?: "",
        toolCalls = this.toolCalls?.map {
            com.codesage.model.dto.ToolCall(
                id = it.id,
                name = it.name,
                arguments = it.arguments
            )
        },
        toolCallId = this.toolCallId
    )
}

fun PersistedSession.toAgentSession(): AgentSession {
    return AgentSession(
        id = this.id,
        name = this.name,
        createdAt = this.createdAt,
        lastActivityAt = this.lastActivityAt,
        isActive = this.isActive
    )
}
