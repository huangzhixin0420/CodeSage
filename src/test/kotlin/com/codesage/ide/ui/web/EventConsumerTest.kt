package com.codesage.ide.ui.web

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.model.dto.ToolCall
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * EventConsumer 不变量测试
 *
 * 覆盖 6 个核心 contract:
 *  1. Terminal 事件永不丢
 *  2. 多 tool 并行调用,start/result/delta 互不吞
 *  3. 顺序:Coalescable 之后 Terminal 必先 flush Coalescable
 *  4. per-turn 状态隔离(跨 turn 不污染)
 *  5. 取消/异常路径不丢残留(finally flush)
 *  6. sendToJS 抛错不杀消费者
 *
 * 配套:
 *  - Done 展开路径(thinking_complete + turn_complete 双消息)
 *  - 不同 toolId 的 ToolCallDelta 不互相合并
 *  - interval flush 在到达阈值时触发
 *
 * 注: "eventRouter 返回 null" 这个防御分支由 Done 展开测试间接覆盖 —
 * EventRouter 对 Done 返回 null,Done 路径走的是 expand 分支而不是 null-warn 分支。
 * 其他 Terminal 事件真实 router 不会返 null,所以 null-warn 防御分支暂无 e2e 测试覆盖。
 */
class EventConsumerTest {

    private lateinit var router: EventRouter
    private lateinit var delivered: MutableList<Map<String, Any?>>
    private lateinit var sendToJS: (Map<String, Any?>) -> Unit

    @BeforeEach
    fun setUp() {
        router = EventRouter()
        delivered = mutableListOf()
        sendToJS = { delivered.add(it) }
    }

    /** 构造消费者:默认关掉 interval flush(Long.MAX_VALUE),只观察 Terminal 触发的 flush */
    private fun newConsumer(
        flushIntervalMs: Long = Long.MAX_VALUE,
        clock: () -> Long = { 0L },
    ): EventConsumer = EventConsumer(router, sendToJS, flushIntervalMs, clock)

    // ===== 不变量 1 + 2:多 tool 并行不丢 =====

    @Test
    fun `parallel tool calls -- 2 starts and 2 results all delivered, none dropped`() = runBlocking {
        // 模拟 LLM 一次返回 2 个 read_file 的场景(原 bug 重现)
        val consumer = newConsumer()
        val flow = flowOf(
            AgentStreamEvent.ToolCallStart(
                ToolCall(id = "call_A", name = "read_file", arguments = """{"path":"a.kt"}""")
            ),
            AgentStreamEvent.ToolCallStart(
                ToolCall(id = "call_B", name = "read_file", arguments = """{"path":"b.kt"}""")
            ),
            AgentStreamEvent.ToolCallResult(
                toolCallId = "call_A", toolName = "read_file",
                result = """{"success":true,"data":"AAA"}""", success = true,
            ),
            AgentStreamEvent.ToolCallResult(
                toolCallId = "call_B", toolName = "read_file",
                result = """{"success":true,"data":"BBB"}""", success = true,
            ),
            AgentStreamEvent.Done,
        )

        consumer.consumeTurn(flow, turnId = "t1")

        // 4 个工具事件 + Done 展开的 2 条 = 6 条
        assertEquals(6, delivered.size, "2 start + 2 result + 2 done-expansion should yield 6 messages")

        // 顺序校验
        val types = delivered.map { it["type"] }
        assertEquals(
            listOf("tool_call_start", "tool_call_start", "tool_call_complete", "tool_call_complete", "thinking_complete", "turn_complete"),
            types,
        )

        // 工具 ID 必须都在(不能被吞)
        val toolStarts = delivered.filter { it["type"] == "tool_call_start" }.map { it["toolId"] }
        assertEquals(listOf("call_A", "call_B"), toolStarts)
        val toolResults = delivered.filter { it["type"] == "tool_call_complete" }.map { it["toolId"] }
        assertEquals(listOf("call_A", "call_B"), toolResults)
    }

