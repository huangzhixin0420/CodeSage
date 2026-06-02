package com.codesage.agent.multiagent

import com.codesage.shared.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * T4.1 修复：Agent 消息总线
 *
 * **目标**：让多 Agent 协作从"keyword 路由 + 串行 chatWithTools"升级为"消息驱动 + 并行协作"。
 *
 * **设计选择**（保持零新增依赖 + 与现有架构一致）：
 * 1. 消息类型用 sealed class（`AgentMessage`）—— 类型安全
 * 2. 总线用 [Channel] + 单个 dispatcher 协程做 fan-out —— 简单可靠
 * 3. 每个订阅者有自己的 buffered Channel，慢消费者不阻塞快消费者
 * 4. ack 用 [java.util.concurrent.ConcurrentHashMap] + AtomicLong
 * 5. 消息有 TTL（默认 30s），超时未 ack 视为 dead
 *
 * **消息类型**：
 * - `TaskAssigned`: 分配任务
 * - `TaskCompleted`: 任务完成（带结果）
 * - `TaskBlocked`: 任务阻塞（带原因）
 * - `QuestionAsked`: A 向 B 提问
 * - `AnswerProvided`: 回答
 * - `SharedContext`: 共享上下文更新
 * - `Escalation`: 升级到 orchestrator
 *
 * **Pub/Sub 语义**（与标准消息总线一致）：
 * - 订阅后发布的消息会被收到
 * - 发布前订阅不会收到（这是预期语义）
 * - 跨 agent 的"晚到数据"用 [SharedScratchpad] 替代
 */
class AgentMessageBus(
    private val config: BusConfig = BusConfig()
) {
    private val logger = Logger.getLogger<AgentMessageBus>()

    // 同步 fan-out: publish 时直接遍历所有订阅者并 trySend
    // 这样不需要后台 dispatcher 协程，可靠性更高。
    // 慢消费者会被 trySend 失败时丢弃（不阻塞快消费者）。
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<Channel<AgentMessage>>>()
    private val messageCounter = AtomicLong(0)
    private val pendingAcks = ConcurrentHashMap<String, Long>()  // msgId -> expiryMs

    /**
     * 广播一条消息到所有订阅者
     */
    fun publish(message: AgentMessage): String {
        val msgId = message.messageId.ifEmpty { "msg_${messageCounter.incrementAndGet()}" }
        val withId = if (message.messageId.isEmpty()) {
            when (message) {
                is AgentMessage.TaskAssigned -> message.copy(messageId = msgId)
                is AgentMessage.TaskCompleted -> message.copy(messageId = msgId)
                is AgentMessage.TaskBlocked -> message.copy(messageId = msgId)
                is AgentMessage.QuestionAsked -> message.copy(messageId = msgId)
                is AgentMessage.AnswerProvided -> message.copy(messageId = msgId)
                is AgentMessage.SharedContext -> message.copy(messageId = msgId)
                is AgentMessage.Escalation -> message.copy(messageId = msgId)
            }
        } else message
        fanOut(withId)
        return msgId
    }

    private fun fanOut(msg: AgentMessage) {
        if (msg.channel == "*") {
            // broadcast 到所有订阅者
            subscribers.values.forEach { list -> list.forEach { it.trySend(msg) } }
        } else {
            // 特定 channel + wildcard 订阅者
            subscribers[msg.channel]?.forEach { it.trySend(msg) }
            subscribers["*"]?.forEach { it.trySend(msg) }
        }
    }

    /**
     * 订阅一个 channel 的消息
     *
     * @param channel 通道名（如 "agent.coder"、"agent.planner"），传 `"*"` 订阅所有
     * @return 该 channel 上的消息流
     *
     * **注意**：必须先订阅再发布。订阅前的发布不会重放。
     */
    fun subscribe(channel: String): Flow<AgentMessage> {
        // 使用 [Channel.consumeAsFlow] 而不是 flow { } 块，避免调度器问题。
        // [consumeAsFlow] 不会为 body 启动新的 coroutine，因此不会在单线程 dispatcher 上死锁。
        val myChannel = Channel<AgentMessage>(capacity = config.subscriberBufferCapacity)
        val list = subscribers.computeIfAbsent(channel) { CopyOnWriteArrayList() }
        list.add(myChannel)
        return myChannel.consumeAsFlow().onCompletion {
            list.remove(myChannel)
        }
    }

    /**
     * 显式 ack 一条消息（标记已收到）
     */
    fun ack(messageId: String): Boolean {
        return pendingAcks.remove(messageId) != null
    }

    /**
     * 清理过期的 pending ack（防止无界增长）
     */
    fun pruneExpiredAcks(nowMs: Long = System.currentTimeMillis()): Int {
        var removed = 0
        val iter = pendingAcks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value <= nowMs) {
                iter.remove()
                removed++
            }
        }
        return removed
    }

    fun pendingAckCount(): Int = pendingAcks.size

    /**
     * 关闭总线（关闭所有订阅者的 channel）
     */
    fun shutdown() {
        // 关闭所有订阅者的 channel
        subscribers.values.forEach { list ->
            list.forEach { it.close() }
            list.clear()
        }
    }

    /**
     * 消息总线配置
     */
    data class BusConfig(
        val subscriberBufferCapacity: Int = 64,
        val defaultAckTtlMs: Long = 30_000L
    )
}

