package com.codesage.prompt.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AgentConfigLoaderTest {

    @Test
    fun `returns null when no config files exist`(@TempDir tempDir: Path) {
        val result = AgentConfigLoader.load(tempDir.toString())
        assertNull(result)
    }

    @Test
    fun `loads AGENTS md from project root`(@TempDir tempDir: Path) {
        val projectRoot = tempDir.toFile()
        File(projectRoot, "AGENTS.md").writeText("Use Kotlin style.")

        val result = AgentConfigLoader.load(projectRoot.absolutePath)
        assertNotNull(result)
        assertTrue(result!!.contains("Use Kotlin style."))
    }

    @Test
    fun `prefers AGENTS md over CLAUDE md`(@TempDir tempDir: Path) {
        val projectRoot = tempDir.toFile()
        File(projectRoot, "CLAUDE.md").writeText("Claude specific.")
        File(projectRoot, "AGENTS.md").writeText("AGENTS specific.")

        val result = AgentConfigLoader.load(projectRoot.absolutePath)
        assertNotNull(result)
        assertTrue(result!!.contains("AGENTS specific."))
        assertFalse(result.contains("Claude specific."))
    }

    @Test
    fun `falls back to codesage AGENTS md`(@TempDir tempDir: Path) {
        val projectRoot = tempDir.toFile()
        val codesageDir = File(projectRoot, ".codesage").apply { mkdirs() }
        File(codesageDir, "AGENTS.md").writeText("CodeSage project config.")

        val result = AgentConfigLoader.load(projectRoot.absolutePath)
        assertNotNull(result)
        assertTrue(result!!.contains("CodeSage project config."))
    }

    @Test
    fun `truncates overly large config`(@TempDir tempDir: Path) {
        val projectRoot = tempDir.toFile()
        File(projectRoot, "AGENTS.md").writeText("x".repeat(20_000))

        val result = AgentConfigLoader.load(projectRoot.absolutePath)
        assertNotNull(result)
        assertEquals(8_000, result!!.length)
    }
}
