package com.codesage.ide.ui.components.chat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MarkdownRendererTest {

    @Test
    fun `parse inline bold`() {
        val blocks = MarkdownRenderer.parse("**平台**: IntelliJ IDEA 2026.1.2")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MarkdownRenderer.Block.Paragraph
        assertEquals(2, paragraph.segments.size)
        assertTrue(paragraph.segments[0] is MarkdownRenderer.Segment.Bold)
        assertEquals("平台", (paragraph.segments[0] as MarkdownRenderer.Segment.Bold).text)
        assertTrue(paragraph.segments[1] is MarkdownRenderer.Segment.Text)
    }

    @Test
    fun `parse heading`() {
        val blocks = MarkdownRenderer.parse("### 4. 📋 规则引擎 (Rule Engine)")
        assertEquals(1, blocks.size)
        val heading = blocks[0] as MarkdownRenderer.Block.Heading
        assertEquals(3, heading.level)
    }

    @Test
    fun `segmentsToHtml renders bold`() {
        val segments = listOf(
            MarkdownRenderer.Segment.Bold("平台"),
            MarkdownRenderer.Segment.Text(": IntelliJ IDEA 2026.1.2")
        )
        val html = MarkdownRenderer.segmentsToHtml(segments)
        assertEquals("<b>平台</b>: IntelliJ IDEA 2026.1.2", html)
    }

    @Test
    fun `full parse with bold and inline code`() {
        val text = "**触发器类型**：`OnEvent` / `OnSchedule` / `OnCondition` / `Manual`"
        val blocks = MarkdownRenderer.parse(text)
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MarkdownRenderer.Block.Paragraph
        val html = MarkdownRenderer.segmentsToHtml(paragraph.segments)
        println("HTML output: $html")
        assertTrue(html.contains("<b>触发器类型</b>"))
        assertTrue(html.contains("<code") && html.contains(">OnEvent</code>"))
    }

    @Test
    fun `parse multiple lines with bold`() {
        val text = "**平台**: IntelliJ IDEA 2026.1.2\n**也是md的一种格式吧，没渲染**"
        val blocks = MarkdownRenderer.parse(text)
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MarkdownRenderer.Block.Paragraph
        val html = MarkdownRenderer.segmentsToHtml(paragraph.segments)
        println("HTML output: $html")
        assertTrue(html.contains("<b>平台</b>"))
        assertTrue(html.contains("<b>也是md的一种格式吧，没渲染</b>"))
    }
}
