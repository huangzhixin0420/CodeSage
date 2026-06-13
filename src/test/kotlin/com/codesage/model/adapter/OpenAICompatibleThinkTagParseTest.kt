package com.codesage.model.adapter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * v2.6+ 修复验证测试: OpenAI 兼容 adapter 解析 <think> 标签模式
 *
 * 背景: MiniMax-M3 / Qwen2.5 / DeepSeek-R1 (Qwen-distill) 把 reasoning
 * 和正文一起塞进 delta.content,靠 <think>...</think> XML 标签区分。
 * 不是协议层 reasoning_content / reasoning / thinking 字段,是 prompt
 * 层 XML 标签。
 *
 * 修复: OpenAICompatibleAdapter 维护跨 chunk 状态机(inThinkBlock +
 * pendingThink),parseStreamChunk 调用 splitThinkTag() 切分。
 *
 * 测试策略: 每个测试创建一个新的 MiniMaxAdapter 实例以重置状态机
 * (adapter 字段是 instance 级,跨 chunk 才能持续)。模拟连续 SSE
 * chunk 调 parseStreamChunk,断言累积后的 reasoning + content 正确。
 */
class OpenAICompatibleThinkTagParseTest {

    private fun newAdapter() = com.codesage.model.adapter.minimax.MiniMaxAdapter(
        apiKey = "test",
        baseUrl = "https://api.minimaxi.com"
    )

    @Test
    fun `DEBUG - single chunk returns what`() {
        val a = newAdapter()
        val sse = """data: {"id":"x","choices":[{"delta":{"content":"<think>\n让我分析\n</think>\n\n## 答案\n正文"}}]}"""
        val chunk = a.parseStreamChunk(sse)
        println("===DEBUG chunk class=${chunk?.javaClass}===")
        println("===DEBUG chunk=$chunk===")
        println("===DEBUG reasoningDelta=${chunk?.reasoningDelta}===")
        println("===DEBUG delta=${chunk?.delta}===")
        println("===DEBUG done=${chunk?.done}===")
        // 不断言,只打印
        assertNotNull(chunk)
    }

    /** 跑一连串 chunk,累积 delta / reasoningDelta,返回 (reasoning 全量, content 全量) */
    private fun runStream(adapter: OpenAICompatibleAdapter, chunks: List<String>): Pair<String, String> {
        var reasoning = ""
        var content = ""
        for (c in chunks) {
            val sse = "data: $c"
            val parsed = adapter.parseStreamChunk(sse) ?: continue
            if (parsed.done) break
            if (!parsed.reasoningDelta.isNullOrEmpty()) reasoning += parsed.reasoningDelta
            if (parsed.delta.isNotEmpty()) content += parsed.delta
        }
        return reasoning to content
    }

