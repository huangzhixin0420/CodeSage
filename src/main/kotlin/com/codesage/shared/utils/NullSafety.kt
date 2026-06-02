package com.codesage.shared.utils

/**
 * T0.6 修复：Null-safety 工具方法
 *
 * 集中项目里的 null-safety 模式，避免在 hot path 中散落的 `!!`。
 */
object NullSafety {

    /**
     * 获取 nullable 值或 fallback。
     *
     * 与 `value ?: fallback` 行为相同，但允许在调用点显式表达"如果为 null 则"
     * 的意图，可读性更好。
     *
     * 用法：`val name = NullSafety.orElse(session.name, "<unnamed>")`
     */
    inline fun <T : Any> orElse(value: T?, fallback: T): T = value ?: fallback

    /**
     * 获取 nullable 值或通过 supplier 计算的 fallback。
     *
     * 用法：`val displayName = NullSafety.orElseGet(session.name) { generate() }`
     */
    inline fun <T : Any> orElseGet(value: T?, supplier: () -> T): T = value ?: supplier()

    /**
     * 断言参数非空，失败时抛出带具体位置信息的异常。
     *
     * 与 `requireNotNull` 类似，但支持自定义异常类型。
     *
     * 用法：`NullSafety.requireNonNull(connection, "connection") { MCPNotConnectedException() }`
     */
    inline fun <T : Any> requireNonNull(
        value: T?,
        name: String,
        factory: () -> Throwable = { IllegalStateException("$name must not be null") }
    ): T {
        if (value == null) {
            throw factory()
        }
        return value
    }
}
