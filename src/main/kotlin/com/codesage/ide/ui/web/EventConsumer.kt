package com.codesage.ide.ui.web

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.EventDelivery
import com.codesage.agent.core.coalesceKey
import com.codesage.agent.core.mergeWith
import com.codesage.agent.core.delivery
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * 事件投递消费者 — 取代原 JCEFChatPanel.shouldEmit 的 buggy 去重
 *
 * 核心不变量(单测保护):
 *  1. Terminal 事件永不丢
 *  2. 多 tool 并行调用,各 tool 的 start/result/delta 互不吞
 *  3. 顺序保持:Coalescable 之后出现的 Terminal 必先 flush Coalescable
 *  4. per-turn 状态隔离:Done 触发清理,跨 turn 不共享
 *  5. 取消/异常路径不丢残留:finally 强制 final flush
 *  6. sendToJS 抛错不杀消费者,只 WARN 跳过当前事件
 *
 * 关键日志(均含 turnId 字段便于 grep):
 *  - INFO  consumeTurn start / end / Done / 关键 Terminal 事件投递
 *  - DEBUG coalesce 命中/覆盖、flush 触发、pre-terminal flush
 *  - WARN  eventRouter null / sendToJS 抛错 / 异常路径
 *  - ERROR 不可恢复异常
 *
 * Done 特殊处理:
 *  EventRouter 对 Done 返回 null(它无法用单个 Map 表示"thinking_complete + turn_complete"
 *  双消息展开)。consumer 显式处理 Done 展开,避免误入 "eventRouter returned null" 的 warn 分支。
 */
