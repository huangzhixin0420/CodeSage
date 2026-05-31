package com.codesage.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class HtmlEscapeTest {

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    @Test
    fun `should escape basic HTML characters`() {
        assertEquals("&lt;div&gt;", escapeHtml("<div>"))
        assertEquals("&amp;test", escapeHtml("&test"))
    }

    @Test
    fun `should escape quotes to prevent attribute injection`() {
        assertEquals("&quot;onclick=alert(1)&quot;", escapeHtml("\"onclick=alert(1)\""))
        assertEquals("&#x27;test&#x27;", escapeHtml("'test'"))
    }

    @Test
    fun `should handle mixed content safely`() {
        val input = "<script>alert('xss')</script>"
        val expected = "&lt;script&gt;alert(&#x27;xss&#x27;)&lt;/script&gt;"
        assertEquals(expected, escapeHtml(input))
    }
}
