package com.codesage.ide.ui.components.chat

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Markdown 分块解析器（增强版）
 * 支持：段落、代码块、表格、引用块、任务列表、水平线、无序列表、有序列表
 */
object MarkdownRenderer {

    sealed class Block {
        data class Paragraph(val segments: List<Segment>) : Block()
        data class Heading(val level: Int, val segments: List<Segment>) : Block()
        data class CodeBlock(val language: String, val code: String) : Block()
        data class Table(val headers: List<String>, val rows: List<List<String>>) : Block()
        data class Quote(val lines: List<String>) : Block()
        data class TaskList(val items: List<TaskItem>) : Block()
        data class UnorderedList(val items: List<String>) : Block()
        data class OrderedList(val items: List<String>) : Block()
        object HorizontalRule : Block()
    }

    data class TaskItem(val checked: Boolean, val text: String)

    sealed class Segment {
        data class Text(val text: String) : Segment()
        data class InlineCode(val code: String) : Segment()
        data class Bold(val text: String) : Segment()
        data class Italic(val text: String) : Segment()
    }

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val remaining = StringBuilder(markdown.trimStart())

        while (remaining.isNotEmpty()) {
            skipEmptyLines(remaining)
            if (remaining.isEmpty()) break

            val block = extractBlock(remaining)
            if (block != null) {
                blocks.add(block)
            } else {
                if (remaining.isNotEmpty()) remaining.deleteCharAt(0)
            }
        }

