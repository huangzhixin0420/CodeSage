package com.codesage.shared.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 验证 [Logger] 包装类正确返回 IntelliJ Platform Logger。
 *
 * 历史教训：之前 Logger 包装 SLF4J/Logback，但 plugin classpath 里**没有
 * Logback 实现**，导致 NopLogger fallback——所有 logger.info/warn/error
 * 调用静默吞掉，根本没进 idea.log。这次改为 [com.intellij.openapi.diagnostic.Logger]
 * 直接走 IntelliJ 自己的日志系统，保证 idea.log 一定能看见。
 */
class LoggerTest {

    @Test
    fun `getLogger by class returns non-null logger`() {
        val logger = Logger.getLogger(LoggerTest::class.java)
        assertNotNull(logger, "Logger should be non-null")
    }

    @Test
    fun `getLogger by reified type returns non-null logger`() {
        val logger = Logger.getLogger<LoggerTest>()
        assertNotNull(logger)
    }

    @Test
    fun `getLogger by name returns non-null logger`() {
        val logger = Logger.getLogger("com.codesage.test.logger")
        assertNotNull(logger)
    }

    @Test
    fun `logger trace debug info warn 都不 throw`() {
        val logger = Logger.getLogger<LoggerTest>()
        // 不验证输出，只验证调用不抛。
        // 注：logger.error() 在测试环境下**故意**抛 AssertionError
        // （DefaultLogger.java:91，IntelliJ 为让测试捕获 error 级日志的 safety net），
        // 所以 error 级调用单独测。
        logger.trace("[LoggerTest] trace message")
        logger.debug("[LoggerTest] debug message")
        logger.info("[LoggerTest] info message")
        logger.warn("[LoggerTest] warn message")
    }

    @Test
    fun `SafeLogger swallows AssertionError so test mocks with expected errors do not break`() {
        // SafeLogger 包装：IntelliJ 的 DefaultLogger.error() 在 test env
        // 故意抛 AssertionError（防止测试静默吞 error）。SafeLogger 吞掉
        // 让 mock 失败路径测试（如 EnhancedAgentLoop "Context length exceeded"）
        // 不会被静默 error 干扰。
        // 生产环境 DefaultLogger.error() 不抛（isUnitTestMode=false），
        // 所以这个 swallow 不影响生产行为。
        val logger = Logger.getLogger<LoggerTest>()
        val cause = RuntimeException("test cause")
        // 以下调用在 IntelliJ raw logger 中会抛 AssertionError，
        // SafeLogger 应该吞掉，这里不应抛任何异常。
        logger.error("[LoggerTest] error without throwable - SafeLogger catches AssertionError")
        logger.error("[LoggerTest] error with throwable - SafeLogger catches AssertionError", cause)
        // 如果到了这行说明 SafeLogger 正确吞掉了
        assertTrue(true, "SafeLogger should swallow AssertionError so mock-based test failures don't propagate")
    }

    @Test
    fun `logger with throwable on trace debug info warn 不 throw`() {
        val logger = Logger.getLogger<LoggerTest>()
        val cause = RuntimeException("test cause")
        logger.info("[LoggerTest] info with cause", cause)
        logger.warn("[LoggerTest] warn with cause", cause)
    }
}