class EventConsumer(
    private val eventRouter: EventRouter,
    private val sendToJS: (Map<String, Any?>) -> Unit,
    /**
     * Coalescable 事件的最大滞留时间。默认 16ms ≈ 一帧(60fps),与前端 rAF 对齐。
     * 测试时可注入 Long.MAX_VALUE 关闭 interval flush,只观察 Terminal 路径。
     */
    private val flushIntervalMs: Long = 16L,
    /** 时间源,默认 System::currentTimeMillis。测试可注入可控 clock。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = Logger.getLogger<EventConsumer>()

    /**
     * 处理单个 turn 的事件流。turn 之间状态完全隔离 — 不跨 turn 共享 buffer/lastFlush/metrics。
     *
     * @param onTurnEnd 收到 Done 后回调(供 JCEFChatPanel 清理 thinkingStarted / UI 状态等)
     */
    suspend fun consumeTurn(
        flow: Flow<AgentStreamEvent>,
        turnId: String,
        onTurnEnd: suspend () -> Unit = {},
    ) {
        val state = TurnState(turnId, onTurnEnd)
        logger.info("[EventConsumer] consumeTurn start: turnId=$turnId, flushIntervalMs=$flushIntervalMs")

        try {
            flow.collect { event ->
                state.lastFlushTime = processEvent(event, state)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.info("[EventConsumer] consumeTurn cancelled: turnId=$turnId, ${state.metrics.summary()}")
            throw e
        } catch (e: Throwable) {
            logger.error("[EventConsumer] consumeTurn failed: turnId=$turnId, ${state.metrics.summary()}", e)
            throw e
        } finally {
            // 关键: 流异常/取消/正常结束 都要把残留 Coalescable flush 出去,UI 不能半成品
            flushPending(state, reason = "final")
            state.coalesceBuffer.clear()
            logger.info("[EventConsumer] consumeTurn end: turnId=$turnId, ${state.metrics.summary()}")
        }
    }

    // ===== 内部:单事件处理 =====

    private suspend fun processEvent(event: AgentStreamEvent, state: TurnState): Long {
        state.metrics.received++
        return when (event.delivery) {
            EventDelivery.Terminal -> processTerminal(event, state)
            EventDelivery.Coalescable -> processCoalescable(event, state)
        }
    }

    private suspend fun processTerminal(event: AgentStreamEvent, state: TurnState): Long {
        val turnId = state.turnId

        // 顺序保证:Terminal 到达前,先把同 turn 的 Coalescable flush 出去
        val flushed = flushPending(state, reason = "terminal-arrives")
        if (flushed > 0 && logger.isDebugEnabled) {
            logger.debug(
                "[EventConsumer] pre-terminal flush: count=$flushed, turnId=$turnId, " +
                        "incomingType=${event::class.simpleName}"
            )
        }
        val now = clock()
        state.lastFlushTime = now

        // Done 特殊路径:展开为 thinking_complete + turn_complete 两条消息
        if (event is AgentStreamEvent.Done) {
            sendDoneExpansion(turnId, state)
            state.coalesceBuffer.clear()
            logger.info("[EventConsumer] Done received, turnId=$turnId, ${state.metrics.summary()}")
            // onTurnEnd 是上层 JCEFChatPanel 的 suspend 回调 — 在 metrics 日志之后再调,
            // 这样回调里打日志也能带正确的 turn 状态
            state.onTurnEnd()
            return now
        }

        val msg = eventRouter.toMessage(event, turnId)
        if (msg == null) {
            // 异常路径:除 Done 外,Terminal 事件应该 100% 能路由;null 视为 EventRouter bug
            logger.warn(
                "[EventConsumer] eventRouter returned null for Terminal event: " +
                        "type=${event::class.simpleName}, turnId=$turnId"
            )
            return now
        }
        try {
            sendToJS(msg)
            state.metrics.delivered++
        } catch (e: Throwable) {
            // sendToJS 内部已 catch,这里双保险;不抛、不吞,只 WARN + 跳过当前事件
            logger.warn(
                "[EventConsumer] sendToJS threw for Terminal: " +
                        "type=${event::class.simpleName}, turnId=$turnId, msgType=${msg["type"]}",
                e
            )
            return now
        }

        logTerminalDelivery(event, turnId, msg)
        return now
    }

    private fun processCoalescable(event: AgentStreamEvent, state: TurnState): Long {
        val turnId = state.turnId
        val key = event.coalesceKey
        if (key == null) {
            // 异常:Coalescable 语义事件必须有 coalesceKey;否则就是 classifier 与 coalesceKey 不一致
            logger.warn(
                "[EventConsumer] Coalescable event has null coalesceKey: " +
                        "type=${event::class.simpleName}, turnId=$turnId"
            )
            return state.lastFlushTime
        }

        // 顺序关键: 先判断是否到了 interval flush 阈值,再把当前事件合并进 buffer。
        // 这样做确保:interval flush 发出的内容是"截至上一次 flush 以来累积的",
        // 当前事件进 buffer 等下一次 flush(interval 或 terminal)再发。
        val now = clock()
        if (now - state.lastFlushTime >= flushIntervalMs) {
            flushPending(state, reason = "interval")
            state.lastFlushTime = now
        }

        val existing = state.coalesceBuffer[key]
        val merged: AgentStreamEvent = if (existing != null) {
            val m = existing.mergeWith(event)
            if (m == null) {
                // 防御:Coalescable 事件同 key 应能合并;null 意味着 classifier / coalesceKey / mergeWith 三者不一致
                logger.warn(
                    "[EventConsumer] mergeWith returned null: key=$key, turnId=$turnId, " +
                            "existingType=${existing::class.simpleName}, newType=${event::class.simpleName}"
                )
                event
            } else {
                state.metrics.coalesced++
                if (logger.isDebugEnabled) {
                    logger.debug(
                        "[EventConsumer] coalesce merge: key=$key, turnId=$turnId, " +
                                "bufferSize=${state.coalesceBuffer.size}, type=${event::class.simpleName}"
                    )
                }
                m
            }
        } else {
            if (logger.isDebugEnabled) {
                logger.debug(
                    "[EventConsumer] coalesce buffer: key=$key, turnId=$turnId, " +
                            "bufferSize=${state.coalesceBuffer.size}, type=${event::class.simpleName}"
                )
            }
            event
        }
        state.coalesceBuffer[key] = merged
        return state.lastFlushTime
    }

    // ===== 内部:Done 展开 =====

    /**
     * 把 Done 事件展开为 thinking_complete + turn_complete 两条消息依次发出。
     * 顺序固定:thinking_complete 先(前端 _onThinkingComplete),再 turn_complete(_endAITurn)。
     */
    private fun sendDoneExpansion(turnId: String, state: TurnState) {
        val thinkingMsg = mapOf<String, Any?>("type" to "thinking_complete", "turnId" to turnId, "elapsedMs" to 0)
        val turnMsg = mapOf<String, Any?>("type" to "turn_complete", "turnId" to turnId)
        try {
            sendToJS(thinkingMsg)
            state.metrics.delivered++
            sendToJS(turnMsg)
            state.metrics.delivered++
            if (logger.isDebugEnabled) {
                logger.debug("[EventConsumer] Done expanded: thinking_complete + turn_complete, turnId=$turnId")
            }
        } catch (e: Throwable) {
            logger.warn("[EventConsumer] sendToJS threw during Done expansion: turnId=$turnId", e)
        }
    }

    // ===== 内部:flush 实际投递 =====

    /**
     * 把 buffer 里的事件按插入顺序依次发到 JS。
     * @return flush 的事件数(0 表示 buffer 空)
     */
    private fun flushPending(state: TurnState, reason: String): Int {
        if (state.coalesceBuffer.isEmpty()) return 0
        val snapshot = state.coalesceBuffer.values.toList()
        state.coalesceBuffer.clear()
        state.metrics.flushed += snapshot.size

        if (logger.isDebugEnabled) {
            logger.debug(
                "[EventConsumer] flush: reason=$reason, count=${snapshot.size}, " +
                        "turnId=${state.turnId}, types=${snapshot.map { it::class.simpleName }}"
            )
        }
        for (event in snapshot) {
            val rawMsg = eventRouter.toMessage(event, state.turnId) ?: continue
            // Thinking 首/续:首条 thinking_start,后续 thinking_update
            // (EventRouter 默认 Thinking 出 "thinking_update",这里覆写 type 字段)
            val msg = when (event) {
                is AgentStreamEvent.Thinking -> {
                    if (!state.firstThinkingSent) {
                        state.firstThinkingSent = true
                        rawMsg.toMutableMap().apply { this["type"] = "thinking_start" }
                    } else {
                        rawMsg
                    }
                }

                // 修正 2026-06:O5.1 之后,ModelReasoning 全部路由为 model_reasoning_delta。
                //   不再在首条 ModelReasoning 上重写 type 为 model_reasoning_start。
                //   卡片创建由 ModelReasoningRoundStart 事件(model_reasoning_round_start)
                //   单独驱动,首条 ModelReasoning 只是首个 delta,不再触发旧"创建卡片"路径。
                //   否则会出现:round_start 先建空卡 → 旧 start 重命名又建空卡 →
                //   round_end 折叠第二张 → 留下第一张空 "已思考" 卡片。
                is AgentStreamEvent.ModelReasoning -> rawMsg

                else -> rawMsg
            }
            try {
                sendToJS(msg)
                state.metrics.delivered++
            } catch (e: Throwable) {
                logger.warn(
                    "[EventConsumer] sendToJS threw during flush: " +
                            "type=${event::class.simpleName}, turnId=${state.turnId}",
                    e
                )
            }
        }
        return snapshot.size
    }

    // ===== 内部:Terminal 投递日志(显式字段化,便于 grep) =====

    private fun logTerminalDelivery(event: AgentStreamEvent, turnId: String, msg: Map<String, Any?>) {
        val msgType = msg["type"] as? String ?: "unknown"
        when (event) {
            is AgentStreamEvent.ToolCallStart -> logger.info(
                "[EventConsumer] delivered: type=ToolCallStart, turnId=$turnId, " +
                        "toolId=${event.toolCall.id}, name=${event.toolCall.name}, msgType=$msgType"
            )

            is AgentStreamEvent.ToolCallResult -> logger.info(
                "[EventConsumer] delivered: type=ToolCallResult, turnId=$turnId, " +
                        "toolId=${event.toolCallId}, name=${event.toolName}, success=${event.success}, " +
                        "resultLen=${event.result.length}, msgType=$msgType"
            )

            is AgentStreamEvent.ToolCallError -> logger.info(
                "[EventConsumer] delivered: type=ToolCallError, turnId=$turnId, " +
                        "toolId=${event.toolCallId}, error=${event.error.take(200)}, msgType=$msgType"
            )

            is AgentStreamEvent.ToolConfirmationNeeded -> logger.info(
                "[EventConsumer] delivered: type=ToolConfirmationNeeded, turnId=$turnId, " +
                        "toolId=${event.toolCallId}, name=${event.toolName}, msgType=$msgType"
            )

            is AgentStreamEvent.CommandOutputStream -> logger.info(
                "[EventConsumer] delivered: type=CommandOutputStream, turnId=$turnId, " +
                        "toolId=${event.toolCallId}, stdoutLen=${event.stdout.length}, " +
                        "stderrLen=${event.stderr.length}, done=${event.done}, msgType=$msgType"
            )

            is AgentStreamEvent.Error -> logger.warn(
                "[EventConsumer] delivered: type=Error, turnId=$turnId, " +
                        "message=${event.message.take(200)}, msgType=$msgType"
            )

            is AgentStreamEvent.SubAgentStart -> logger.info(
                "[EventConsumer] delivered: type=SubAgentStart, turnId=$turnId, " +
                        "sessionId=${event.sessionId}, msgType=$msgType"
            )

            is AgentStreamEvent.SubAgentComplete -> logger.info(
                "[EventConsumer] delivered: type=SubAgentComplete, turnId=$turnId, " +
                        "sessionId=${event.sessionId}, success=${event.success}, msgType=$msgType"
            )

            is AgentStreamEvent.SubAgentProgress -> logger.debug(
                "[EventConsumer] delivered: type=SubAgentProgress, turnId=$turnId, " +
                        "sessionId=${event.sessionId}, message=${event.message.take(200)}"
            )

            is AgentStreamEvent.PlanGenerated -> logger.info(
                "[EventConsumer] delivered: type=PlanGenerated, turnId=$turnId, " +
                        "planId=${event.planId}, steps=${event.steps.size}, msgType=$msgType"
            )

            is AgentStreamEvent.PlanApproved -> logger.info(
                "[EventConsumer] delivered: type=PlanApproved, turnId=$turnId, " +
                        "planId=${event.planId}, msgType=$msgType"
            )

            is AgentStreamEvent.PlanModified -> logger.info(
                "[EventConsumer] delivered: type=PlanModified, turnId=$turnId, " +
                        "planId=${event.planId}, msgType=$msgType"
            )

            is AgentStreamEvent.PlanRejected -> logger.info(
                "[EventConsumer] delivered: type=PlanRejected, turnId=$turnId, " +
                        "planId=${event.planId}, msgType=$msgType"
            )

            is AgentStreamEvent.ContextCompressed -> logger.info(
                "[EventConsumer] delivered: type=ContextCompressed, turnId=$turnId, " +
                        "${event.originalTokens}→${event.compressedTokens} tokens, msgType=$msgType"
            )

            is AgentStreamEvent.SessionMigrated -> logger.info(
                "[EventConsumer] delivered: type=SessionMigrated, turnId=$turnId, " +
                        "${event.oldSessionId}→${event.newSessionId}, msgType=$msgType"
            )

            is AgentStreamEvent.ModeSuggestion -> logger.info(
                "[EventConsumer] delivered: type=ModeSuggestion, turnId=$turnId, " +
                        "effective=${event.effective}, suggestion=${event.suggestion}, msgType=$msgType"
            )

            is AgentStreamEvent.Done -> {
                // Done 在 processTerminal 里被 sendDoneExpansion 拦截了;此分支是防御性的
                logger.warn("[EventConsumer] Done reached logTerminalDelivery (should have been expanded earlier): turnId=$turnId")
            }

            is AgentStreamEvent.ModelReasoningRoundStart -> {
                // O5.1: 多轮推理起点 — 纯状态事件,无需额外日志
            }

            is AgentStreamEvent.ModelReasoningRoundEnd -> {
                // O5.1: 多轮推理终点 — 纯状态事件,无需额外日志
            }

            // 2026-06: CodeBlock 事件 - 投递日志(便于 grep 调试)
            is AgentStreamEvent.CodeBlockStart -> logger.info(
                "[EventConsumer] delivered: type=CodeBlockStart, turnId=$turnId, " +
                        "codeBlockId=${event.codeBlockId}, language=${event.language}, msgType=$msgType"
            )
            is AgentStreamEvent.CodeBlockEnd -> logger.info(
                "[EventConsumer] delivered: type=CodeBlockEnd, turnId=$turnId, " +
                        "codeBlockId=${event.codeBlockId}, msgType=$msgType"
            )

            is AgentStreamEvent.TextDelta,
            is AgentStreamEvent.Thinking,
            is AgentStreamEvent.ModelReasoning,
            is AgentStreamEvent.CodeBlockDelta,
            is AgentStreamEvent.ToolCallDelta -> {
                // 异常路径:这五种应该是 Coalescable,不应走 Terminal 分支
                // 落到这里说明 classifier 与事件流不一致 — 立刻 WARN
                logger.warn(
                    "[EventConsumer] delivered via Terminal path (classifier/state mismatch): " +
                            "type=${event::class.simpleName}, turnId=$turnId, msgType=$msgType"
                )
            }
        }
    }

    // ===== per-turn 状态 =====
    private class TurnState(
        val turnId: String,
        val onTurnEnd: suspend () -> Unit,
    ) {
        val coalesceBuffer: LinkedHashMap<String, AgentStreamEvent> = LinkedHashMap()
        var lastFlushTime: Long = 0L
        val metrics = TurnMetrics()

        /**
         * Thinking 事件首/续状态 — per-turn 隔离。
         * 首次 Thinking 路由为 "thinking_start",后续为 "thinking_update"。
         * 由 flushPending 在 sendToJS 前根据此标志改写 msg["type"]。
         */
        var firstThinkingSent: Boolean = false

        // 修正 2026-06:不再使用 firstModelReasoningSent 标志做 type 改写。
        // 卡片生命周期由 ModelReasoningRoundStart / ModelReasoningRoundEnd 事件严格驱动,
        // ModelReasoning delta 事件保持单一 type=model_reasoning_delta。
    }
}

/** per-turn 累计指标 — 写入 consumeTurn 结束 INFO 日志 */
internal class TurnMetrics {
    var received: Int = 0
    var delivered: Int = 0
    var coalesced: Int = 0
    var flushed: Int = 0

    fun summary(): String {
        val rate = if (received > 0) "%.1f%%".format(coalesced * 100.0 / received) else "n/a"
        return "received=$received, delivered=$delivered, coalesced=$coalesced (rate=$rate), flushed=$flushed"
    }
}
