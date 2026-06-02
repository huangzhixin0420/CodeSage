package com.codesage.agent.tools.handlers

import java.io.File

/**
 * 构建命令解析器：解决 wrapper 优先于全局命令的问题
 *
 * 历史背景：本机经常只装 gradle/maven 的 wrapper（./gradlew, ./mvnw），
 * 没有全局 `gradle` / `mvn` 命令。如果工具硬编码全局命令名，会在
 * `ProcessBuilder.start()` 抛 `IOException: Cannot run program "gradle"`，
 * 表现为"工具执行失败"但根因不直观。
 *
 * 统一约定：所有调 gradle / mvn 的工具都应该走这个 resolver：
 * 1. 工作目录存在 wrapper 脚本 → 用 wrapper（确保 build 用项目锁定的版本）
 * 2. 否则回退到系统命令
 *
 * 这样在 CI / 用户机器上行为一致，且避免"项目有 gradlew 但工具尝试用 gradle"
 * 这种用户难以排查的失败。
 */
object BuildCommandResolver {

    /**
     * 构造 gradle 命令。优先用 `./gradlew`，否则回退到 `gradle`。
     *
     * @param workingDir 项目根目录（包含 build.gradle[.kts] 的目录）
     * @param args 任务和额外参数（不要包含二进制名本身）
     * @return 完整的命令行（首元素是二进制名）
     */
    fun gradleCommand(workingDir: String, args: List<String>): List<String> {
        val binary = if (hasWrapper(workingDir, "gradlew")) "./gradlew" else "gradle"
        return listOf(binary) + args
    }

    /**
     * 构造 maven 命令。优先用 `./mvnw`，否则回退到 `mvn`。
     */
    fun mavenCommand(workingDir: String, args: List<String>): List<String> {
        val binary = if (hasWrapper(workingDir, "mvnw")) "./mvnw" else "mvn"
        return listOf(binary) + args
    }

    /**
     * 检查 wrapper 脚本是否存在且可执行。
     * 在 Windows 上 gradlew 可能是 gradlew.bat，这里也一并检查。
     */
    private fun hasWrapper(workingDir: String, wrapperName: String): Boolean {
        val dir = File(workingDir)
        if (!dir.isDirectory) return false
        val unixWrapper = File(dir, wrapperName)
        if (unixWrapper.isFile) return true
        // Windows 兼容
        val windowsWrapper = File(dir, "$wrapperName.bat")
        if (windowsWrapper.isFile) return true
        return false
    }
}