    // ===== 不变量 3:顺序 — Coalescable 必先于后续 Terminal =====

    @Test
    fun `order -- coalescable before terminal flushed first`() = runBlocking {
        val consumer = newConsumer()
        val flow = flowOf(
            AgentStreamEvent.TextDelta(delta = "hello "),
            AgentStreamEvent.ToolCallStart(ToolCall(id = "x", name = "t", arguments = "{}")),
        )

        consumer.consumeTurn(flow, turnId = "t1")

        assertEquals(2, delivered.size)
        assertEquals("text_delta", delivered[0]["type"])
        assertEquals("tool_call_start", delivered[1]["type"])
    }

    // ===== 不变量 4:跨 turn 不污染 =====

    @Test
    fun `state isolation -- two consecutive turns do not share buffer or lastFlush`() = runBlocking {
        val consumer = newConsumer()

        // turn 1: 1 个 Coalescable 但不跟 Terminal → 走 final flush
        consumer.consumeTurn(
            flowOf(AgentStreamEvent.TextDelta(delta = "first-turn-text")),
            turnId = "turn-1",
        )
        assertEquals(1, delivered.size, "turn-1 should flush via finally")

        // turn 2: 1 个 Coalescable 但不跟 Terminal → 走 final flush,不应被 turn-1 状态影响
        consumer.consumeTurn(
            flowOf(AgentStreamEvent.TextDelta(delta = "second-turn-text")),
            turnId = "turn-2",
        )
        assertEquals(2, delivered.size, "turn-2 should also flush via finally")
        assertEquals("first-turn-text", delivered[0]["delta"])
        assertEquals("second-turn-text", delivered[1]["delta"])
    }

    // ===== 不变量 5:finally flush — 流结束时残留 Coalescable 必送达 =====

    @Test
    fun `finally flush -- trailing coalescable still delivered after flow ends`() = runBlocking {
        val consumer = newConsumer()
        val flow = flowOf(
            AgentStreamEvent.TextDelta(delta = "tail-coalesce"),
            // 没有 Terminal — 测试 finally 路径
        )

        consumer.consumeTurn(flow, turnId = "t1")

        assertEquals(1, delivered.size, "trailing Coalescable must be flushed in finally")
        assertEquals("tail-coalesce", delivered[0]["delta"])
    }

    @Test
    fun `finally flush -- trailing coalescable delivered even when flow throws`() = runBlocking {
        val consumer = newConsumer()
        val flow = flow {
            emit(AgentStreamEvent.TextDelta(delta = "before-throw"))
            throw RuntimeException("simulated upstream failure")
        }

        assertThrows(RuntimeException::class.java) {
            runBlocking {
                consumer.consumeTurn(flow, turnId = "t1")
            }
        }

        assertEquals(1, delivered.size, "trailing Coalescable must be flushed before rethrow")
        assertEquals("before-throw", delivered[0]["delta"])
    }

    // ===== 不变量 6:sendToJS 抛错不杀消费者 =====

    @Test
    fun `sendToJS throws -- consumer logs warn and continues`() = runBlocking {
        var throwCount = 0
        val flakySend: (Map<String, Any?>) -> Unit = { msg ->
            if (msg["type"] == "tool_call_start") {
                throwCount++
                throw RuntimeException("simulated bridge failure")
            }
            delivered.add(msg)
        }
        val consumer = EventConsumer(router, flakySend, flushIntervalMs = Long.MAX_VALUE, clock = { 0L })

        val flow = flowOf(
            AgentStreamEvent.ToolCallStart(ToolCall(id = "call_X", name = "t", arguments = "{}")),
            AgentStreamEvent.ToolCallResult(
                toolCallId = "call_X", toolName = "t",
                result = """{"success":true}""", success = true,
            ),
            AgentStreamEvent.Done,
        )

        consumer.consumeTurn(flow, turnId = "t1")

        assertEquals(1, throwCount, "sendToJS was called for ToolCallStart and threw once")
        // 第二次事件(ToolCallResult)应该正常送达
        assertTrue(delivered.any { it["type"] == "tool_call_complete" }, "second event must still be delivered")
        // Done 展开的两条也应该送达
        assertTrue(delivered.any { it["type"] == "thinking_complete" })
        assertTrue(delivered.any { it["type"] == "turn_complete" })
    }

