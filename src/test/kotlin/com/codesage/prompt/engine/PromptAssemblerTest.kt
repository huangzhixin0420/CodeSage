package com.codesage.prompt.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path
import java.io.File

class PromptAssemblerTest {

    @Test
    fun `assemble basic prompt`() {
        val assembler = PromptAssembler()
        val prompt = assembler.assemble()

        assertTrue(prompt.contains("CodeSage"))
        assertTrue(prompt.contains("Guidelines"))
    }

    @Test
    fun `assemble with role`() {
        val assembler = PromptAssembler()
        val prompt = assembler.assemble(
            PromptAssembler.AssemblyContext(role = PromptRole.CODE_REVIEWER)
        )

        assertTrue(prompt.contains("Code Reviewer"))
        assertTrue(prompt.contains("CRITICAL"))
    }

    @Test
    fun `assemble with project context`() {
        val assembler = PromptAssembler()
        val prompt = assembler.assemble(
            PromptAssembler.AssemblyContext(
                projectLanguage = "Kotlin",
                projectFramework = "Spring Boot"
            )
        )

        assertTrue(prompt.contains("Kotlin"))
        assertTrue(prompt.contains("Spring Boot"))
    }

    @Test
    fun `assemble with capabilities`() {
        val assembler = PromptAssembler()
        val prompt = assembler.assemble(
            PromptAssembler.AssemblyContext(
                hasMemory = true,
                hasSubAgent = true,
                hasMCP = true
            )
        )

        assertTrue(prompt.contains("Memory"))
        assertTrue(prompt.contains("Sub-Agent"))
        assertTrue(prompt.contains("MCP"))
    }

    @Test
    fun `assemble with tools`() {
        val assembler = PromptAssembler()
        val prompt = assembler.assembleWithTools(emptyList())

        assertTrue(prompt.contains("CodeSage"))
    }

    @Test
    fun `assemble includes AGENTS md content when present`(@TempDir tempDir: Path) {
        val projectRoot = tempDir.toFile()
        File(projectRoot, "AGENTS.md").writeText("Always run tests before committing.")

        val assembler = PromptAssembler()
        val prompt = assembler.assemble(
            PromptAssembler.AssemblyContext(
                projectRoot = projectRoot.absolutePath
            )
        )

        assertTrue(prompt.contains("Project Agent Configuration"))
        assertTrue(prompt.contains("Always run tests before committing."))
    }

    @Test
    fun `default prompt contains ReAct parallel tools permission context budget and edit guidelines`() {
        val assembler = PromptAssembler()
        val prompt = assembler.assemble()

        assertTrue(prompt.contains("ReAct"), "Should mention ReAct protocol")
        assertTrue(prompt.contains("Thought"), "Should mention Thought step")
        assertTrue(prompt.contains("Action"), "Should mention Action step")
        assertTrue(prompt.contains("Observation"), "Should mention Observation step")
        assertTrue(prompt.contains("并行工具调用"), "Should mention parallel tool calling")
        assertTrue(prompt.contains("权限策略"), "Should mention permission policy")
        assertTrue(prompt.contains("上下文预算"), "Should mention context budget")
        assertTrue(prompt.contains("DO"), "Should mention DO guidelines")
        assertTrue(prompt.contains("DON'T"), "Should mention DON'T guidelines")
        assertTrue(prompt.contains("沙箱"), "Should mention sandbox")
    }

}
