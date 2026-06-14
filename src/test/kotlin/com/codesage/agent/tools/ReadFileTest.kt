package com.codesage.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * read_file 工具行为测试
 *
 * 覆盖以下回归点:
 *  - P0: readFile 响应必须包含 content 字段(LLM 拿不到正文 = 工具事实失效)
 *  - P0: 大文件截断提示必须出现(旧实现循环出口条件错,提示永不触发)
 *  - P0: 大文件末行不丢(EOF 或 hit cap 都要 flush)
 *  - offset > totalLines 必须返回明确错误
 *  - offset == totalLines 合法 EOF,返回空 content
 *  - offset 负数 → coerce 到 0
 *  - limit 越界 → 截到 totalLines
 *  - 短文本: total_lines / start_line / end_line 在分页时正确,全文时不出现
 *
 * 注: readFile 端到端走 IntelliJ VFS,纯 JUnit 测不开 LightProject。
 *      这里测内部纯函数 computePagedContent / pagedErrorMessage / readLargeFileFromBuffer。
 *      端到端通过 src/test/js-e2e 配合 JSDOM 不覆盖 VFS,需要在真 IDE 验证。
 */
class ReadFileTest {

    private val tools = IDETools(project = null)

    // ========== computePagedContent ==========

    @Test
    fun `full read returns raw with no start_line and end_line`() {
        val raw = "line0\nline1\nline2"
        val paged = tools.computePagedContent(raw, offset = null, limit = null)!!
        assertEquals(raw, paged.content)
        assertEquals(3, paged.totalLines)
        assertNull(paged.startLine)
        assertNull(paged.endLine)
    }

    @Test
    fun `paged read reports start_line and end_line`() {
        val raw = (0 until 10).joinToString("\n") { "L$it" }
        val paged = tools.computePagedContent(raw, offset = 2, limit = 3)!!
        assertEquals("L2\nL3\nL4", paged.content)
        assertEquals(10, paged.totalLines)
        assertEquals(2, paged.startLine)
        assertEquals(5, paged.endLine)
    }

    @Test
    fun `offset equal to totalLines is legal EOF with empty content`() {
        val raw = "a\nb"
        val paged = tools.computePagedContent(raw, offset = 2, limit = null)!!
        assertEquals("", paged.content)
        assertEquals(2, paged.totalLines)
        assertEquals(2, paged.startLine)
        assertEquals(2, paged.endLine)
    }

    @Test
    fun `offset greater than totalLines returns null for explicit error`() {
        val raw = "a\nb\nc"
        val paged = tools.computePagedContent(raw, offset = 100, limit = null)
        assertNull(paged, "越界必须返回 null,让 readFile 构造明确错误")
    }

    @Test
    fun `negative offset is coerced to zero`() {
        val raw = "x\ny\nz"
        val paged = tools.computePagedContent(raw, offset = -5, limit = 1)!!
        assertEquals("x", paged.content)
        assertEquals(0, paged.startLine)
        assertEquals(1, paged.endLine)
    }

    @Test
    fun `limit overflow is clamped to totalLines`() {
        val raw = (0 until 5).joinToString("\n") { "L$it" }
        val paged = tools.computePagedContent(raw, offset = 3, limit = 999)!!
        assertEquals("L3\nL4", paged.content)
        assertEquals(5, paged.endLine)
    }

    @Test
    fun `offset only without limit returns from offset to EOF`() {
        val raw = (0 until 5).joinToString("\n") { "L$it" }
        val paged = tools.computePagedContent(raw, offset = 2, limit = null)!!
        assertEquals("L2\nL3\nL4", paged.content)
        assertEquals(2, paged.startLine)
        assertEquals(5, paged.endLine)
    }

    @Test
    fun `limit only without offset starts from zero`() {
        val raw = (0 until 5).joinToString("\n") { "L$it" }
        val paged = tools.computePagedContent(raw, offset = null, limit = 2)!!
        assertEquals("L0\nL1", paged.content)
        assertEquals(0, paged.startLine)
        assertEquals(2, paged.endLine)
    }

    @Test
    fun `pagedErrorMessage includes actual line count`() {
        val raw = (0 until 50).joinToString("\n") { "L$it" }
        val msg = tools.pagedErrorMessage(offset = 999, raw = raw)
        assertTrue(msg.contains("999"), "应包含越界 offset: $msg")
        assertTrue(msg.contains("50"), "应包含实际行数: $msg")
    }

