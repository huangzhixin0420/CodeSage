package com.codesage.prompt.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

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
}