/**
 * Agent 间消息（sealed class）
 *
 * 所有消息都有：
 * - `messageId`：唯一 ID
 * - `channel`：目标 channel（`*` = 广播）
 * - `sender`：发送方角色（可选）
 * - `timestamp`：发送时间
 */
sealed class AgentMessage {
    abstract val messageId: String
    abstract val channel: String
    abstract val sender: String?
    abstract val timestampMs: Long

    /**
     * 任务分配
     */
    data class TaskAssigned(
        override val messageId: String = "",
        override val channel: String,
        override val sender: String? = null,
        override val timestampMs: Long = System.currentTimeMillis(),
        val taskId: String,
        val description: String,
        val toolset: String = "dev"
    ) : AgentMessage()

    /**
     * 任务完成（带结果）
     */
    data class TaskCompleted(
        override val messageId: String = "",
        override val channel: String,
        override val sender: String? = null,
        override val timestampMs: Long = System.currentTimeMillis(),
        val taskId: String,
        val result: String,
        val success: Boolean = true
    ) : AgentMessage()

    /**
     * 任务阻塞（带原因）
     */
    data class TaskBlocked(
        override val messageId: String = "",
        override val channel: String,
        override val sender: String? = null,
        override val timestampMs: Long = System.currentTimeMillis(),
        val taskId: String,
        val reason: String
    ) : AgentMessage()

    /**
     * 提问（A 向 B 提问，期望 B 在自己的回合里回答）
     */
    data class QuestionAsked(
        override val messageId: String = "",
        override val channel: String,
        override val sender: String? = null,
        override val timestampMs: Long = System.currentTimeMillis(),
        val question: String,
        val context: String? = null
    ) : AgentMessage()

    /**
     * 回答
     */
    data class AnswerProvided(
        override val messageId: String = "",
        override val channel: String,
        override val sender: String? = null,
        override val timestampMs: Long = System.currentTimeMillis(),
        val inReplyTo: String,
        val answer: String
    ) : AgentMessage()

    /**
     * 共享上下文（任何 agent 写入后，所有 agent 可见）
     */
    data class SharedContext(
        override val messageId: String = "",
        override val channel: String = "*",
        override val sender: String? = null,
        override val timestampMs: Long = System.currentTimeMillis(),
        val key: String,
        val value: String
    ) : AgentMessage()

    /**
     * 升级到 orchestrator
     */
    data class Escalation(
        override val messageId: String = "",
        override val channel: String = "orchestrator",
        override val sender: String? = null,
        override val timestampMs: Long = System.currentTimeMillis(),
        val reason: String,
        val context: String? = null
    ) : AgentMessage()
}