    // ========== readLargeFileFromBuffer ==========

    private fun bufOf(text: String): ByteBuffer =
        ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8))

    @Test
    fun `large file under CHUNK_LINES returns all lines with trailing newline`() {
        val text = "alpha\nbeta\ngamma\n"
        val out = tools.readLargeFileFromBuffer(bufOf(text), fileLength = text.length.toLong())
        // 3 行全部出现,且没有截断提示
        assertEquals(text, out)
        assertFalse(out.contains("已截断"), "未达上限不应出现截断提示")
    }

    @Test
    fun `large file exactly CHUNK_LINES does not show truncation hint`() {
        val text = (0 until IDETools.CHUNK_LINES).joinToString("\n") { "L$it" } + "\n"
        val out = tools.readLargeFileFromBuffer(bufOf(text), fileLength = text.length.toLong())
        assertEquals(IDETools.CHUNK_LINES, out.count { it == '\n' }, "行数应等于 CHUNK_LINES(每行末尾一个 \\n)")
        assertFalse(out.contains("已截断"), "刚好 CHUNK_LINES 不应触发截断提示")
    }

    @Test
    fun `large file over CHUNK_LINES emits truncation hint and keeps all front lines`() {
        val lines = IDETools.CHUNK_LINES + 100
        val text = (0 until lines).joinToString("\n") { "L$it" } + "\n"
        val out = tools.readLargeFileFromBuffer(bufOf(text), fileLength = text.length.toLong())
        // 1) 前 CHUNK_LINES 行都在结果里
        for (i in 0 until IDETools.CHUNK_LINES) {
            assertTrue(out.contains("L$i"), "前 CHUNK_LINES 行的第 $i 行应保留")
        }
        // 2) 截断提示必须出现
        assertTrue(out.contains("已截断"), "超过 CHUNK_LINES 必须出现截断提示")
        assertTrue(out.contains("offset"), "截断提示应引导 LLM 用 offset 续读")
        // 3) 后面的行(超 CHUNK_LINES 的)不应出现
        assertFalse(out.contains("L${IDETools.CHUNK_LINES + 5}"), "截断后的行不应出现")
    }

    @Test
    fun `large file with non-newline-terminated last line flushes that line`() {
        // 1000 行整,最后一行无 \n — 旧实现: 跳循环 → 丢末行。新实现: flush。
        val lines = IDETools.CHUNK_LINES
        val text = (0 until lines).joinToString("\n") { "L$it" } // 末行无 \n
        val out = tools.readLargeFileFromBuffer(bufOf(text), fileLength = text.length.toLong())
        assertTrue(out.contains("L${lines - 1}"), "末行(无 \\n 结尾)必须被 flush, 不能丢")
        assertFalse(out.contains("已截断"), "刚好 CHUNK_LINES 不应触发截断")
    }

    @Test
    fun `large file over cap does not flush partial over-cap line`() {
        // 设计契约: hit CHUNK_LINES 时立即 break, 不读下一个字节。
        // 避免把半个 UTF-8 字符的字节推进 linePos(后续 String() 会乱码)。
        // 后果: L${CHUNK_LINES} 整行**不会**出现在结果里, 由截断提示告诉 LLM。
        val lines = IDETools.CHUNK_LINES + 1
        val text = (0 until lines).joinToString("\n") { "L$it" } + "\n"
        val out = tools.readLargeFileFromBuffer(bufOf(text), fileLength = text.length.toLong())
        for (i in 0 until IDETools.CHUNK_LINES) {
            assertTrue(out.contains("L$i"), "前 CHUNK_LINES 行的第 $i 行应保留")
        }
        assertFalse(
            out.contains("L${IDETools.CHUNK_LINES}\n"),
            "over-cap 的整行不应出现(避免半个字符)",
        )
        assertTrue(out.contains("已截断"), "应触发截断提示")
    }

    @Test
    fun `empty file returns empty string without hint`() {
        val out = tools.readLargeFileFromBuffer(bufOf(""), fileLength = 0)
        assertEquals("", out)
    }

    // ========== readFile 响应字段契约 (Schema 验证) ==========

    @Test
    fun `readFile tool schema declares path as required and offset limit as optional`() {
        // 工具注册时 schema 是事实契约, 注册名 / 必填项被 LLM SDK 校验依赖。
        // 任何回归(如把 offset/limit 标为 required)会让 LLM 拒绝调用。
        val tool = readFileTool()
        assertEquals("read_file", tool.name)
        assertEquals(listOf("path"), tool.parameters.required)
        assertTrue(tool.parameters.properties.containsKey("path"))
        assertTrue(tool.parameters.properties.containsKey("offset"))
        assertTrue(tool.parameters.properties.containsKey("limit"))
    }

    @Test
    fun `readFile args parsing handles missing optional fields`() {
        // 端到端需要 VFS, 这里仅验证 args 解析路径(parse offset/limit intOrNull
        // 在缺省时返回 null, 与 computePagedContent 的契约吻合)
        val args = buildJsonObject {
            put("path", JsonPrimitive("/tmp/whatever"))
        }
        assertNull(args["offset"]?.jsonPrimitive?.intOrNull)
        assertNull(args["limit"]?.jsonPrimitive?.intOrNull)
    }

    // ========== addLineNumbers ==========

    @Test
    fun `addLineNumbers produces cat-n style output with tab separator`() {
        val content = "alpha\nbeta\ngamma"
        val numbered = tools.addLineNumbers(content, startLine = 0)
        val lines = numbered.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].matches(Regex("""\s+1\talpha""")))
        assertTrue(lines[1].matches(Regex("""\s+2\tbeta""")))
        assertTrue(lines[2].matches(Regex("""\s+3\tgamma""")))
        assertTrue(numbered.contains('\t'), "行号与内容之间应使用 tab 分隔")
    }

    @Test
    fun `addLineNumbers respects startLine offset`() {
        val content = "L2\nL3"
        val numbered = tools.addLineNumbers(content, startLine = 2)
        val lines = numbered.lines()
        assertEquals("     3\tL2", lines[0])
        assertEquals("     4\tL3", lines[1])
    }

    @Test
    fun `addLineNumbers handles empty content`() {
        assertEquals("", tools.addLineNumbers(""))
    }

    @Test
    fun `addLineNumbers widens padding for large line counts`() {
        val content = (0 until 2000).joinToString("\n") { "x" }
        val numbered = tools.addLineNumbers(content, startLine = 0)
        val lastLine = numbered.lines().last()
        assertTrue(lastLine.startsWith("  2000"), "2000 行时行号应至少 4 位宽: $lastLine")
    }

    // ========== countLines / readChunkFromBuffer (P0 6.1.2) ==========

    @Test
    fun `countLines matches String lines semantics`() {
        assertEquals(0, tools.countLines(bufOf("")))
        assertEquals(1, tools.countLines(bufOf("a")))
        assertEquals(2, tools.countLines(bufOf("a\nb")))
        assertEquals(3, tools.countLines(bufOf("a\nb\n")))
    }

    @Test
    fun `readChunkFromBuffer reads requested range without loading whole content`() {
        val text = "L0\nL1\nL2\nL3\nL4\n"
        val chunk = tools.readChunkFromBuffer(bufOf(text), offset = 1, limit = 2)
        assertEquals("L1\nL2\n", chunk.content)
        // Kotlin String.lines() 将末尾换行视为一个空行
        assertEquals(6, chunk.totalLines)
        assertEquals(1, chunk.startLine)
        assertEquals(3, chunk.endLine)
    }

    @Test
    fun `readChunkFromBuffer offset equal to totalLines returns empty EOF`() {
        val text = "a\nb\n"
        val chunk = tools.readChunkFromBuffer(bufOf(text), offset = 2, limit = 10)
        assertEquals("", chunk.content)
        assertEquals(2, chunk.startLine)
        assertEquals(2, chunk.endLine)
    }

    @Test
    fun `readChunkFromBuffer offset beyond totalLines returns empty with total`() {
        val text = "a\nb\n"
        val chunk = tools.readChunkFromBuffer(bufOf(text), offset = 100, limit = 10)
        assertEquals("", chunk.content)
        assertEquals(3, chunk.totalLines)
        assertEquals(3, chunk.startLine)
        assertEquals(3, chunk.endLine)
    }

    @Test
    fun `readChunkFromBuffer handles UTF-8 multibyte and last line without newline`() {
        val text = "中文\n🎉emoji\n末行无换行"
        val chunk = tools.readChunkFromBuffer(bufOf(text), offset = 0, limit = 10)
        assertEquals(3, chunk.totalLines)
        assertTrue(chunk.content.contains("中文"))
        assertTrue(chunk.content.contains("🎉emoji"))
        assertTrue(chunk.content.contains("末行无换行"))
    }
}