    // ===== Done 展开 =====

    @Test
    fun `done expansion -- emits thinking_complete then turn_complete in order`() = runBlocking {
        val consumer = newConsumer()
        consumer.consumeTurn(flowOf(AgentStreamEvent.Done), turnId = "t1")

        assertEquals(2, delivered.size)
        assertEquals("thinking_complete", delivered[0]["type"])
        assertEquals("turn_complete", delivered[1]["type"])
        assertEquals("t1", delivered[0]["turnId"])
        assertEquals("t1", delivered[1]["turnId"])
    }

    @Test
    fun `done expansion -- invokes onTurnEnd callback exactly once`() = runBlocking {
        val consumer = newConsumer()
        var callbackCount = 0

        consumer.consumeTurn(
            flow = flowOf(AgentStreamEvent.Done),
            turnId = "t1",
            onTurnEnd = { callbackCount++ },
        )

        assertEquals(1, callbackCount)
    }

    // ===== 修正 2026-06:首条 ModelReasoning 不再重命名为 model_reasoning_start =====

    @Test
    fun `coalesce -- first ModelReasoning of turn is model_reasoning_delta, NOT model_reasoning_start`() = runBlocking {
        // 修正 2026-06:O5.1 之后,卡片创建由 ModelReasoningRoundStart 事件单独驱动。
        // 首条 ModelReasoning 不再重命名 type — 旧"model_reasoning_start"路径会导致
        // 双重建卡(round_start 建一张,旧 start 路径又建一张),留下空"已思考"卡。
        val consumer = newConsumer()
        val flow = flowOf(
            AgentStreamEvent.ModelReasoning(delta = "think part 1 "),
            // 立即跟一个 Terminal 强制 flush
            AgentStreamEvent.ModelReasoningRoundStart(roundIndex = 1),
            AgentStreamEvent.ModelReasoning(delta = "think part 2 "),
            AgentStreamEvent.ModelReasoningRoundEnd(roundIndex = 1),
            AgentStreamEvent.Done,
        )

        consumer.consumeTurn(flow, turnId = "t1")

        val reasonings = delivered.filter {
            it["type"] == "model_reasoning_delta" || it["type"] == "model_reasoning_start"
        }
        // 2 条 ModelReasoning 都应是 model_reasoning_delta,不应有 model_reasoning_start
        assertEquals(2, reasonings.size, "Both ModelReasoning events should be delivered as delta, got ${reasonings.size}")
        assertTrue(reasonings.all { it["type"] == "model_reasoning_delta" },
            "No legacy model_reasoning_start should be emitted, got types: ${reasonings.map { it["type"] }}")

        // 同时 round_start / round_end 正常发出
        val roundStart = delivered.find { it["type"] == "model_reasoning_round_start" }
        val roundEnd = delivered.find { it["type"] == "model_reasoning_round_end" }
        assertNotNull(roundStart, "RoundStart should still be delivered")
        assertNotNull(roundEnd, "RoundEnd should still be delivered")

        // Done 展开不再含 model_reasoning_complete
        val complete = delivered.find { it["type"] == "model_reasoning_complete" }
        assertNull(complete, "Done expansion should NOT emit model_reasoning_complete (replaced by round_end)")
    }