    @Test
    fun `single chunk with complete think tag`() {
        // chunk1: <think> 完整结束 + 正文
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"content":"<think>\n让我分析\n</think>\n\n## 答案\nMarkdown 正文"}}]}"""
        ))
        assertEquals("\n让我分析\n", r)
        assertEquals("\n\n## 答案\nMarkdown 正文", c)
    }

    @Test
    fun `think tag split across multiple chunks`() {
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"content":"<think>"}}]}""",
            """{"id":"x","choices":[{"delta":{"content":"\n让我分析"}}]}""",
            """{"id":"x","choices":[{"delta":{"content":"\n用户要求"}}]}""",
            """{"id":"x","choices":[{"delta":{"content":"\n</think>"}}]}""",
            """{"id":"x","choices":[{"delta":{"content":"\n\n## 答案\n正文"}}]}""",
        ))
        assertEquals("\n让我分析\n用户要求\n", r)
        assertEquals("\n\n## 答案\n正文", c)
    }

    @Test
    fun `no think tag - delta goes straight to content`() {
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"content":"普通回答,无 think"}}]}"""
        ))
        assertTrue(r.isNullOrEmpty(), "无 <think> 时 reasoningDelta 应为 null 或空")
        assertEquals("普通回答,无 think", c)
    }

    @Test
    fun `think tag opens but never closes (malformed)`() {
        // 模型偶尔会忘记 </think> — 不能因此阻塞后续 chunk。
        // 后续 chunk 走\"已在 think 内\"分支继续累积 reasoning。
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"content":"<think>\n未结束..."}}]}""",
            """{"id":"x","choices":[{"delta":{"content":"更多内容"}}]}""",
        ))
        assertEquals("\n未结束...更多内容", r)
        assertEquals("", c, "未结束 think 时不应有 content")
    }

    @Test
    fun `multiple think blocks in one stream`() {
        // Qwen2.5 偶尔会在 tool call 前再开一个 think 段。
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"content":"<think>\n第一段思考"}}]}""",
            """{"id":"x","choices":[{"delta":{"content":"\n</think>\n\n## 第一次回答"}}]}""",
            """{"id":"x","choices":[{"delta":{"content":"<think>\n第二段思考"}}]}""",
            """{"id":"x","choices":[{"delta":{"content":"\n</think>\n\n## 第二次回答"}}]}""",
        ))
        assertEquals("\n第一段思考\n\n第二段思考\n", r)
        assertEquals("\n\n## 第一次回答\n\n## 第二次回答", c)
    }

    @Test
    fun `think tag opens mid-content (not at start)`() {
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"content":"先说点前置内容"}}]}""",
            """{"id":"x","choices":[{"delta":{"content":"<think>\n后接思考"}}]}""",
            """{"id":"x","choices":[{"delta":{"content":"\n</think>\n## 答案"}}]}""",
        ))
        assertEquals("\n后接思考\n", r)
        assertEquals("先说点前置内容\n## 答案", c)
    }

    @Test
    fun `dedicated reasoning_content field takes priority over think tag`() {
        // DeepSeek-R1 走专有字段 reasoning_content,不走 <think> 标签。
        // 拿到的 delta.content 应保持纯正文,不要被当 think 切。
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"reasoning_content":"专用字段","content":"普通正文"}}]}"""
        ))
        assertEquals("专用字段", r)
        assertEquals("普通正文", c)
    }

    @Test
    fun `DONE sentinel does not crash state machine`() {
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"content":"<think>\n正在想"}}]}""",
            """[DONE]""",
        ))
        // [DONE] 不应影响状态
        assertEquals("\n正在想", r)
        assertEquals("", c)
    }

    // ========== 修复: 专有字段为 null/空白 时回退到 <think> 标签模式 ==========
    //
    // 用户报告 minimax(经中转)thinking 解析错乱: 正文卡里出现 `` 标签和
    // 完整答案。根因: 专有字段 reasoning_content / reasoning / thinking 在中转
    // 上常发空串作为"该模型无 thinking"占位, Kotlin `?:` 在空串上不触发回退,
    // 错走"模式 1" → 跳过 <think> 标签切分 → 正文卡塞进 <think> 标签 + thinking 文本。

    @Test
    fun `empty string dedicated reasoning_content falls back to think tag mode`() {
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"reasoning_content":"","content":"<think>分析中</think>\n\n答案"}}]}"""
        ))
        // 空白占位不应进模式 1; 走 <think> 标签模式
        assertEquals("分析中", r)
        assertEquals("\n\n答案", c)
    }

    @Test
    fun `whitespace-only dedicated reasoning field falls back to think tag mode`() {
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"reasoning":"   ","content":"<think>thinking</think>\n正文"}}]}"""
        ))
        assertEquals("thinking", r)
        assertEquals("\n正文", c)
    }

    @Test
    fun `all three dedicated fields empty still falls back to think tag mode`() {
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"reasoning_content":"","reasoning":"","thinking":"","content":"<think>A</think>\nB"}}]}"""
        ))
        assertEquals("A", r)
        assertEquals("\nB", c)
    }

    @Test
    fun `non-blank dedicated field still wins over think tag`() {
        // 回归保护: 真有专有字段时仍走模式 1, 不被本次修复误伤
        val a = newAdapter()
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"reasoning_content":"专用 reasoning","content":"<think>不应被切\n</think>\n正文"}}]}"""
        ))
        assertEquals("专用 reasoning", r)
        assertEquals("<think>不应被切\n</think>\n正文", c, "模式 1 时 content 不应被切分")
    }

    // ========== 修复: resetStreamState 跨 turn 状态污染 ==========
    //
    // 同 adapter 实例被多 turn 复用, 若上一轮因网络中断 / 用户停止 / 错误
    // 导致流未正常结束(未收到 </think>), inThinkBlock 残留 true, 下一轮首
    // 个不含 < 的 chunk 会被当 thinking。

    @Test
    fun `resetStreamState clears inThinkBlock across turns`() {
        val a = newAdapter()
        // 模拟上一轮: 收到 <think> 但流中断, inThinkBlock 残留 true
        runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"content":"<think>\n未结束"}}]}""",
        ))
        // 调 reset (实际由 ModelGateway.chatStream 入口调)
        a.resetStreamState()
        // 新一轮: 纯正文, 不应被当 thinking
        val (r, c) = runStream(a, listOf(
            """{"id":"x","choices":[{"delta":{"content":"新 turn 的普通正文"}}]}"""
        ))
        assertTrue(r.isNullOrEmpty(), "reset 后 inThinkBlock=false, 纯正文不应进 reasoning")
        assertEquals("新 turn 的普通正文", c)
    }
}
