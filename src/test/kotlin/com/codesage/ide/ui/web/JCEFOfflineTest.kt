package com.codesage.ide.ui.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * 验证 JCEF 离线化相关组件的正确性。
 */
class JCEFOfflineTest {

    @Test
    fun `ResourceInliner should replace Tailwind CDN with data URI link tag`() {
        val html = """<html><head><script src="https://cdn.tailwindcss.com"></script></head></html>"""
        val result = ResourceInliner.inlineResources(html)
        assertTrue(
            result.contains("<link rel=\"stylesheet\" href=\"data:text/css;base64,"),
            "Tailwind should be replaced with a base64 data URI link tag"
        )
        assertFalse(
            result.contains("<script src=\"https://cdn.tailwindcss.com\""),
            "Original Tailwind script tag should be removed"
        )
    }

    @Test
    fun `ResourceInliner should replace markdown-it CDN with data URI`() {
        val html =
            """<html><head><script src="https://cdnjs.cloudflare.com/ajax/libs/markdown-it/14.1.0/markdown-it.min.js"></script></head></html>"""
        val result = ResourceInliner.inlineResources(html)
        assertTrue(
            result.contains("data:text/javascript;base64,"),
            "markdown-it.js should be replaced with a base64 data URI"
        )
        assertFalse(
            result.contains("https://cdnjs.cloudflare.com/ajax/libs/markdown-it/14.1.0/markdown-it.min.js"),
            "Original markdown-it CDN URL should be removed"
        )
    }

    @Test
    fun `ResourceInliner should replace highlightjs core CDN with data URI`() {
        val html =
            """<html><head><script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script></head></html>"""
        val result = ResourceInliner.inlineResources(html)
        assertTrue(
            result.contains("data:text/javascript;base64,"),
            "highlight.js should be replaced with a base64 data URI"
        )
        assertFalse(
            result.contains("https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"),
            "Original highlight.js CDN URL should be removed"
        )
    }

    @Test
    fun `ResourceInliner should replace Font Awesome CSS CDN with inline style tag containing embedded fonts`() {
        val html =
            """<html><head><link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css" /></head></html>"""
        val result = ResourceInliner.inlineResources(html)
        assertTrue(
            result.contains("<style>") && result.contains("</style>"),
            "Font Awesome should be replaced with an inline <style> tag"
        )
        assertFalse(
            result.contains("https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css"),
            "Original Font Awesome CDN URL should be removed"
        )
        // 验证 CSS 中的字体已经被内联为 base64（结果字符串不应包含原始字体路径，应包含 data:font）
        assertFalse(
            result.contains("../webfonts/fa-solid-900.woff2"),
            "Font paths should be inlined, not left as relative URLs"
        )
        assertTrue(
            result.contains("data:font/woff2;base64,") || result.contains("data:font/ttf;base64,"),
            "Font Awesome CSS should contain embedded base64 font data URIs"
        )
    }

    @Test
    fun `ResourceInliner should replace highlightjs theme CDN with data URI`() {
        val html =
            """<html><head><link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css" id="hljs-theme" /></head></html>"""
        val result = ResourceInliner.inlineResources(html)
        assertTrue(
            result.contains("data:text/css;base64,"),
            "Theme CSS should be replaced with a base64 data URI"
        )
        assertFalse(
            result.contains("https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css"),
            "Original theme CDN URL should be removed"
        )
    }

    @Test
    fun `ResourceInliner should replace language pack CDNs with data URIs`() {
        val html = """<html><head>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/kotlin.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/java.min.js"></script>
        </head></html>"""
        val result = ResourceInliner.inlineResources(html)
        val dataUriCount = result.split("data:text/javascript;base64,").size - 1
        assertTrue(
            dataUriCount >= 2,
            "At least 2 language packs should be replaced with data URIs, found $dataUriCount"
        )
    }

    @Test
    fun `ResourceInliner should preserve original HTML when resource is missing`() {
        // 使用一个不在映射表中的 URL，验证 HTML 不被破坏
        val html = """<html><head><script src="https://unknown.cdn/unknown.js"></script></head></html>"""
        val result = ResourceInliner.inlineResources(html)
        assertEquals(html, result, "Unknown CDN URLs should be preserved unchanged")
    }

    @Test
    fun `ResourceInliner should produce HTML under 2MB when inlining full chat HTML`() {
        val classLoader = javaClass.classLoader
        val rawHtml =
            classLoader.getResourceAsStream("webui/chat.html")?.use { it.readBytes().toString(Charsets.UTF_8) }
        assertNotNull(rawHtml, "chat.html should exist in resources")
        val result = ResourceInliner.inlineResources(rawHtml!!)
        println("Inlined HTML size: ${result.length} bytes")
        assertTrue(
            result.length < 2_000_000,
            "Inlined HTML should be under 2MB, but was ${result.length} bytes"
        )
    }
}
