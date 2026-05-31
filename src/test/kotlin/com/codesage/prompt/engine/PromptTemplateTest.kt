package com.codesage.prompt.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PromptTemplateTest {

    @Test
    fun `render simple variable`() {
        val template = PromptTemplate("Hello, {{name}}!")
        val result = template.render(mapOf("name" to "World"))
        assertEquals("Hello, World!", result)
    }

    @Test
    fun `render multiple variables`() {
        val template = PromptTemplate("{{greeting}}, {{name}}! You have {{count}} messages.")
        val result = template.render(
            mapOf(
                "greeting" to "Hi",
                "name" to "Alice",
                "count" to 5
            )
        )
        assertEquals("Hi, Alice! You have 5 messages.", result)
    }

    @Test
    fun `render conditional block when true`() {
        val template = PromptTemplate("Start {{#if showDetails}}Details here{{/if}} End")
        val result = template.render(mapOf("showDetails" to true))
        assertEquals("Start Details here End", result)
    }

    @Test
    fun `render conditional block when false`() {
        val template = PromptTemplate("Start {{#if showDetails}}Details here{{/if}} End")
        val result = template.render(mapOf("showDetails" to false))
        assertEquals("Start  End", result)
    }

    @Test
    fun `render loop block`() {
        val template = PromptTemplate("Items: {{#each items}}- {{this}}\n{{/each}}")
        val result = template.render(mapOf("items" to listOf("a", "b", "c")))
        assertTrue(result.contains("- a"))
        assertTrue(result.contains("- b"))
        assertTrue(result.contains("- c"))
    }

    @Test
    fun `extract variables from template`() {
        val template = PromptTemplate("{{name}} {{#if active}}active{{/if}} {{#each items}}{{this}}{{/each}}")
        val vars = template.extractVariables()
        assertTrue(vars.contains("name"))
        assertTrue(vars.contains("active"))
        assertTrue(vars.contains("items"))
    }

    @Test
    fun `template builder works`() {
        val result = PromptTemplateBuilder()
            .section("Role", "You are a coding assistant.")
            .section("Tools", "You have access to tools.")
            .withVar("language", "Kotlin")
            .render()

        assertTrue(result.contains("## Role"))
        assertTrue(result.contains("## Tools"))
        assertTrue(result.contains("coding assistant"))
    }

    @Test
    fun `missing variable keeps placeholder`() {
        val template = PromptTemplate("Hello, {{name}}!")
        val result = template.render(emptyMap())
        assertEquals("Hello, {{name}}!", result)
    }
}