    @Test
    fun `done expansion -- no model_reasoning_complete emitted even when ModelReasoning was present`() = runBlocking {
        // 边界:即使 turn 内有 ModelReasoning delta,Done 展开也只发 thinking_complete + turn_complete,
        // 卡片归档由 model_reasoning_round_end 负责。
        val consumer = newConsumer()
        val flow = flowOf(
            AgentStreamEvent.ModelReasoningRoundStart(roundIndex = 1),
            AgentStreamEvent.ModelReasoning(delta = "some reasoning"),
            AgentStreamEvent.ModelReasoningRoundEnd(roundIndex = 1),
            AgentStreamEvent.Done,
        )

        consumer.consumeTurn(flow, turnId = "t1")

        val types = delivered.map { it["type"] }
        assertFalse(types.contains("model_reasoning_complete"),
            "Done expansion should not emit model_reasoning_complete, got types: ${types}")
        // Done 展开固定两条:thinking_complete + turn_complete
        val doneExpansion = types.takeLast(2)
        assertEquals(listOf("thinking_complete", "turn_complete"), doneExpansion,
            "Done expansion order must be preserved, got: ${doneExpansion}")
    }

    // ===== Coalescable 行为 =====

    @Test
    fun `coalesce -- TextDelta with same key concatenates into one`() = runBlocking {
        val consumer = newConsumer()
        val flow = flowOf(
            AgentStreamEvent.TextDelta(delta = "v1 "),
            AgentStreamEvent.TextDelta(delta = "v2 "),
            AgentStreamEvent.TextDelta(delta = "v3"),
            // Terminal 触发 flush
            AgentStreamEvent.ToolCallStart(ToolCall(id = "x", name = "t", arguments = "{}")),
        )

        consumer.consumeTurn(flow, turnId = "t1")

        val textDeltas = delivered.filter { it["type"] == "text_delta" }
        assertEquals(1, textDeltas.size, "3 TextDelta with same key coalesce to 1")
        assertEquals("v1 v2 v3", textDeltas[0]["delta"], "TextDelta 是 append 语义,必须拼接")
    }

    @Test
    fun `coalesce -- Thinking uses latest-wins, not concatenation`() = runBlocking {
        val consumer = newConsumer()
        val flow = flowOf(
            AgentStreamEvent.Thinking(message = "step 1: reading file"),
            AgentStreamEvent.Thinking(message = "step 2: analyzing"),
            AgentStreamEvent.Thinking(message = "step 3: writing"),
            // Terminal 触发 flush
            AgentStreamEvent.ToolCallStart(ToolCall(id = "x", name = "t", arguments = "{}")),
        )

        consumer.consumeTurn(flow, turnId = "t1")

        val thinkings = delivered.filter {
            it["type"] == "thinking_start" || it["type"] == "thinking_update"
        }
        assertEquals(1, thinkings.size, "3 Thinking with same key coalesce to 1")
        // 验证是 latest-wins(取最后一条),不是拼接
        assertEquals("step 3: writing", thinkings[0]["message"], "Thinking 是 latest-wins,新值覆盖旧值")
        assertEquals("thinking_start", thinkings[0]["type"], "首次 Thinking 在同 turn 必须是 thinking_start")
    }

    @Test
    fun `coalesce -- first Thinking of turn is thinking_start, rest are thinking_update`() = runBlocking {
        val consumer = newConsumer()
        val flow = flowOf(
            AgentStreamEvent.Thinking(message = "first"),
            // 立刻插入 Terminal 强制 flush
            AgentStreamEvent.ToolCallStart(ToolCall(id = "x", name = "t", arguments = "{}")),
            // 再来一条 Thinking(此时 firstThinkingSent=true) — 单独走 coalesce buffer
            AgentStreamEvent.Thinking(message = "second"),
            // 再一个 Terminal flush
            AgentStreamEvent.ToolCallResult(
                toolCallId = "x", toolName = "t",
                result = """{"success":true}""", success = true,
            ),
            AgentStreamEvent.Done,
        )

        consumer.consumeTurn(flow, turnId = "t1")

        val thinkings = delivered.filter {
            it["type"] == "thinking_start" || it["type"] == "thinking_update"
        }
        assertEquals(2, thinkings.size, "2 Thinking,first 是 thinking_start,second 是 thinking_update")
        assertEquals("thinking_start", thinkings[0]["type"])
        assertEquals("first", thinkings[0]["message"])
        assertEquals("thinking_update", thinkings[1]["type"])
        assertEquals("second", thinkings[1]["message"])
    }

