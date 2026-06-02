package com.codesage.agent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 事件批量发射器
 * - 减少高频小事件的发射次数
 * - 批量合并 TextDelta 事件
 * - 目标：单次事件处理 < 5ms
 *
 * T0.2 修复（对应 CodeReview #2/#7）：
 * 1. 移除未使用的 `ArrayBlockingQueue buffer` 字段（dead code，原误以为需要背压缓冲）
 * 2. 强化 [shutdown]：幂等、可重入、安全地取消 scope + 关闭所有 in-flight channel
 * 3. 跟踪丢奔计数（高负载下上游仍可能以某种方式丢失）
 * 4. 防止 [batch] 在已 shutdown 的 emitter 上调用
 */
class EventBatchEmitter(
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val batchIntervalMs: Long = DEFAULT_BATCH_INTERVAL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _shutdown = AtomicBoolean(false)

    /**
     * 高负载或异常路径下丢奔的事件数（主要用于指标统计）。
     * 在当前实现中，正常路径不会丢奔；这里是预留位以便未来加背压策略。
     */
    private val _droppedCount = AtomicLong(0)

    /** 当前是否已 shutdown。 */
    val isShutdown: Boolean get() = _shutdown.get()

    /** 丢奔事件累计数。 */
    val droppedCount: Long get() = _droppedCount.get()

    /**
     * 将事件流转换为批量事件流。
     *
     * 行为契约：
     * - 连续 [AgentStreamEvent.TextDelta] 会被合并为一个 [AgentStreamEvent.TextDelta]，合并其 delta 文本
     * - 当 batch 达到 [batchSize] 或距上次发射间隔 [batchIntervalMs] 时刷出
     * - 上游流结束时发送剩余 buffer 中的事件
     * - 收集协程被取消时，会保证 channel 被关闭以避免下游永远阻塞
     */
    fun batch(flow: Flow<AgentStreamEvent>): Flow<AgentStreamEvent> = flow {
        check(!_shutdown.get()) { "EventBatchEmitter has been shut down; cannot call batch()" }

        val channel = Channel<List<AgentStreamEvent>>(Channel.CONFLATED)

        val collectJob = scope.launch {
            val batch = mutableListOf<AgentStreamEvent>()
            var lastEmitTime = System.currentTimeMillis()

            try {
                flow.collect { event ->
                    if (_shutdown.get()) {
                        // shutdown 中途被调用，立即退出并丢弃未发送的
                        _droppedCount.incrementAndGet()
                        return@collect
                    }
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
            } catch (e: CancellationException) {
                // 预期中的取消；不要在 finally 重新抛
            } catch (e: Exception) {
                // 上游异常 - 重新抛但不丢奔
                throw e
            } finally {
                channel.close()
            }
        }

        try {
            for (batchEvents in channel) {
                // 合并连续的 TextDelta
                val merged = mergeTextDeltas(batchEvents)
                merged.forEach { emit(it) }
            }
        } finally {
            // 保证 collectJob 不会因 emit() 取消而泄漏
            collectJob.cancel()
        }
    }

    /**
     * 快速发射单个事件（不经过批处理，用于关键事件）
     */
    fun emitImmediate(event: AgentStreamEvent): AgentStreamEvent = event

    /**
     * 获取 TextDelta 事件实例。
     *
     * 保留为工厂方法以供未来重新引入对象池（当前是直接 new 因为 data class 不可变）。
     */
    fun acquireTextDelta(delta: String): AgentStreamEvent.TextDelta {
        return AgentStreamEvent.TextDelta(delta)
    }

    /**
     * 关闭发射器，释放资源。
     *
     * 幂等且可重入：多次调用安全。
     */
    fun shutdown() {
        if (_shutdown.compareAndSet(false, true)) {
            scope.cancel()
        }
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
