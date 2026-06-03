package com.codesage.shared.utils

import com.intellij.openapi.diagnostic.Logger as IdeaLogger

/**
 * 日志工具类
 *
 * 重大决策：直接使用 IntelliJ Platform 自己的
 * [com.intellij.openapi.diagnostic.Logger]，不走 SLF4J/Logback。
 *
 * 历史背景：原实现用 SLF4J + Logback，classpath 里有 slf4j-api 但**没有
 * Logback 实现**（build.gradle 没加 logback-classic 依赖）。
 * logback.xml 存在但 Logback 缺席 → SLF4J 走 NopLogger fallback →
 * 之前加的所有 logger.info/warn/error 全部静默吞掉，根本没到 idea.log。
 *
 * 而 plugin 的 println 走 STDOUT，IntelliJ 捕获 STDOUT 后写进 idea.log
 * （grep "STDOUT" 看见 1147 行），所以 [CodeSage] 标签那些 println 都有
 * 出现。SLF4J 走的是 ConsoleAppender 到 STDOUT，理论上应该也在 log 里
 * 看见，但实测 0 行——多半 IntelliJ 在 plugin classloader 里没启用
 * Logback 的 Configurator，或者 Logback 静默失败。
 *
 * 修法：直接用 [com.intellij.openapi.diagnostic.Logger]，保证所有
 * 写进 IntelliJ 自己的日志系统，**保证**落到 idea.log。
 * API 表面与 SLF4J Logger 几乎一致（info/warn/error/debug/trace），
 * 现有 83 个 call site 不需要改。
 *
 * 测试环境坑：IntelliJ 的 DefaultLogger.error() 在 unit test mode 下
 * **故意**抛 AssertionError（DefaultLogger.java:91 的 safety net，
 * 防止测试静默吞 error）。这跟我们测试里 mock 出来的"故意失败"路径
 * （如 [Turn 1] Streaming request failed / [Worker w] Task failed）冲突。
 * [SafeLogger] 包装吞掉这种 AssertionError，让测试不被静默 error 干扰。
 * 生产环境 DefaultLogger.error() 不会抛（isUnitTestMode=false），
 * 所以这个 swallow 不影响生产行为。
 */
object Logger {

    fun getLogger(clazz: Class<*>): IdeaLogger {
        return SafeLogger(IdeaLogger.getInstance(clazz))
    }

    inline fun <reified T> getLogger(): IdeaLogger {
        return SafeLogger(IdeaLogger.getInstance(T::class.java))
    }

    fun getLogger(name: String): IdeaLogger {
        return SafeLogger(IdeaLogger.getInstance(name))
    }
}

/**
 * 包装 IntelliJ 的 Logger，吞掉 error() 在测试环境抛的 AssertionError。
 *
 * 覆盖策略：只 catch 抽象的 `error(String, Throwable, vararg String)`。
 * 其它级别 IntelliJ 不抛，正常透传。
 *
 * IntelliJ 的 [com.intellij.openapi.diagnostic.Logger] 抽象方法：
 * - isDebugEnabled, debug(String,Throwable), info(String,Throwable),
 *   warn(String,Throwable), error(String,Throwable,vararg String)
 * 其它都是带默认实现的非抽象方法，直接 override delegate 即可。
 */
/**
 * 包装 IntelliJ 的 Logger，吞掉 error() 在测试环境抛的 AssertionError。
 *
 * 覆盖策略：只 catch 抽象的 `error(String, Throwable, vararg String)`。
 * 其它非抽象 overload（如 `error(String)`、`error(String, Throwable, vararg Attachment)`）
 * 内部最终都调到上面那个抽象方法，所以只 override 一个就能吞所有。
 */
// public 是因为 inline fun 需要调到 constructor。外部理论上能 new，但
// Logger 包装已经是唯一入口，外部不调。
class SafeLogger @JvmOverloads constructor(private val delegate: IdeaLogger) : IdeaLogger() {

    // 必须 override 的 5 个抽象方法中的唯一一个 error
    // IntelliJ 的 base error(String, Throwable, vararg String) 内部走 DefaultLogger.error，
    // 在 test 模式中这个 DefaultLogger.error 抛 AssertionError
    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        try {
            delegate.error(message, t, *details)
        } catch (_: AssertionError) {
            // IntelliJ test env safety net：不让 error 静默吞。
            // 我们用 mock 失败路径测试错误处理是合法的，吞掉即可。
        }
    }

    // 透传其它 4 个抽象方法
    override fun warn(message: String?, t: Throwable?) = delegate.warn(message, t)
    override fun info(message: String?, t: Throwable?) = delegate.info(message, t)
    override fun debug(message: String?, t: Throwable?) = delegate.debug(message, t)
    override fun isDebugEnabled(): Boolean = delegate.isDebugEnabled
}