    @Test
    fun `coalesce -- different toolIds for ToolCallDelta do NOT cross-coalesce`() = runBlocking {
        val consumer = newConsumer()
        val flow = flowOf(
            AgentStreamEvent.ToolCallDelta(toolCallId = "call_A", toolName = "t", delta = "{"),
            AgentStreamEvent.ToolCallDelta(toolCallId = "call_B", toolName = "t", delta = "["),
            AgentStreamEvent.ToolCallDelta(toolCallId = "call_A", toolName = "t", delta = "\"path\""),
            // Terminal 触发 flush
            AgentStreamEvent.ToolCallStart(ToolCall(id = "x", name = "t", arguments = "{}")),
        )

        consumer.consumeTurn(flow, turnId = "t1")

        // call_A 合并成 "{path" — 第二次 call_A 覆盖第一次
        // call_B 保持 "["
        val deltas = delivered.filter { it["type"] == "tool_call_delta" }
        assertEquals(2, deltas.size, "call_A and call_B must be flushed independently")
        val byTool = deltas.associateBy { it["toolId"] as String }
        assertEquals("{\"path\"", byTool["call_A"]!!["delta"])
        assertEquals("[", byTool["call_B"]!!["delta"])
    }

    // ===== interval flush:可控 clock =====

    @Test
    fun `interval flush -- triggered when interval exceeded`() = runBlocking {
        var now = 0L
        val clock: () -> Long = { now }
        val consumer = EventConsumer(router, sendToJS, flushIntervalMs = 100, clock = clock)

        val flow = flow<AgentStreamEvent> {
            emit(AgentStreamEvent.TextDelta(delta = "t0"))
            now = 50  // 50ms 后,未到 interval
            emit(AgentStreamEvent.TextDelta(delta = "t50"))
            now = 200 // 200ms 后,lastFlush=0,now=200>=100,触发 interval flush
            emit(AgentStreamEvent.TextDelta(delta = "t200"))  // 这一发会先 buffer,再 flush 在 200ms 处
        }

        consumer.consumeTurn(flow, turnId = "t1")

        // 3 个 TextDelta: t0(没 flush), t50(没 flush,buffer 合并为 "t0t50"),
        // t200(interval 触发,发 "t0t50",新 buffer "t200"),
        // final flush 发 "t200"
        // 期望 delivered.size = 2: 一次 interval flush 发了 "t0t50",一次 final flush 发了 "t200"
        assertEquals(2, delivered.size)
        assertEquals("t0t50", delivered[0]["delta"])
        assertEquals("t200", delivered[1]["delta"])
    }

    // ===== 指标(间接验证:coalesce 计数从外部可观察) =====

    @Test
    fun `coalesce counter -- 3 same-key writes produce 2 coalesces and 1 delivered`() = runBlocking {
        val consumer = newConsumer()
        consumer.consumeTurn(
            flow = flowOf(
                AgentStreamEvent.TextDelta(delta = "a"),
                AgentStreamEvent.TextDelta(delta = "b"),
                AgentStreamEvent.TextDelta(delta = "c"),
                AgentStreamEvent.ToolCallStart(ToolCall(id = "x", name = "t", arguments = "{}")),
            ),
            turnId = "t1",
        )

        val textDeltas = delivered.filter { it["type"] == "text_delta" }
        assertEquals(1, textDeltas.size, "3 same-key writes -> 1 delivered (2 coalesced internally)")
    }
}