        return blocks
    }

    private fun skipEmptyLines(sb: StringBuilder) {
        while (sb.isNotEmpty() && sb[0] == '\n') {
            sb.deleteCharAt(0)
        }
    }

    private fun extractBlock(sb: StringBuilder): Block? {
        val text = sb.toString()

        extractCodeBlock(sb)?.let { return it }
        extractHeading(sb)?.let { return it }
        extractHorizontalRule(sb)?.let { return it }
        extractTable(sb)?.let { return it }
        extractQuote(sb)?.let { return it }
        extractTaskList(sb)?.let { return it }
        extractUnorderedList(sb)?.let { return it }
        extractOrderedList(sb)?.let { return it }
        extractParagraph(sb)?.let { return it }

        return null
    }

    private fun extractCodeBlock(sb: StringBuilder): Block.CodeBlock? {
        val text = sb.toString()
        val regex = Regex("^```(\\w*)\\n(.*?)^```", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(text) ?: return null

        val language = match.groupValues[1].trim()
        val code = match.groupValues[2].removeSuffix("\n")
        sb.delete(0, match.range.last + 1)
        return Block.CodeBlock(language, code)
    }

    private fun extractHeading(sb: StringBuilder): Block.Heading? {
        val text = sb.toString()
        val regex = Regex("^(#{1,6})\\s+(.*?)$", RegexOption.MULTILINE)
        val match = regex.find(text) ?: return null

        val level = match.groupValues[1].length
        val headingText = match.groupValues[2].trimEnd()
        val segments = parseInlineElements(headingText)

        sb.delete(0, match.range.last + 1)
        return Block.Heading(level, segments)
    }

    private fun extractHorizontalRule(sb: StringBuilder): Block.HorizontalRule? {
        val text = sb.toString()
        val regex = Regex("^(---|\\*\\*\\*|___)\\s*\\n?")
        val match = regex.find(text) ?: return null
        sb.delete(0, match.range.last + 1)
        return Block.HorizontalRule
    }

    private fun extractTable(sb: StringBuilder): Block.Table? {
        val lines = sb.toString().lines()
        if (lines.isEmpty() || !lines[0].trim().startsWith("|")) return null

        val tableLines = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("|") || trimmed.contains("|")) {
                tableLines.add(trimmed)
            } else if (line.isBlank() && tableLines.isNotEmpty()) {
                break
            } else if (line.isNotBlank()) {
                break
            }
        }

        if (tableLines.size < 2) return null

        val headers = parseTableRow(tableLines[0])
        var rowStart = 1
        if (tableLines.getOrNull(1)?.replace("|", "")?.trim()?.all { it == '-' || it == ':' || it == ' ' } == true) {
            rowStart = 2
        }

        val rows = tableLines.drop(rowStart).map { parseTableRow(it) }

        val consumed = tableLines.joinToString("\n").length
        sb.delete(0, consumed)
        skipEmptyLines(sb)

        return Block.Table(headers, rows)
    }

    private fun parseTableRow(row: String): List<String> {
        return row.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim() }
    }

    private fun extractQuote(sb: StringBuilder): Block.Quote? {
        val allLines = sb.toString().lines()
        val quoteLines = mutableListOf<String>()
        var consumed = 0

        for (line in allLines) {
            when {
                line.startsWith("> ") -> {
                    quoteLines.add(line.substring(2))
                    consumed += line.length + 1
                }

                line == ">" -> {
                    quoteLines.add("")
                    consumed += line.length + 1
                }

                line.isBlank() && quoteLines.isNotEmpty() -> {
                    quoteLines.add("")
                    consumed += 1
                }

                line.isBlank() -> {
                    consumed += 1
                }

                else -> break
            }
        }

        if (quoteLines.isEmpty()) return null
        sb.delete(0, consumed.coerceAtMost(sb.length))
        skipEmptyLines(sb)
        return Block.Quote(quoteLines)
    }

    private fun extractTaskList(sb: StringBuilder): Block.TaskList? {
        val allLines = sb.toString().lines()
        val items = mutableListOf<TaskItem>()
        var consumed = 0

        for (line in allLines) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("- [ ] ") -> {
                    items.add(TaskItem(false, trimmed.substring(6)))
                    consumed += line.length + 1
                }

                trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ") -> {
                    items.add(TaskItem(true, trimmed.substring(6)))
                    consumed += line.length + 1
                }

                line.isBlank() && items.isNotEmpty() -> {
                    consumed += 1
                    break
                }

                line.isBlank() -> consumed += 1
                else -> break
            }
        }

        if (items.isEmpty()) return null
        sb.delete(0, consumed.coerceAtMost(sb.length))
        skipEmptyLines(sb)
        return Block.TaskList(items)
    }

    private fun extractUnorderedList(sb: StringBuilder): Block.UnorderedList? {
        val allLines = sb.toString().lines()
        val items = mutableListOf<String>()
        var consumed = 0

        for (line in allLines) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("- ") && !trimmed.startsWith("- [") -> {
                    items.add(trimmed.substring(2))
                    consumed += line.length + 1
                }

                trimmed.startsWith("* ") && !trimmed.startsWith("* [") -> {
                    items.add(trimmed.substring(2))
                    consumed += line.length + 1
                }

                line.isBlank() && items.isNotEmpty() -> {
                    consumed += 1
                    break
                }

                line.isBlank() -> consumed += 1
                else -> break
            }
        }

        if (items.isEmpty()) return null
        sb.delete(0, consumed.coerceAtMost(sb.length))
        skipEmptyLines(sb)
        return Block.UnorderedList(items)
    }

    private fun extractOrderedList(sb: StringBuilder): Block.OrderedList? {
        val allLines = sb.toString().lines()
        val items = mutableListOf<String>()
        var consumed = 0

        for (line in allLines) {
            val trimmed = line.trimStart()
            val match = Regex("^(\\d+)\\.\\s+(.*)$").find(trimmed)
            when {
                match != null -> {
                    items.add(match.groupValues[2])
                    consumed += line.length + 1
                }

                line.isBlank() && items.isNotEmpty() -> {
                    consumed += 1
                    break
                }

                line.isBlank() -> consumed += 1
                else -> break
            }
        }

        if (items.isEmpty()) return null
        sb.delete(0, consumed.coerceAtMost(sb.length))
        skipEmptyLines(sb)
        return Block.OrderedList(items)
    }

    private fun extractParagraph(sb: StringBuilder): Block.Paragraph? {
        val text = sb.toString()
        val endIdx = findNextBlockStart(text)
        val paragraphText = if (endIdx > 0) text.substring(0, endIdx) else text

        if (paragraphText.isBlank()) {
            if (endIdx > 0) sb.delete(0, endIdx)
            return null
        }

        sb.delete(0, paragraphText.length)
        val segments = parseInlineElements(paragraphText.trim())
        return Block.Paragraph(segments)
    }

    private fun findNextBlockStart(text: String): Int {
        val lines = text.lines()
        var charCount = 0
        for (line in lines) {
            val trimmed = line.trimStart()
            if (line.startsWith("```") ||
                line.startsWith("---") ||
                line.startsWith("***") ||
                line.startsWith("___") ||
                trimmed.startsWith("|") ||
                trimmed.startsWith("> ") ||
                trimmed.startsWith("- [ ] ") ||
                trimmed.startsWith("- [x] ") ||
                trimmed.startsWith("- [X] ") ||
                trimmed.startsWith("- ") ||
                trimmed.startsWith("* ") ||
                Regex("^\\d+\\.\\s+").find(trimmed) != null
            ) {
                if (charCount > 0) return charCount
            }
            charCount += line.length + 1
        }
        return text.length
    }

    private fun parseInlineElements(text: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val pattern = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|\\*[^*]+\\*)")
        var lastIndex = 0

        for (match in pattern.findAll(text)) {
            if (match.range.first > lastIndex) {
                segments.add(Segment.Text(text.substring(lastIndex, match.range.first)))
            }

            val content = match.value
            when {
                content.startsWith("`") && content.endsWith("`") -> {
                    segments.add(Segment.InlineCode(content.substring(1, content.length - 1)))
                }

                content.startsWith("**") && content.endsWith("**") -> {
                    segments.add(Segment.Bold(content.substring(2, content.length - 2)))
                }

                content.startsWith("*") && content.endsWith("*") -> {
                    segments.add(Segment.Italic(content.substring(1, content.length - 1)))
                }

                else -> segments.add(Segment.Text(content))
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < text.length) {
            segments.add(Segment.Text(text.substring(lastIndex)))
        }

        return segments
    }

    // ===== HTML 生成 =====

    fun blockToHtml(block: Block): String {
        return when (block) {
            is Block.Paragraph -> "<p>${segmentsToHtml(block.segments)}</p>"
            is Block.Heading -> renderHeadingHtml(block)
            is Block.CodeBlock -> "" // 由 CodeBlockComponent 单独渲染
            is Block.Table -> renderTableHtml(block)
            is Block.Quote -> renderQuoteHtml(block)
            is Block.TaskList -> renderTaskListHtml(block)
            is Block.UnorderedList -> renderUnorderedListHtml(block)
            is Block.OrderedList -> renderOrderedListHtml(block)
            Block.HorizontalRule -> "<hr/>"
        }
    }

    private fun renderHeadingHtml(heading: Block.Heading): String {
        val tag = "h${heading.level.coerceIn(1, 6)}"
        val fontSize = when (heading.level) {
            1 -> "18px"
            2 -> "16px"
            3 -> "15px"
            4 -> "14px"
            5 -> "13px"
            else -> "13px"
        }
        val marginTop = if (heading.level <= 2) "14px" else "10px"
        val marginBottom = if (heading.level <= 2) "8px" else "4px"
        return "<$tag style='font-size:$fontSize;font-weight:600;margin:$marginTop 0 $marginBottom 0;'>${
            segmentsToHtml(
                heading.segments
            )
        }</$tag>"
    }

    private fun renderTableHtml(table: Block.Table): String {
        val headerBg = if (JBColor.isBright()) "#F0F0F0" else "#2A2A2A"
        val borderColor = if (JBColor.isBright()) "#D8D8D8" else "#3D3D3D"
        val headerHtml = table.headers.joinToString("") { th ->
            "<th bgcolor='$headerBg' style='border:1px solid $borderColor;padding:6px 10px;'>${escapeHtml(th)}</th>"
        }
        val rowsHtml = table.rows.joinToString("") { row ->
            val cells = row.joinToString("") { cell ->
                "<td style='border:1px solid $borderColor;padding:6px 10px;'>${escapeHtml(cell)}</td>"
            }
            "<tr>$cells</tr>"
        }
        return "<table cellpadding='0' cellspacing='0' style='border-collapse:collapse;margin:8px 0;font-size:13px;'>" +
                "<thead><tr>$headerHtml</tr></thead>" +
                "<tbody>$rowsHtml</tbody></table>"
    }

    private fun renderQuoteHtml(quote: Block.Quote): String {
        val content = quote.lines.joinToString("<br/>") { escapeHtml(it) }
        return "<blockquote style='margin:8px 0;padding:8px 12px;border-left:3px solid ${if (JBColor.isBright()) "#CCCCCC" else "#555555"};color:${if (JBColor.isBright()) "#666666" else "#AAAAAA"};'>$content</blockquote>"
    }

    private fun renderTaskListHtml(taskList: Block.TaskList): String {
        val items = taskList.items.joinToString("") { item ->
            val checkbox = if (item.checked) "☑" else "☐"
            val style = if (item.checked) "color:#999999;text-decoration:line-through;" else ""
            "<li style='margin:3px 0;'>$checkbox ${escapeHtml(item.text)}</li>"
        }
        return "<ul style='margin:6px 0;padding-left:0;list-style:none;'>$items</ul>"
    }

    private fun renderUnorderedListHtml(list: Block.UnorderedList): String {
        val items = list.items.joinToString("") { item ->
            "<li style='margin:3px 0;'>${escapeHtml(item)}</li>"
        }
        return "<ul style='margin:6px 0;padding-left:20px;'>$items</ul>"
    }

    private fun renderOrderedListHtml(list: Block.OrderedList): String {
        val items = list.items.joinToString("") { item ->
            "<li style='margin:3px 0;'>${escapeHtml(item)}</li>"
        }
        return "<ol style='margin:6px 0;padding-left:20px;'>$items</ol>"
    }

    fun segmentsToHtml(segments: List<Segment>): String {
        return segments.joinToString("") { segment ->
            when (segment) {
                is Segment.Text -> escapeHtml(segment.text)
                is Segment.InlineCode ->
                    "<code style=\"background:${if (JBColor.isBright()) "#E8E8E8" else "#3D3D3D"};font-family:'JetBrains Mono',monospace;font-size:12px;\">${
                        escapeHtml(
                            segment.code
                        )
                    }</code>"

                is Segment.Bold -> "<b>${escapeHtml(segment.text)}</b>"
                is Segment.Italic -> "<i>${escapeHtml(segment.text)}</i>"
            }
        }
    }

    fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("\n", "<br/>")
    }
}
