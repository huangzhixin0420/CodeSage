package com.codesage.ide.ui.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ResourceInlinerTest {

    @Test
    fun `font awesome css should be inlined with file font references`() {
        val rawHtml =
            """<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css" />"""
        val result = ResourceInliner.inlineResources(rawHtml)

        // Should replace link with style tag
        assertTrue(result.contains("<style>"), "Should contain <style> tag")
        assertTrue(result.contains("</style>"), "Should contain </style> tag")

        // Should replace font urls with base64 data URIs
        assertTrue(result.contains("data:font/"), "Should contain base64 font data URIs")

        // Should NOT contain unresolved relative font paths
        assertFalse(result.contains("../webfonts/"), "Should not contain relative webfonts paths")
    }

    @Test
    fun `highlight js should be inlined as data uri`() {
        val rawHtml =
            """<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>"""
        val result = ResourceInliner.inlineResources(rawHtml)
        assertTrue(result.contains("data:text/javascript;base64,"), "Should contain JS data URI")
    }

    @Test
    fun `inlined html should be under 2mb`() {
        val rawHtml = javaClass.classLoader.getResourceAsStream("webui/chat.html")
            ?.use { it.bufferedReader(Charsets.UTF_8).readText() }
            ?: return
        val result = ResourceInliner.inlineResources(rawHtml)
        assertTrue(result.length < 2 * 1024 * 1024, "Inlined HTML should be under 2MB, actual: ${result.length} bytes")
    }
}
