package com.codesage.persistence

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.AgentSession
import com.codesage.model.dto.Message
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*

/**
 * 会话恢复管理器
 * 在IDE启动时恢复之前的会话
 */
class SessionRestore(
    private val persistence: ConversationPersistence,
    private val agentCore: AgentCore
) {
    private val logger = Logger.getLogger<SessionRestore>()

    /**
     * 恢复策略
     */
    enum class RestoreStrategy {
        RESTORE_ALL,      // 恢复所有活跃会话
        RESTORE_LAST,     // 仅恢复最后一个活跃会话
        ASK_USER,         // 询问用户
        NONE              // 不恢复
    }

    /**
     * 恢复选项
     */
    data class RestoreOptions(
        val strategy: RestoreStrategy = RestoreStrategy.RESTORE_LAST,
        val maxAgeHours: Int = 24,        // 只恢复24小时内的会话
        val maxSessions: Int = 5          // 最多恢复5个会话
    )

    /**
     * 恢复会话
     */
    fun restore(options: RestoreOptions = RestoreOptions()): RestoreResult {
        if (options.strategy == RestoreStrategy.NONE) {
            return RestoreResult(skipped = true)
        }

        val cutoffTime = System.currentTimeMillis() - options.maxAgeHours * 3600 * 1000
        val allSessions = persistence.loadAllSessions()
            .filter { it.lastActivityAt >= cutoffTime }
            .sortedByDescending { it.lastActivityAt }

        if (allSessions.isEmpty()) {
            logger.info("No recent sessions to restore")
            return RestoreResult(restoredCount = 0)
        }

        val sessionsToRestore = when (options.strategy) {
            RestoreStrategy.RESTORE_ALL -> allSessions.take(options.maxSessions)
            RestoreStrategy.RESTORE_LAST -> allSessions.take(1)
            else -> allSessions.take(options.maxSessions)
        }

        var restoredCount = 0
        val restoredSessions = mutableListOf<AgentSession>()

        for (persisted in sessionsToRestore) {
            try {
                val session = restoreSingleSession(persisted)
                if (session != null) {
                    restoredCount++
                    restoredSessions.add(session)
                }
            } catch (e: Exception) {
                logger.error("Failed to restore session: ${persisted.id}", e)
            }
        }

        logger.info("Restored $restoredCount sessions")
        return RestoreResult(
            restoredCount = restoredCount,
            restoredSessions = restoredSessions,
            availableSessions = allSessions.size
        )
    }

    /**
     * 恢复单个会话
     */
    private fun restoreSingleSession(persisted: PersistedSession): AgentSession? {
        return agentCore.restoreSession(persisted)
    }

    /**
     * 自动保存当前会话（定期调用）
     */
    fun autoSaveCurrentSession() {
        try {
            val session = agentCore.getCurrentSession() ?: return
            val history = agentCore.getCurrentHistory()
            val hasUserMessage = history.any { it.role == com.codesage.model.dto.Role.USER }
            if (!hasUserMessage) return
            persistence.saveSession(session, history)
        } catch (e: Exception) {
            logger.error("Auto-save failed", e)
        }
    }

    /**
     * 启动自动保存定时器
     */
    fun startAutoSave(scope: CoroutineScope, intervalMs: Long = 30000) {
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                autoSaveCurrentSession()
            }
        }
        logger.info("Auto-save started (interval: ${intervalMs}ms)")
    }

    /**
     * 恢复结果
     */
    data class RestoreResult(
        val restoredCount: Int = 0,
        val restoredSessions: List<AgentSession> = emptyList(),
        val availableSessions: Int = 0,
        val skipped: Boolean = false
    )
}
