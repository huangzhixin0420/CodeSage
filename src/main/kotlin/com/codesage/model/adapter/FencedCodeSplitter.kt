package com.codesage.model.adapter

import com.codesage.model.dto.CodeBlockEvent

/**
 * 2026-06: Fenced code block 状态机,把 rawDelta 按 xxx/~~~ 围栏切成:
 *   - 普通文本(由 [feed] 返回值的 text 字段给出)
 *   - 代码块事件序列(由 [feed] 返回值的 events 字段给出,可能 0-2 个)
 *
 * 设计要点(依据 docs/research/CodeBlock围栏格式调研-2026-06-16-01.md):
 *   1. 状态机跨 chunk 累积 — LLM 流式可能把围栏字符拆到多个 chunk
 *   2. 围栏字符:`` ` `` 或 `~`,至少 3 个连续;不能混用
 *   3. 闭合围栏:同类型 + 长度 ≥ 开围栏长度
 *   4. info string:开围栏行内,trim 前后空白;反引号围栏后不能含反引号
 *   5. 围栏前可有 0-3 空格缩进(开/闭都允许)
 *   6. 不闭合兜底:流结束时若仍在代码块内,自动 emit End + Delta
 *
 * 事件发射策略:
 *   - Start:开围栏识别时,带 codeBlockId+language
 *   - End:闭围栏识别时,带 codeBlockId
 *   - Delta:在 End 触发时**一并** emit 累积的整段代码
 *   - flush() 兜底:在流结束时若仍在代码块内,emit Delta + End
 *
 *   不在每一帧实时 emit Delta(流式过程中代码块字符暂存),等 End 一起发。
 *   这是与 ModelReasoning 不同的地方(Reasoning 是每帧都 emit 增量)。
 *   原因:代码块语义上是"完整单元",中间过程对用户意义不大,
 *   End 时一次性给完整代码更符合用户预期(配合前端 CodeBlockCard)。
 */
internal class FencedCodeSplitter {

    private var inCodeBlock: Boolean = false
    private var fenceChar: Char = '`'
    private var fenceLength: Int = 0
    private var currentCodeBlockId: String? = null
    private var currentLanguage: String? = null

    // 跨 chunk 累积 buffer — 围栏字符可能跨 chunk,需要延迟识别
    private val carry: StringBuilder = StringBuilder()
    // 累积代码内容 — 等 End 时一并 emit Delta
    private val pendingCode: StringBuilder = StringBuilder()

    private var codeBlockCounter: Int = 0

    /** 当前在代码块内(供调试日志) */
    fun isInCodeBlock(): Boolean = inCodeBlock

    /** 当前 codeBlockId(供调试日志) */
    fun currentId(): String? = currentCodeBlockId

    /** 重置状态机(每轮流开始时调一次) */
    fun reset() {
        inCodeBlock = false
        fenceChar = '`'
        fenceLength = 0
        currentCodeBlockId = null
        currentLanguage = null
        carry.setLength(0)
        pendingCode.setLength(0)
        codeBlockCounter = 0
    }

    /**
     * 处理一帧 rawDelta。
     * 返回 FeedResult: text(本帧的纯文本片段) + events(代码块事件序列,可能 0-2 个)。
     *
     * events 序列规则:
     *   - Start: 总是单独出现
     *   - End: 总是单独出现
     *   - Delta: 仅在 End 出现时**一同**返回(累积的整段代码)
     *   - 即:开围栏 → [Start]; 闭围栏 → [Delta, End]; 一帧内不会同时 Start 和 End
     */
    fun feed(rawDelta: String): FeedResult {
        if (rawDelta.isEmpty()) return FeedResult("", emptyList())

        val s = carry.append(rawDelta).toString()
        carry.setLength(0)

        val out = StringBuilder()
        val events = mutableListOf<CodeBlockEvent>()
        var i = 0

        while (i < s.length) {
            if (!inCodeBlock) {
                val openResult = findOpeningFence(s, i)
                if (openResult == null) {
                    out.append(s, i, s.length)
                    i = s.length
                } else {
                    // 开围栏之前的字符是 text
                    out.append(s, i, openResult.fenceStart)
                    inCodeBlock = true
                    fenceChar = openResult.fenceChar
                    fenceLength = openResult.fenceLength
                    codeBlockCounter++
                    currentCodeBlockId = "cb-$codeBlockCounter"
                    currentLanguage = openResult.infoString.takeIf { it.isNotEmpty() }
                    pendingCode.setLength(0)
                    events.add(
                        CodeBlockEvent.Start(
                            codeBlockId = currentCodeBlockId!!,
                            language = currentLanguage,
                        )
                    )
                    i = openResult.afterFence
                }
            } else {
                val closeResult = findClosingFence(s, i)
                if (closeResult == null) {
                    pendingCode.append(s, i, s.length)
                    i = s.length
                } else {
                    pendingCode.append(s, i, closeResult.fenceStart)
                    val closedId = currentCodeBlockId ?: "cb-?"
                    val code = pendingCode.toString()
                    pendingCode.setLength(0)
                    inCodeBlock = false
                    fenceChar = '`'
                    fenceLength = 0
                    currentCodeBlockId = null
                    currentLanguage = null
                    if (code.isNotEmpty()) {
                        events.add(CodeBlockEvent.Delta(closedId, code))
                    }
                    events.add(CodeBlockEvent.End(closedId))
                    i = closeResult.afterFence
                }
            }
        }

        return FeedResult(out.toString(), events)
    }

