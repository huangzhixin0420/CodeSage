package com.codesage.agent.tools

import com.codesage.agent.tools.handlers.ReadDocumentTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO

/**
 * read_document 工具单元测试。
 *
 * 覆盖 P0 6.1.3 核心路径：图片 base64、PDF 文本提取、ipynb 解析，以及错误路径。
 * 测试不依赖 IntelliJ VFS/PSI，使用 headless 文件路径直接读写。
 */
class ReadDocumentToolTest {

    private val ideTools = IDETools(project = null)
    private val tool = ReadDocumentTool(ideTools)

    private fun args(path: String, extras: Map<String, JsonPrimitive> = emptyMap()): JsonObject {
        val map = mutableMapOf("path" to JsonPrimitive(path))
        map.putAll(extras)
        return JsonObject(map)
    }

    @Test
    fun `read png image returns base64 mime_type and dimensions`(@TempDir tempDir: File) = runBlocking {
        val imageFile = File(tempDir, "test.png").apply {
            val image = BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply {
                    color = Color.RED
                    fillRect(0, 0, 16, 9)
                    dispose()
                }
            }
            ImageIO.write(image, "png", this)
        }

        val result = tool.execute(args(imageFile.absolutePath))
        assertTrue(result is ToolResult.Success, "Expected success but got $result")
        val data = (result as ToolResult.Success).data.jsonObject

        assertEquals("image", data["format"]?.jsonPrimitive?.content)
        assertEquals("image/png", data["mime_type"]?.jsonPrimitive?.content)
        assertEquals(16, data["width"]?.jsonPrimitive?.intOrNull)
        assertEquals(9, data["height"]?.jsonPrimitive?.intOrNull)
        assertEquals("16x9", data["dimensions"]?.jsonPrimitive?.content)
        assertFalse(data["truncated"]?.jsonPrimitive?.booleanOrNull ?: true)

