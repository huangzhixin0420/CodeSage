package com.codesage.ide.ui.web

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.EventDelivery
import com.codesage.agent.core.delivery
import com.codesage.agent.core.coalesceKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * O5.1: EventRouter 路由 ModelReasoningRoundStart 测试
 *
 * 验证:
 *  - 路由输出 JSON 包含 type=model_reasoning_round_start, turnId, roundIndex
 *  - delivery 走 Terminal（不参与 buffer 合并）
 *  - coalesceKey 为 null（不参与合并 key）
 */
class EventRouterModelReasoningTest {

    @Test
    fun `ModelReasoningRoundStart is routed to model_reasoning_round_start with roundIndex`() {
        val router = EventRouter()
        val event = AgentStreamEvent.ModelReasoningRoundStart(roundIndex = 2)
        val msg = router.toMessage(event, turnId = "turn-7")

        assertNotNull(msg, "router should produce a JSON message")
        assertEquals("model_reasoning_round_start", msg!!["type"])
        assertEquals("turn-7", msg["turnId"])
        assertEquals(2, msg["roundIndex"])
    }

    @Test
    fun `ModelReasoningRoundStart delivery is Terminal to bypass coalescing`() {
        val event = AgentStreamEvent.ModelReasoningRoundStart(roundIndex = 1)
        // O5.1 设计要求:round start 必须 Terminal 投递,前端能在同 turn 内立刻切卡
        assertEquals(EventDelivery.Terminal, event.delivery)
        assertNull(event.coalesceKey, "round start should not participate in coalesce key")
    }

    @Test
    fun `router does not collapse round start into model_reasoning_start`() {
        // 防御:确保新增事件没被旧有的 firstModelReasoningSent 标志拦截/改写
        val router = EventRouter()
        val event = AgentStreamEvent.ModelReasoningRoundStart(roundIndex = 5)
        val msg = router.toMessage(event, "turn-x")
        // 不应被 EventConsumer 内的 "改 type 为 start" 逻辑影响:router 自己产出的 type 就是 round_start
        assertEquals("model_reasoning_round_start", msg!!["type"])
    }
}
