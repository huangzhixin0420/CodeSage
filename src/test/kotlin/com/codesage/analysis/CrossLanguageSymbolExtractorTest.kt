package com.codesage.analysis

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CrossLanguageSymbolExtractorTest {

    @Test
    fun `extract JSON top-level keys`() {
        val content = """
            {
              "name": "CodeSage",
              "version": "1.0",
              "nested": { "ignored": true }
            }
        """.trimIndent()
        val symbols = CrossLanguageSymbolExtractor.extractFromContent("/project/config.json", "json", content)
        assertTrue(symbols.any { it.name == "name" })
        assertTrue(symbols.any { it.name == "version" })
        assertFalse(symbols.any { it.name == "ignored" })
        assertEquals(PSIAnalyzer.SymbolType.FIELD, symbols.first().type)
    }

    @Test
    fun `extract YAML top-level keys`() {
        val content = """
            server:
              port: 8080
            database:
              url: jdbc:sqlite:test
        """.trimIndent()
        val symbols = CrossLanguageSymbolExtractor.extractFromContent("/project/config.yaml", "yaml", content)
        assertTrue(symbols.any { it.name == "server" })
        assertTrue(symbols.any { it.name == "database" })
        assertFalse(symbols.any { it.name == "port" })
    }

    @Test
    fun `extract SQL create statements`() {
        val content = """
            CREATE TABLE users (id INT);
            CREATE OR REPLACE FUNCTION get_user() RETURNS INT AS $$ BEGIN RETURN 1; END; $$;
            CREATE INDEX idx_name ON users(name);
        """.trimIndent()
        val symbols = CrossLanguageSymbolExtractor.extractFromContent("/project/schema.sql", "sql", content)
        assertTrue(symbols.any { it.name == "users" && it.type == PSIAnalyzer.SymbolType.CLASS })
        assertTrue(symbols.any { it.name == "get_user" && it.type == PSIAnalyzer.SymbolType.METHOD })
        assertTrue(symbols.any { it.name == "idx_name" && it.type == PSIAnalyzer.SymbolType.CLASS })
    }

    @Test
    fun `extract Markdown headings`() {
        val content = """
            # Introduction
            Some text
            ## Getting Started
            ### API Reference
        """.trimIndent()
        val symbols = CrossLanguageSymbolExtractor.extractFromContent("/project/README.md", "md", content)
        assertEquals(3, symbols.size)
        assertTrue(symbols.any { it.name == "Introduction" })
        assertTrue(symbols.any { it.name == "Getting Started" })
        assertTrue(symbols.any { it.name == "API Reference" })
    }

    @Test
    fun `extract Vue and Svelte component names`() {
        val vue = CrossLanguageSymbolExtractor.extractFromContent("/src/App.vue", "vue", "", fileName = "App")
        assertEquals(1, vue.size)
        assertEquals("App", vue[0].name)
        assertEquals(PSIAnalyzer.SymbolType.CLASS, vue[0].type)

        val svelte =
            CrossLanguageSymbolExtractor.extractFromContent("/src/Widget.svelte", "svelte", "", fileName = "Widget")
        assertEquals("Widget", svelte[0].name)
    }

    @Test
    fun `ignore invalid identifiers`() {
        val content = """{"123bad": "x", "good_one": "y"}"""
        val symbols = CrossLanguageSymbolExtractor.extractFromContent("/project/config.json", "json", content)
        assertEquals(1, symbols.size)
        assertEquals("good_one", symbols[0].name)
    }
}
