package com.codesage.tools.guardrails

import java.util.concurrent.ConcurrentHashMap

/**
 * 工具调用频率限制器
 *
 * - 同一工具连续调用超过阈值时触发限制
 * - 支持滑动窗口统计
 * - 可配置策略：WARN(继续但警告) / BLOCK(中断) / SKIP(跳过)
 * - 工具执行成功后重置该工具的连续调用计数
 */
class ToolRateLimiter(
    private val maxConsecutiveCalls: Int = 3,
    private val windowSizeMs: Long = 60_000,
    private val policy: RateLimitPolicy = RateLimitPolicy.WARN
) {

    enum class RateLimitPolicy {
        WARN,   // 允许继续，但发出警告
        BLOCK,  // 阻止执行，抛出异常
        SKIP    // 跳过执行，返回提示
    }

    data class CheckResult(
        val allowed: Boolean,
        val warning: String? = null
    )

    // 每个工具的调用历史（时间戳列表）
    private val callHistory = ConcurrentHashMap<String, MutableList<Long>>()

    // 记录每个工具最近一次被限制的时间（用于 BLOCK 策略的冷却判断）
    private val lastBlockedTime = ConcurrentHashMap<String, Long>()

    /**
     * 检查工具调用是否允许
     */
    fun check(toolName: String): CheckResult {
        val now = System.currentTimeMillis()

        // BLOCK 策略下，检查是否仍在冷却期
        if (policy == RateLimitPolicy.BLOCK) {
            val lastBlocked = lastBlockedTime[toolName]
            if (lastBlocked != null && now - lastBlocked < windowSizeMs) {
                return CheckResult(
                    allowed = false,
                    warning = "Tool '$toolName' is blocked due to consecutive call limit exceeded"
                )
            }
        }
        val history = callHistory.computeIfAbsent(toolName) { mutableListOf() }

        // 滑动窗口：移除过期时间戳
        history.removeAll { now - it > windowSizeMs }

        // 记录本次调用
        history.add(now)

        // 检查是否超过连续调用阈值
        return if (history.size > maxConsecutiveCalls) {
            when (policy) {
                RateLimitPolicy.WARN -> CheckResult(
                    allowed = true,
                    warning = "Warning: Tool '$toolName' has been called ${history.size} times within the window (limit: $maxConsecutiveCalls)"
                )

                RateLimitPolicy.BLOCK -> {
                    lastBlockedTime[toolName] = now
                    CheckResult(
                        allowed = false,
                        warning = "Blocked: Tool '$toolName' exceeded consecutive call limit ($maxConsecutiveCalls)"
                    )
                }

                RateLimitPolicy.SKIP -> CheckResult(
                    allowed = false,
                    warning = "Skipped: Tool '$toolName' exceeded consecutive call limit ($maxConsecutiveCalls)"
                )
            }
        } else {
            CheckResult(allowed = true, warning = null)
        }
    }

    /**
     * 记录工具执行成功，重置该工具的连续调用计数
     */
    fun recordSuccess(toolName: String) {
        callHistory.remove(toolName)
        lastBlockedTime.remove(toolName)
    }

    /**
     * 重置所有计数器
     */
    fun resetAll() {
        callHistory.clear()
        lastBlockedTime.clear()
    }

    /**
     * 获取指定工具在窗口内的调用次数
     */
    fun getCallCount(toolName: String): Int {
        val now = System.currentTimeMillis()
        return callHistory[toolName]?.count { now - it <= windowSizeMs } ?: 0
    }
}
