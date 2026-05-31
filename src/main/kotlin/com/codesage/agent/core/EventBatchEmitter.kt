package com.codesage.agent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ArrayBlockingQueue

/**
 * 事件批量发射器
 * - 减少高频小事件的发射次数
 * - 批量合并 TextDelta 事件
 * - 使用对象池减少临时对象分配
 * - 目标：单次事件处理 < 5ms
 */
class EventBatchEmitter(
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val batchIntervalMs: Long = DEFAULT_BATCH_INTERVAL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val buffer = ArrayBlockingQueue<AgentStreamEvent>(batchSize * 2)
    // 注意：TextDelta 是 data class（不可变），对象池在此场景下无法真正复用对象，
    // 因此移除了对象池逻辑，直接创建新实例。后续如需极致优化，可改用 @JvmInline value class。


    /**
     * 将事件流转换为批量事件流
     */
    fun batch(flow: Flow<AgentStreamEvent>): Flow<AgentStreamEvent> = flow {
        val channel = Channel<List<AgentStreamEvent>>(Channel.CONFLATED)

        val collectJob = scope.launch {
            val batch = mutableListOf<AgentStreamEvent>()
            var lastEmitTime = System.currentTimeMillis()

            flow.collect { event ->
                batch.add(event)
                val now = System.currentTimeMillis()
                if (batch.size >= batchSize || now - lastEmitTime >= batchIntervalMs) {
                    channel.send(batch.toList())
                    batch.clear()
                    lastEmitTime = now
                }
            }

            if (batch.isNotEmpty()) {
                channel.send(batch)
            }
            channel.close()
        }

        for (batchEvents in channel) {
            // 合并连续的 TextDelta
            val merged = mergeTextDeltas(batchEvents)
            merged.forEach { emit(it) }
        }

        collectJob.join()
    }

    /**
     * 快速发射单个事件（不经过批处理，用于关键事件）
     */
    fun emitImmediate(event: AgentStreamEvent): AgentStreamEvent = event

    /**
     * 获取 TextDelta 事件实例
     */
    fun acquireTextDelta(delta: String): AgentStreamEvent.TextDelta {
        return AgentStreamEvent.TextDelta(delta)
    }

    /**
     * 关闭发射器，释放资源
     */
    fun shutdown() {
        scope.cancel()
    }

    private fun mergeTextDeltas(events: List<AgentStreamEvent>): List<AgentStreamEvent> {
        if (events.isEmpty()) return emptyList()
        val result = mutableListOf<AgentStreamEvent>()
        var pendingText = StringBuilder()

        for (event in events) {
            when (event) {
                is AgentStreamEvent.TextDelta -> {
                    pendingText.append(event.delta)
                }

                else -> {
                    if (pendingText.isNotEmpty()) {
                        result.add(AgentStreamEvent.TextDelta(pendingText.toString()))
                        pendingText.clear()
                    }
                    result.add(event)
                }
            }
        }

        if (pendingText.isNotEmpty()) {
            result.add(AgentStreamEvent.TextDelta(pendingText.toString()))
        }

        return result
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 10
        const val DEFAULT_BATCH_INTERVAL_MS = 16L // ~60fps
    }
}
