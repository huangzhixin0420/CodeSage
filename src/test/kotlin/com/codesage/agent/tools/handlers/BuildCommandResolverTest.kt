package com.codesage.agent.tools.handlers

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * 验证 BuildCommandResolver 的 wrapper 优先解析逻辑。
 *
 * 历史 bug：RunLinterTool 等工具硬编码 `gradle` / `mvn`，
 * 在只有 wrapper 的环境会抛 IOException: Cannot run program "gradle"。
 */
class BuildCommandResolverTest {

    @Test
    fun `gradleCommand should prefer gradlew when wrapper exists`(@TempDir tempDir: Path) {
        File(tempDir.toFile(), "gradlew").writeText("#!/bin/sh\necho fake")
        val cmd = BuildCommandResolver.gradleCommand(tempDir.toString(), listOf("check"))
        assertEquals(listOf("./gradlew", "check"), cmd)
    }

    @Test
    fun `gradleCommand should fall back to system gradle when no wrapper`(@TempDir tempDir: Path) {
        // 没有 gradlew，应当回退到全局 gradle
        val cmd = BuildCommandResolver.gradleCommand(tempDir.toString(), listOf("check", "--info"))
        assertEquals(listOf("gradle", "check", "--info"), cmd)
    }

    @Test
    fun `gradleCommand should also detect gradlew bat for Windows`(@TempDir tempDir: Path) {
        // Windows 风格：gradlew.bat
        File(tempDir.toFile(), "gradlew.bat").writeText("@echo off")
        val cmd = BuildCommandResolver.gradleCommand(tempDir.toString(), listOf("check"))
        assertEquals(listOf("./gradlew", "check"), cmd)
    }

    @Test
    fun `mavenCommand should prefer mvnw when wrapper exists`(@TempDir tempDir: Path) {
        File(tempDir.toFile(), "mvnw").writeText("#!/bin/sh\necho fake")
        val cmd = BuildCommandResolver.mavenCommand(tempDir.toString(), listOf("-B", "test"))
        assertEquals(listOf("./mvnw", "-B", "test"), cmd)
    }

    @Test
    fun `mavenCommand should fall back to system mvn when no wrapper`(@TempDir tempDir: Path) {
        val cmd = BuildCommandResolver.mavenCommand(tempDir.toString(), listOf("-B", "test"))
        assertEquals(listOf("mvn", "-B", "test"), cmd)
    }

    @Test
    fun `resolver should return safe command when working dir is invalid`() {
        // 路径不存在，应当静默回退到全局命令（不抛）
        val cmd = BuildCommandResolver.gradleCommand("/path/that/does/not/exist", listOf("check"))
        assertEquals(listOf("gradle", "check"), cmd)
    }
}