        val base64 = data["base64"]?.jsonPrimitive?.content
        assertNotNull(base64)
        assertTrue(base64!!.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `read pdf extracts text per page and reports pagination metadata`(@TempDir tempDir: File) = runBlocking {
        val pdfFile = File(tempDir, "test.pdf").apply {
            PDDocument().use { doc ->
                repeat(3) { pageIndex ->
                    val page = PDPage(PDRectangle.A4)
                    doc.addPage(page)
                    val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                    PDPageContentStream(doc, page).use { stream ->
                        stream.beginText()
                        stream.setFont(font, 12f)
                        stream.newLineAtOffset(100f, 700f)
                        stream.showText("Hello PDF page ${pageIndex + 1}")
                        stream.endText()
                    }
                }
                doc.save(this)
            }
        }

        val result = tool.execute(args(pdfFile.absolutePath))
        assertTrue(result is ToolResult.Success, "Expected success but got $result")
        val data = (result as ToolResult.Success).data.jsonObject

        assertEquals("pdf", data["format"]?.jsonPrimitive?.content)
        assertEquals("application/pdf", data["mime_type"]?.jsonPrimitive?.content)
        assertEquals(3, data["page_count"]?.jsonPrimitive?.intOrNull)
        assertEquals(3, data["returned_pages"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, data["start_page"]?.jsonPrimitive?.intOrNull)
        assertEquals(3, data["end_page"]?.jsonPrimitive?.intOrNull)
        assertFalse(data["truncated"]?.jsonPrimitive?.booleanOrNull ?: true)

        val pages = data["pages"]?.jsonArray ?: fail("Missing pages array")
        assertEquals(3, pages.size)
        pages.forEachIndexed { index, page ->
            val text = page.jsonObject["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(text.contains("Hello PDF page ${index + 1}"), "Page ${index + 1} text missing: $text")
        }
    }

    @Test
    fun `read pdf with page parameter returns single page`(@TempDir tempDir: File) = runBlocking {
        val pdfFile = File(tempDir, "test.pdf").apply {
            PDDocument().use { doc ->
                repeat(2) { pageIndex ->
                    val page = PDPage(PDRectangle.A4)
                    doc.addPage(page)
                    val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                    PDPageContentStream(doc, page).use { stream ->
                        stream.beginText()
                        stream.setFont(font, 12f)
                        stream.newLineAtOffset(100f, 700f)
                        stream.showText("Page ${pageIndex + 1} content")
                        stream.endText()
                    }
                }
                doc.save(this)
            }
        }

        val result = tool.execute(args(pdfFile.absolutePath, mapOf("page" to JsonPrimitive(2))))
        assertTrue(result is ToolResult.Success, "Expected success but got $result")
        val data = (result as ToolResult.Success).data.jsonObject

        assertEquals(2, data["start_page"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, data["end_page"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, data["returned_pages"]?.jsonPrimitive?.intOrNull)
        val pages = data["pages"]?.jsonArray ?: fail("Missing pages array")
        assertEquals(1, pages.size)
        assertTrue(pages[0].jsonObject["text"]?.jsonPrimitive?.content?.contains("Page 2 content") == true)
    }

    @Test
    fun `read ipynb returns cells and metadata`(@TempDir tempDir: File) = runBlocking {
        val notebook = """
            {
              "nbformat": 4,
              "nbformat_minor": 5,
              "metadata": {
                "kernelspec": { "language": "python" }
              },
              "cells": [
                {
                  "cell_type": "markdown",
                  "source": ["# Title"]
                },
                {
                  "cell_type": "code",
                  "execution_count": 1,
                  "source": "print('hello')",
                  "outputs": [
                    {
                      "output_type": "stream",
                      "text": ["hello\n"]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val notebookFile = File(tempDir, "sample.ipynb").apply {
            writeText(notebook, StandardCharsets.UTF_8)
        }

        val result = tool.execute(args(notebookFile.absolutePath))
        assertTrue(result is ToolResult.Success, "Expected success but got $result")
        val data = (result as ToolResult.Success).data.jsonObject

        assertEquals("jupyter_notebook", data["format"]?.jsonPrimitive?.content)
        assertEquals(4, data["nbformat"]?.jsonPrimitive?.intOrNull)
        assertEquals(5, data["nbformat_minor"]?.jsonPrimitive?.intOrNull)
        assertEquals("python", data["language"]?.jsonPrimitive?.content)

        val cells = data["cells"]?.jsonArray ?: fail("Missing cells array")
        assertEquals(2, cells.size)

        val markdownCell = cells[0].jsonObject
        assertEquals("markdown", markdownCell["cell_type"]?.jsonPrimitive?.content)
        assertEquals("# Title", markdownCell["source"]?.jsonPrimitive?.content)

        val codeCell = cells[1].jsonObject
        assertEquals("code", codeCell["cell_type"]?.jsonPrimitive?.content)
        assertEquals(1, codeCell["execution_count"]?.jsonPrimitive?.intOrNull)
        assertEquals("print('hello')", codeCell["source"]?.jsonPrimitive?.content)
        val outputs = codeCell["outputs"]?.jsonArray ?: fail("Missing outputs")
        assertEquals(1, outputs.size)
        assertEquals("stream", outputs[0].jsonObject["output_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unsupported extension returns error`(@TempDir tempDir: File) = runBlocking {
        val doc = File(tempDir, "archive.zip").apply { writeText("not a doc") }
        val result = tool.execute(args(doc.absolutePath))
        assertTrue(result is ToolResult.Error, "Expected error for unsupported extension")
        val message = (result as ToolResult.Error).message
        assertTrue(message.contains("Unsupported document format"), message)
        assertTrue(message.contains(".zip"), message)
    }

    @Test
    fun `missing file returns error`() = runBlocking {
        val result = tool.execute(args("/nonexistent/path/document.pdf"))
        assertTrue(result is ToolResult.Error, "Expected error for missing file")
        assertTrue((result as ToolResult.Error).message.contains("File not found"), result.message)
    }

    @Test
    fun `file exceeding max size returns error`(@TempDir tempDir: File) = runBlocking {
        val pdfFile = File(tempDir, "large.pdf").apply {
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                doc.save(this)
            }
        }

        val result = tool.execute(
            args(
                pdfFile.absolutePath,
                mapOf("max_size_bytes" to JsonPrimitive(1))
            )
        )
        assertTrue(result is ToolResult.Error, "Expected error for oversized file")
        val message = (result as ToolResult.Error).message
        assertTrue(message.contains("exceeds max_size_bytes"), message)
    }

    @Test
    fun `invalid page number returns error`(@TempDir tempDir: File) = runBlocking {
        val pdfFile = File(tempDir, "test.pdf").apply {
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                doc.save(this)
            }
        }

        val result = tool.execute(args(pdfFile.absolutePath, mapOf("page" to JsonPrimitive(0))))
        assertTrue(result is ToolResult.Error, "Expected error for invalid page")
        assertTrue((result as ToolResult.Error).message.contains("page"), result.message)
    }
}
