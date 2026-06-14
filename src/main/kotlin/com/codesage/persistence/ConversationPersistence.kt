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
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

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

    // 跟踪所有 in-flight 写入任务，供 awaitInFlightWrites() / shutdown() 等待
    private val inFlightWrites = ConcurrentHashMap.newKeySet<Future<*>>()

    // 用于检测持久化器是否已关闭，避免向已关闭的线程池提交任务
    private val isShutdown: Boolean
        get() = ioExecutor.isShutdown

    init {
        storageDir.mkdirs()
        loadAllSessions()
    }

    /**
     * 持久化会话（异步，不阻塞调用线程）
     *
     * T0.3 修复（对应 CodeReview #3/#4/#5）：
     * 1. 原子写入：renameTo 返回 false 时保留 tempFile 并记录错误（不静默丢失）
     * 2. 使用 Future 跟踪 in-flight 任务，shutdown() 可以等待其完成
     * 3. 向已关闭的线程池提交任务时，捕获 RejectedExecutionException，
     *    避免异常扩散到调用方（如 AgentCore）导致“Failed to save session ... asynchronously”报错。
     */
    fun saveSession(session: AgentSession, messages: List<Message>) {
        // save 不能取消 delete 的标记。一旦会话被 delete，
        // 所有后续的 save 都应被拒绝，避免“删除后复活”这类数据泄漏。
        // 语义与 Option B 一致：delete 是永久操作。
        if (session.id in deletedSessionIds) {
            logger.debug("Skipping save for deleted session: ${session.id}")
            return
        }

        // 如果执行器已关闭，优雅跳过，避免 RejectedExecutionException 扩散到调用方。
        // 注意：isShutdown 与 submit 之间仍有竞态，外层 try/catch 做兜底。
        if (isShutdown) {
            logger.debug("Skipping save for session ${session.id}: persistence executor is shut down")
            return
        }

        val persisted = buildPersisted(session, messages)
        sessionCache[session.id] = persisted

        val task = Runnable {
            // 如果该会话已被删除，跳过写入
            if (session.id in deletedSessionIds) {
                logger.debug("Skipping save for deleted session: ${session.id}")
                return@Runnable
            }
            try {
                writeAtomically(persisted)
                logger.debug("Saved session: ${session.id} (${messages.size} messages)")
            } catch (e: Exception) {
                logger.error("Failed to save session: ${session.id}", e)
            }
        }

        try {
            val future = ioExecutor.submit(task)
            inFlightWrites.add(future)
        } catch (e: RejectedExecutionException) {
            // shutdown 期间的竞态：isShutdown 检查之后、submit 之前线程池被关闭。
            // 这里静默处理：缓存已更新，磁盘写入被放弃；调用方不会收到异常。
            logger.debug("Persistence executor rejected save for session ${session.id}: ${e.message}")
        }
    }

    private fun buildPersisted(session: AgentSession, messages: List<Message>): PersistedSession =
        PersistedSession(
            id = session.id,
            name = session.name,
            createdAt = session.createdAt,
            lastActivityAt = session.lastActivityAt,
            isActive = session.isActive,
            messages = messages.map { it.toPersistedMessage() },
            metadata = SessionMetadata(
                messageCount = messages.size,
                saveVersion = CURRENT_VERSION
            ),
            // O5.2: 取首条 user 消息的纯文本前 30 字,去除空白
            previewText = extractPreviewText(messages)
        )

    /**
     * O5.2: 从消息列表提取会话预览文本 — 首条 USER 角色消息去除空白后截前 30 字。
     * 若无 user 消息则返回空串(后端 AgentToolWindowPanel 在 fallback 时会用会话名)。
     */
    private fun extractPreviewText(messages: List<Message>): String {
        val firstUser = messages.firstOrNull { it.role.name == "USER" } ?: return ""
        val raw = (firstUser.content ?: "").trim().replace(Regex("\\s+"), " ")
        if (raw.isEmpty()) return ""
        val max = 30
        return if (raw.length <= max) raw else raw.substring(0, max) + "…"
    }

    /**
     * 原子写入：先写临时文件，再重命名。
     * 修复：renameTo 跨平台/跨设备可能返回 false，原代码静默丢失。
     * 新行为：rename 失败时保留 tempFile 并抛出异常，让调用方/记录器看到。
     */
    @Throws(java.io.IOException::class)
    private fun writeAtomically(persisted: PersistedSession) {
        val file = getSessionFile(persisted.id)
        // 2026-06 修复: sub-agent 临时目录场景下, 异步 save 跑到这里时父目录可能已被
        // SubAgentExecutor 的 finally deleteRecursively() 删掉 (race condition)。
        // mkdirs() 在父目录已存在时是 no-op; 不存在时重建, 让 save 不再因找不到目录而炸。
        val parent = file.parentFile
        if (parent == null) {
            throw java.io.IOException("Session file has no parent directory: ${file.absolutePath}")
        }
        if (!parent.exists() && !parent.mkdirs()) {
            // 极端情况: parent 在 exists() 之后, mkdirs() 之前被另一个线程删了。
            // 再次尝试, 接受"并发删除"语义 — 如果两次都失败就放弃这一轮, 下一轮 save 重试。
            if (!parent.exists()) {
                throw java.io.IOException(
                    "Failed to create parent dir for session file: ${parent.absolutePath}"
                )
            }
        }
        val tempFile = File(parent, "${file.name}.tmp")
        try {
            tempFile.writeText(json.encodeToString(persisted))
            val renamed = tempFile.renameTo(file)
            if (!renamed) {
                // 跨设备/权限问题：保留 tempFile 以供恢复
                logger.error(
                    "Atomic rename failed for session ${persisted.id}. " +
                            "Kept temp file: ${tempFile.absolutePath}"
                )
                throw java.io.IOException(
                    "Failed to rename ${tempFile.path} to ${file.path}; " +
                            "temp file preserved"
                )
            }
        } catch (e: Exception) {
            // 如果是 rename 失败以外的其他原因，也保留 tempFile
            if (tempFile.exists() && e !is java.io.IOException) {
                logger.error(
                    "Write failed for session ${persisted.id}, tempFile preserved at ${tempFile.absolutePath}",
                    e
                )
            }
            throw e
        }
    }

    /**
     * 同步持久化会话（用于关键路径需要确认保存的场景）
     */
    suspend fun saveSessionSync(session: AgentSession, messages: List<Message>) = withContext(Dispatchers.IO) {
        if (session.id in deletedSessionIds) {
            logger.debug("Skipping sync save for deleted session: ${session.id}")
            return@withContext
        }
        val persisted = buildPersisted(session, messages)
        sessionCache[session.id] = persisted

        try {
            writeAtomically(persisted)
            logger.debug("Saved session sync: ${session.id} (${messages.size} messages)")
        } catch (e: Exception) {
            logger.error("Failed to save session: ${session.id}", e)
        }
    }

    /**
     * 加载会话
     *
     * T0.3 修复：在加入缓存前检查 deletedSessionIds，避免被刚 delete 的 session 被 “复活”
     */
    fun loadSession(sessionId: String): PersistedSession? {
        if (sessionId in deletedSessionIds) {
            return null
        }
        // 先查缓存
        sessionCache[sessionId]?.let { return it }
        if (sessionId in deletedSessionIds) return null  // double-check after cache miss

        // 从磁盘加载
        return try {
            val file = getSessionFile(sessionId)
            if (!file.exists()) return null
            if (sessionId in deletedSessionIds) return null  // 再检查一次

            val persisted = json.decodeFromString<PersistedSession>(file.readText())
            if (sessionId in deletedSessionIds) return null  // decode 后再检查
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
                        val parsed = json.decodeFromString<PersistedSession>(file.readText())
                        // T0.11 修复（CodeReview Medium #27）：反序列化后做基础校验
                        // 避免磁盘上的损坏/旧版本文件进入内存后导致后续 NPE
                        if (parsed.id.isBlank()) {
                            logger.warn("Skipping session file with blank id: ${file.name}")
                            return@mapNotNull null
                        }
                        if (parsed.metadata.saveVersion > CURRENT_VERSION) {
                            logger.warn(
                                "Session file ${file.name} has newer version " +
                                    "${parsed.metadata.saveVersion} > $CURRENT_VERSION; skipping"
                            )
                            return@mapNotNull null
                        }
                        file.nameWithoutExtension to parsed
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
     *
     * T0.3 修复：先删文件再清理缓存，避免 loadSession 在中间窗口从磁盘重新加载后将其放入缓存。
     */
    fun deleteSession(sessionId: String): Boolean {
        deletedSessionIds.add(sessionId)  // 先标记，防止任何后续 save 写入
        return try {
            val file = getSessionFile(sessionId)
            val fileDeleted = if (file.exists()) {
                file.delete()
            } else {
                true  // 文件不存在，视为已删除
            }
            // 文件删除成功后才从缓存移除
            sessionCache.remove(sessionId)
            fileDeleted
        } catch (e: Exception) {
            logger.error("Failed to delete session: $sessionId", e)
            // 异常情况下，保留 deletedSessionIds 标记，清理缓存
            sessionCache.remove(sessionId)
            false
        }
    }

    /**
     * 清理旧会话（保留最近N个）
     */
    fun cleanupOldSessions(keepCount: Int = DEFAULT_KEEP_SESSIONS) {
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
     * 2026-06 新增: 等待所有 in-flight 写入完成 (但不拒绝新写入)。
     *
     * 用法: SubAgentExecutor 在 finally deleteRecursively() 之前调用,
     * 避免异步 save 跑到一半父目录就没了。
     *
     * @param timeoutMs 最长等待时间 (毫秒)
     * @return 调用时的 in-flight 写入数 (供日志)
     */
    fun awaitInFlightWrites(timeoutMs: Long = 5000L): Int {
        val initial = inFlightWrites.count { !it.isDone }
        if (initial == 0) return 0

        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (inFlightWrites.any { !it.isDone } && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // 清理已完成的任务，避免集合随时间无限增长
        inFlightWrites.removeIf { it.isDone }

        val remaining = inFlightWrites.count { !it.isDone }
        if (remaining > 0) {
            logger.warn(
                "awaitInFlightWrites: timed out after ${timeoutMs}ms, " +
                "$remaining writes still in flight (writeAtomically mkdirs fallback will catch it)"
            )
        } else {
            logger.debug("awaitInFlightWrites: drained $initial writes")
        }
        return initial
    }

    /**
     * 关闭持久化管理器。
     *
     * T0.3 修复（对应 CodeReview #3）：
     * 1. 调用 shutdown() 后不接收新任务
     * 2. awaitTermination(5s) 等待现有 in-flight 任务完成
     * 3. 超时后 shutdownNow() 强制中断
     * 4. 最后清空 in-flight 跟踪集合，避免已取消/未执行的任务造成内存泄漏
     */
    fun shutdown() {
        ioExecutor.shutdown()
        try {
            if (!ioExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warn("ConversationPersistence executor did not terminate in ${SHUTDOWN_TIMEOUT_SECONDS}s, forcing shutdown")
                ioExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            ioExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        } finally {
            // 已取消或尚未执行的任务不会再运行，清空跟踪防止内存泄漏与 awaitInFlightWrites 永远等待
            inFlightWrites.clear()
        }
    }

    companion object {
        private const val CURRENT_VERSION = 1

        // T0.10 修复（CodeReview Medium #19）：提取魔法数字
        // 显式命名常量让运维/调参更容易，也避免在代码中散落难以追踪的字面量。
        /** shutdown 等待 in-flight 写入完成的超时（秒） */
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L

        /** cleanupOldSessions 默认保留的会话数（与 PluginConfig.conversationCleanupKeep 默认对齐） */
        const val DEFAULT_KEEP_SESSIONS = 50
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
    val metadata: SessionMetadata,
    /**
     * O5.2: 会话预览文本(首条用户消息前 ~30 字),会话列表弹层用。
     * 默认空字符串以便旧版 json 反序列化兼容。
     */
    val previewText: String = ""
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
                arguments = it.arguments,
                summary = null,  // 不持久化 (前端用一次性流式数据)
                icon = null,
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
