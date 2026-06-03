package com.codesage.skill.builtin

import com.codesage.skill.ExecutionContext
import com.codesage.skill.SkillInput
import com.codesage.skill.SkillResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * TreeBuiltinSkill 行为测试。
 *
 * 之前 YAML 里硬编码 `command: "tree"`，但 tree 在 macOS 默认没装，
 * 导致 Plugin 在 macOS 跑不动。这次用纯 Kotlin 实现，不依赖外部命令。
 */
class TreeBuiltinSkillTest {

    private val skill = TreeBuiltinSkill()

    /**
     * 临时构造一个小型项目结构用于测试：
     *   testDir/
     *   ├── src/
     *   │   └── main.kt
     *   ├── test/
     *   │   └── test.kt
     *   └── README.md
     */
    private fun createTestProject(root: File) {
        val src = File(root, "src").also { it.mkdirs() }
        val test = File(root, "test").also { it.mkdirs() }
        File(src, "main.kt").writeText("fun main() {}")
        File(test, "test.kt").writeText("fun test() {}")
        File(root, "README.md").writeText("# Project")
    }

    @Test
    fun `tree should list project structure as ascii tree`(@TempDir tempDir: Path) = runBlocking {
        val projectDir = tempDir.toFile()
        createTestProject(projectDir)

        val result = skill.execute(
            input = SkillInput(mapOf("path" to projectDir.absolutePath, "depth" to 5)),
            context = ExecutionContext(projectPath = projectDir.absolutePath)
        )
        assertTrue(result is SkillResult.Success, "Should succeed, got: $result")
        val output = (result as SkillResult.Success).output["output"] as String

        // 验证根目录出现
        assertTrue(output.contains("${projectDir.name}/"), "Output should contain root dir name")
        // 验证子目录出现
        assertTrue(output.contains("src/"), "Output should contain src/")
        assertTrue(output.contains("test/"), "Output should contain test/")
        // 验证文件出现
        assertTrue(output.contains("main.kt"), "Output should contain main.kt")
        assertTrue(output.contains("test.kt"), "Output should contain test.kt")
        assertTrue(output.contains("README.md"), "Output should contain README.md")
        // 验证有 ASCII 树字符
        assertTrue(
            output.contains("├──") || output.contains("└──"),
            "Output should use tree-style connectors, got: $output"
        )
    }

    @Test
    fun `tree should respect depth limit`(@TempDir tempDir: Path) = runBlocking {
        val projectDir = tempDir.toFile()
        // 创建 3 层目录
        val deep = File(projectDir, "a/b/c")
        deep.mkdirs()
        File(deep, "deep.kt").writeText("// deep")

        // depth=1 只能看到 a/
        val result1 = skill.execute(
            input = SkillInput(mapOf("path" to projectDir.absolutePath, "depth" to 1)),
            context = ExecutionContext(projectPath = projectDir.absolutePath)
        )
        val output1 = (result1 as SkillResult.Success).output["output"] as String
        assertTrue(output1.contains("a/"), "depth=1 should show a/")
        assertFalse(output1.contains("deep.kt"), "depth=1 should NOT show deep.kt")

        // depth=4 看到 a/b/c/deep.kt
        val result2 = skill.execute(
            input = SkillInput(mapOf("path" to projectDir.absolutePath, "depth" to 4)),
            context = ExecutionContext(projectPath = projectDir.absolutePath)
        )
        val output2 = (result2 as SkillResult.Success).output["output"] as String
        assertTrue(output2.contains("deep.kt"), "depth=4 should show deep.kt")
    }

    @Test
    fun `tree should skip noise directories like git and build`(@TempDir tempDir: Path) = runBlocking {
        val projectDir = tempDir.toFile()
        File(projectDir, "src").mkdirs()
        File(projectDir, "src/main.kt").writeText("// main")
        // 噪音目录
        File(projectDir, ".git").mkdirs()
        File(projectDir, ".git/config").writeText("git config")
        File(projectDir, "build").mkdirs()
        File(projectDir, "build/output.jar").writeText("jar")
        File(projectDir, "node_modules").mkdirs()
        File(projectDir, "node_modules/lib.js").writeText("lib")

        val result = skill.execute(
            input = SkillInput(mapOf("path" to projectDir.absolutePath, "depth" to 5)),
            context = ExecutionContext(projectPath = projectDir.absolutePath)
        )
        val output = (result as SkillResult.Success).output["output"] as String

        assertTrue(output.contains("src/"), "src should appear")
        assertFalse(output.contains(".git"), "noise .git should be skipped, got: $output")
        assertFalse(output.contains("build"), "noise build should be skipped")
        assertFalse(output.contains("node_modules"), "noise node_modules should be skipped")
        assertFalse(output.contains("output.jar"), "noise file inside build should be skipped")
    }

    @Test
    fun `tree should return fileCount and dirCount`(@TempDir tempDir: Path) = runBlocking {
        val projectDir = tempDir.toFile()
        createTestProject(projectDir)

        val result = skill.execute(
            input = SkillInput(mapOf("path" to projectDir.absolutePath, "depth" to 5)),
            context = ExecutionContext(projectPath = projectDir.absolutePath)
        )
        val data = (result as SkillResult.Success).output
        // 3 文件 (main.kt, test.kt, README.md) + 2 目录 (src, test) = 但我们从 root 算起还有 root 目录本身
        val fileCount = data["fileCount"] as Int
        val dirCount = data["dirCount"] as Int
        assertTrue(fileCount >= 3, "fileCount should be >= 3, got $fileCount")
        assertTrue(dirCount >= 2, "dirCount should be >= 2, got $dirCount")
    }

    @Test
    fun `tree should fail for nonexistent path`(@TempDir tempDir: Path) = runBlocking {
        val projectDir = tempDir.toFile()
        val nonexistent = File(projectDir, "does_not_exist")

        val result = skill.execute(
            input = SkillInput(mapOf("path" to nonexistent.absolutePath, "depth" to 3)),
            context = ExecutionContext(projectPath = projectDir.absolutePath)
        )
        assertTrue(result is SkillResult.Failure, "Should fail for nonexistent path")
        val msg = (result as SkillResult.Failure).error
        assertTrue(msg.contains("does not exist"), "Error should mention 'does not exist', got: $msg")
    }

    @Test
    fun `tree should fail for file (not directory) path`(@TempDir tempDir: Path) = runBlocking {
        val projectDir = tempDir.toFile()
        val file = File(projectDir, "single.kt")
        file.writeText("// a file")

        val result = skill.execute(
            input = SkillInput(mapOf("path" to file.absolutePath, "depth" to 3)),
            context = ExecutionContext(projectPath = projectDir.absolutePath)
        )
        assertTrue(result is SkillResult.Failure, "Should fail when path is a file, not a dir")
        assertTrue((result as SkillResult.Failure).error.contains("not a directory"))
    }

    @Test
    fun `tree should fail for missing path parameter`() = runBlocking {
        val result = skill.execute(
            input = SkillInput(emptyMap()),
            context = ExecutionContext(projectPath = "/tmp")
        )
        assertTrue(result is SkillResult.Failure, "Should fail for missing path")
        assertTrue((result as SkillResult.Failure).error.contains("path"))
    }
}
