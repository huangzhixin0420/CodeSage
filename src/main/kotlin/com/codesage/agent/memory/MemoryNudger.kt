package com.codesage.agent.memory

/**
 * 记忆提醒器（Memory Nudge）
 *
 * 参考 Hermes 的 `_turns_since_memory` 计数器设计：
 * 每 N 轮对话自动在系统提示中注入提醒，提示 Agent 回顾和整理记忆。
 */
class MemoryNudger(
    private val nudgeInterval: Int = DEFAULT_NUDGE_INTERVAL
) {

    private var turnsSinceMemory = 0

    /**
     * 每轮调用，增加计数
     * @return 如果达到提醒间隔，返回提醒文本；否则返回 null
     */
    fun onTurn(): String? {
        turnsSinceMemory++
        return if (turnsSinceMemory >= nudgeInterval) {
            turnsSinceMemory = 0
            NUDGE_MESSAGE
        } else null
    }

    /**
     * 手动触发提醒
     */
    fun nudgeNow(): String {
        turnsSinceMemory = 0
        return NUDGE_MESSAGE
    }

    /**
     * 重置计数器
     */
    fun reset() {
        turnsSinceMemory = 0
    }

    companion object {
        const val DEFAULT_NUDGE_INTERVAL = 8

        val NUDGE_MESSAGE = """
            [SYSTEM NOTE: You have persistent memory. Consider reviewing what you've learned about this user
            and this project. Use memory_search if you need to recall past decisions, preferences, or patterns.]
        """.trimIndent()
    }
}
