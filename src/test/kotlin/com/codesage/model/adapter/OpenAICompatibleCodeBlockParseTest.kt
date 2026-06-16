package com.codesage.model.adapter

import com.codesage.model.adapter.minimax.MiniMaxAdapter
import com.codesage.model.dto.CodeBlockEvent
import com.codesage.model.dto.StreamChunk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 2026-06: OpenAI 兼容 adapter 解析 fenced code block 测试。
 *
 * 背景: 把大模型 markdown 响应里的 ```` ``` ```` / `~~~` 围栏解析为
 * CodeBlockEvent 事件,前端用 CodeBlockCard 组件实时渲染。
 * 调研依据: docs/research/CodeBlock围栏格式调研-2026-06-16-01.md
 *
 * 测试策略: 12 个场景覆盖标准 / 跨 chunk / 多块 / 不闭合 / 嵌套 /
 * tilde / 4反引号 / inline 干扰 / 围栏在 reasoning 段里。
 */
class OpenAICompatibleCodeBlockParseTest {

    private fun newAdapter() = MiniMaxAdapter(
        apiKey = "test",
        baseUrl = "https://api.minimaxi.com"
    )

    private fun sse(jsonContent: String) = "data: $jsonContent"

    private fun chunk(jsonContent: String): StreamChunk? =
        newAdapter().apply { resetStreamState() }.parseStreamChunk(sse(jsonContent)).firstOrNull()

    private fun runSse(input: List<String>, resetBetween: Boolean = false): List<StreamChunk> {
        val adapter = newAdapter()
        adapter.resetStreamState()
        val out = mutableListOf<StreamChunk>()
        for (line in input) {
            if (resetBetween) adapter.resetStreamState()
            out += adapter.parseStreamChunk(sse(line))
        }
        return out
    }

    // === 场景 1: 标准 kotlin 代码块(单 chunk) ===
    @Test
    fun `single chunk kotlin code block`() {
        val sse = """{"id":"x","choices":[{"delta":{"content":"```kotlin\nfun a(){}\n```"}}]}"""
        val chunks = runSse(listOf(sse))
        // 期望:3 个 events(Start / Delta / End),生成 3 个 StreamChunk
        val startEvents = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Start }
        val deltaEvents = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Delta }
        val endEvents = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.End }
        assertEquals(1, startEvents.size, "期望 1 个 Start")
        assertEquals("cb-1", startEvents[0].codeBlockId)
        assertEquals("kotlin", startEvents[0].language)
        assertTrue(deltaEvents.any { it.delta == "fun a(){}\n" }, "期望含 fun a 内容")
        assertTrue(endEvents.any { it.codeBlockId == "cb-1" }, "期望 End(cb-1)")
    }

    // === 场景 2: 多代码块(串行) ===
    @Test
    fun `multiple code blocks in one response`() {
        val sse = """{"id":"x","choices":[{"delta":{"content":"```python\nprint(1)\n```\n```js\nconsole.log(2)\n```"}}]}"""
        val chunks = runSse(listOf(sse))
        val starts = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Start }
        val ends = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.End }
        assertEquals(2, starts.size, "期望 2 个 Start")
        assertEquals(2, ends.size, "期望 2 个 End")
        assertEquals(setOf("python", "js"), starts.map { it.language }.toSet())
        assertEquals("cb-1", starts[0].codeBlockId)
        assertEquals("cb-2", starts[1].codeBlockId)
    }

    // === 场景 3: 跨 chunk 拆开围栏字符 ===
    @Test
    fun `fence split across chunks`() {
        val c1 = """{"id":"x","choices":[{"delta":{"content":"```kot"}}]}"""
        val c2 = """{"id":"x","choices":[{"delta":{"content":"lin\nfun a(){}\n```"}}]}"""
        val chunks = runSse(listOf(c1, c2))
        val starts = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Start }
        assertEquals(1, starts.size, "围栏跨 chunk 后仍应识别为 1 个开围栏")
        assertEquals("kotlin", starts[0].language)
        val ends = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.End }
        assertEquals(1, ends.size, "闭围栏正常识别")
    }

    // === 场景 4: 跨 chunk 拆开代码内容 ===
    @Test
    fun `code content split across chunks`() {
        val c1 = """{"id":"x","choices":[{"delta":{"content":"```kotlin\nfun a"}}]}"""
        val c2 = """{"id":"x","choices":[{"delta":{"content":"() {}\n```"}}]}"""
        val chunks = runSse(listOf(c1, c2))
        val deltas = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Delta }
        val ends = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.End }
        assertEquals(1, deltas.size, "代码内容跨 chunk 后应合并成 1 个 Delta")
        // 合并后内容应包含 "fun a" + "() {}\n"
        assertTrue(deltas[0].delta.contains("fun a"), "Delta 应含 'fun a'")
        assertTrue(deltas[0].delta.contains("() {}"), "Delta 应含 '() {}'")
        assertEquals(1, ends.size, "闭围栏正常识别")
    }

    // === 场景 5: 不闭合的围栏(stream end) ===
    @Test
    fun `unclosed fence triggers end on flush`() {
        val adapter = newAdapter()
        adapter.resetStreamState()
        // 模拟流中断:只发开围栏和部分代码,不发闭围栏
        adapter.parseStreamChunk(sse("""{"id":"x","choices":[{"delta":{"content":"```kotlin\nfun a() {}"}}]}"""))
        // 流结束 — 调 flushCodeBlockEvents
        if (adapter is OpenAICompatibleAdapter) {
            val events = adapter.flushCodeBlockEvents()
            assertTrue(events.isNotEmpty(), "流结束时未闭合的代码块应 emit 兜底 events")
            val endEvt = events.firstOrNull { it is CodeBlockEvent.End } as? CodeBlockEvent.End
            assertNotNull(endEvt, "应含 End 事件")
            assertEquals("cb-1", endEvt!!.codeBlockId)
        }
    }

    // === 场景 6: 围栏里有 3 反引号(不构成嵌套) ===
    @Test
    fun `inner triple backticks are literal`() {
        val sse = """{"id":"x","choices":[{"delta":{"content":"```markdown\n# Example\n```python\nprint(1)\n```\n```"}}]}"""
        val chunks = runSse(listOf(sse))
        val starts = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Start }
        val ends = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.End }
        assertEquals(1, starts.size, "内层 ```python 应被视为字面量,只识别外层 1 个 Start")
        assertEquals("markdown", starts[0].language)
        assertEquals(1, ends.size, "只有 1 个 End")
        val deltas = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Delta }
        assertTrue(deltas[0].delta.contains("```python"), "Delta 应含内层 ```python 字面量")
    }

    // === 场景 7: 4 个反引号围栏 ===
    @Test
    fun `quadruple backticks fence`() {
        val sse = """{"id":"x","choices":[{"delta":{"content":"````kotlin\nfun a() { ``` }\n````"}}]}"""
        val chunks = runSse(listOf(sse))
        val starts = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Start }
        val ends = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.End }
        assertEquals(1, starts.size, "4 反引号围栏应正常识别")
        assertEquals(1, ends.size, "4 反引号闭围栏应正常识别")
    }

    // === 场景 8: 围栏前 2 空格缩进 ===
    @Test
    fun `fence with 2 space indent`() {
        val sse = """{"id":"x","choices":[{"delta":{"content":"  ```kotlin\nfun a()\n  ```"}}]}"""
        val chunks = runSse(listOf(sse))
        val starts = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Start }
        val ends = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.End }
        assertEquals(1, starts.size, "2 空格缩进允许")
        assertEquals("kotlin", starts[0].language)
        assertEquals(1, ends.size, "2 空格缩进的闭围栏允许")
    }

    // === 场景 9: tilde 围栏 ===
    @Test
    fun `tilde fence`() {
        val sse = """{"id":"x","choices":[{"delta":{"content":"~~~python\nprint(1)\n~~~"}}]}"""
        val chunks = runSse(listOf(sse))
        val starts = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Start }
        val ends = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.End }
        assertEquals(1, starts.size)
        assertEquals("python", starts[0].language)
        assertEquals(1, ends.size)
    }

    // === 场景 10: inline 单反引号不构成围栏 ===
    @Test
    fun `single backtick is not a fence`() {
        val sse = """{"id":"x","choices":[{"delta":{"content":"Use `foo()` for this"}}]}"""
        val chunks = runSse(listOf(sse))
        val starts = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Start }
        assertEquals(0, starts.size, "单反引号不构成围栏")
        // text 应保留在 delta
        val mainDelta = chunks.first().delta
        assertEquals("Use `foo()` for this", mainDelta, "inline code 应作为 text 通过")
    }

    // === 场景 11: 围栏字符出现在 thinking 段里(状态机互斥) ===
    @Test
    fun `fence inside think tag is not emitted as code block`() {
        val sse = """{"id":"x","choices":[{"delta":{"content":"<think>```kotlin\nfun a()\n```</think>\nAnswer here"}}]}"""
        val chunks = runSse(listOf(sse))
        // reasoning 段不应触发 codeBlock 事件
        val starts = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Start }
        assertEquals(0, starts.size, "reasoning 段里的围栏字符不应触发 codeBlock 事件")
        // reasoning 应被切到 reasoningDelta
        val reasoning = chunks.mapNotNull { it.reasoningDelta }.joinToString("")
        assertTrue(reasoning.contains("```kotlin"), "围栏应保留在 reasoning 段")
        // text 应只含 "Answer here"
        val text = chunks.map { it.delta }.joinToString("")
        assertTrue(text.contains("Answer here"), "正文应正常通过")
    }

    // === 场景 12: 闭围栏后多余空白 ===
    @Test
    fun `closing fence with trailing whitespace`() {
        val sse = """{"id":"x","choices":[{"delta":{"content":"```kotlin   \nfun a()\n  ```  \nNext line"}}]}"""
        val chunks = runSse(listOf(sse))
        val starts = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.Start }
        val ends = chunks.mapNotNull { it.codeBlock as? CodeBlockEvent.End }
        assertEquals(1, starts.size)
        assertEquals("kotlin", starts[0].language)
        assertEquals(1, ends.size, "闭围栏后多余空白应忽略")
        // 闭围栏后的 "Next line" 应作为 text 通过
        val textAll = chunks.map { it.delta }.joinToString("")
        assertTrue(textAll.contains("Next line"), "闭围栏后文本应通过")
    }
}