    /**
     * 流结束兜底:若仍在代码块内,emit Delta + End。
     * 返回 List<CodeBlockEvent>,可能空。
     */
    fun flush(): List<CodeBlockEvent> {
        if (!inCodeBlock) return emptyList()
        val closedId = currentCodeBlockId ?: return emptyList()
        val code = pendingCode.toString()
        pendingCode.setLength(0)
        inCodeBlock = false
        fenceChar = '`'
        fenceLength = 0
        currentCodeBlockId = null
        currentLanguage = null
        val events = mutableListOf<CodeBlockEvent>()
        if (code.isNotEmpty()) {
            events.add(CodeBlockEvent.Delta(closedId, code))
        }
        events.add(CodeBlockEvent.End(closedId))
        return events
    }

    data class FeedResult(val text: String, val events: List<CodeBlockEvent>)

    // ====== 围栏识别子例程 ======

    private fun findOpeningFence(s: String, from: Int): FenceMatch? {
        var i = from
        while (i < s.length) {
            val ch = s[i]
            if (ch != '`' && ch != '~') {
                i++
                continue
            }
            val lineStart = lastNewlineIndex(s, i)
            val indent = i - (lineStart + 1)
            if (indent > 3) {
                i++
                continue
            }
            var j = i
            while (j < s.length && s[j] == ch) j++
            val len = j - i
            if (len < 3) {
                i = j
                continue
            }
            val afterFence = j
            if (afterFence >= s.length) {
                // 围栏到末尾,可能跨 chunk
                carry.append(s, i, s.length)
                return null
            }
            val next = s[afterFence]
            // 行尾 -> 开围栏,info string 为空
            if (next == '\n' || next == '\r') {
                return FenceMatch(
                    fenceStart = i,
                    fenceChar = ch,
                    fenceLength = len,
                    infoString = "",
                    afterFence = skipLineEnd(s, afterFence),
                )
            }
            // 围栏后任何非空白字符都可作为 info string 起始
            // 实际 LLM 99% 输出 [3 backticks]kotlin 这种格式(围栏+lang 紧邻,无空格)
            // 也支持 [3 backticks] kotlin(有空格)形式
            val infoStart = afterFence
            var k = infoStart
            while (k < s.length && s[k] != '\n' && s[k] != '\r') k++
            if (k >= s.length) {
                // info string 跨 chunk — 累积到 carry 等下一帧
                carry.append(s, i, s.length)
                return null
            }
            val infoString = s.substring(infoStart, k).trim()
            if (ch == '`' && infoString.contains('`')) {
                // 反引号围栏的 info string 不能含反引号
                // 这种情况下视为非围栏,继续往后找
                i = k
                continue
            }
            return FenceMatch(
                fenceStart = i,
                fenceChar = ch,
                fenceLength = len,
                infoString = infoString,
                afterFence = skipLineEnd(s, k),
            )
        }
        return null
    }

    private fun findClosingFence(s: String, from: Int): FenceMatch? {
        var i = from
        while (i < s.length) {
            val ch = s[i]
            if (ch != fenceChar) {
                i++
                continue
            }
            val lineStart = lastNewlineIndex(s, i)
            val indent = i - (lineStart + 1)
            if (indent > 3) {
                i++
                continue
            }
            var j = i
            while (j < s.length && s[j] == ch) j++
            val len = j - i
            if (len < fenceLength) {
                i = j
                continue
            }
            var k = j
            while (k < s.length && (s[k] == ' ' || s[k] == '\t')) k++
            if (k < s.length && s[k] != '\n' && s[k] != '\r') {
                i = j
                continue
            }
            return FenceMatch(
                fenceStart = i,
                fenceChar = ch,
                fenceLength = len,
                infoString = "",
                afterFence = skipLineEnd(s, k),
            )
        }
        return null
    }

    private fun lastNewlineIndex(s: String, beforeIdx: Int): Int {
        var k = beforeIdx - 1
        while (k >= 0) {
            if (s[k] == '\n') return k
            k--
        }
        return -1
    }

    private fun skipLineEnd(s: String, from: Int): Int {
        var k = from
        if (k < s.length && s[k] == '\r') k++
        if (k < s.length && s[k] == '\n') k++
        return k
    }

    private data class FenceMatch(
        val fenceStart: Int,
        val fenceChar: Char,
        val fenceLength: Int,
        val infoString: String,
        val afterFence: Int,
    )
}
