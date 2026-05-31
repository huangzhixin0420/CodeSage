package com.codesage.observability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * 指标收集器
 * 收集性能指标：计数器、计时器、直方图
 */
class MetricsCollector {

    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val timers = ConcurrentHashMap<String, TimerStats>()
    private val gauges = ConcurrentHashMap<String, () -> Number>()

    // === 计数器 ===

    fun incrementCounter(name: String, delta: Long = 1) {
        counters.getOrPut(name) { AtomicLong(0) }.addAndGet(delta)
    }

    fun getCounter(name: String): Long {
        return counters[name]?.get() ?: 0
    }

    // === 计时器 ===

    fun recordTimer(name: String, durationMs: Long) {
        timers.getOrPut(name) { TimerStats() }.record(durationMs)
    }

    fun <T> time(name: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            recordTimer(name, System.currentTimeMillis() - start)
        }
    }

    suspend fun <T> timeSuspend(name: String, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            recordTimer(name, System.currentTimeMillis() - start)
        }
    }

    fun getTimerStats(name: String): TimerSnapshot? {
        return timers[name]?.snapshot()
    }

    // === 仪表盘 ===

    fun registerGauge(name: String, supplier: () -> Number) {
        gauges[name] = supplier
    }

    fun getGauge(name: String): Number? {
        return gauges[name]?.invoke()
    }

    // === 批量查询 ===

    fun getAllCounters(): Map<String, Long> {
        return counters.mapValues { it.value.get() }
    }

    fun getAllTimers(): Map<String, TimerSnapshot> {
        return timers.mapValues { it.value.snapshot() }
    }

    fun getAllGauges(): Map<String, Number> {
        return gauges.mapNotNull { (name, supplier) ->
            try {
                name to supplier.invoke()
            } catch (e: Exception) {
                null
            }
        }.toMap()
    }

    /**
     * 导出所有指标
     */
    fun export(): MetricsSnapshot {
        return MetricsSnapshot(
            counters = getAllCounters(),
            timers = getAllTimers(),
            gauges = getAllGauges(),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 重置所有指标
     */
    fun reset() {
        counters.clear()
        timers.clear()
        gauges.clear()
    }

    // === 内部类 ===

    private class TimerStats {
        private val count = AtomicLong(0)
        private val total = LongAdder()
        private val min = AtomicLong(Long.MAX_VALUE)
        private val max = AtomicLong(Long.MIN_VALUE)

        fun record(durationMs: Long) {
            count.incrementAndGet()
            total.add(durationMs)
            min.updateAndGet { if (durationMs < it) durationMs else it }
            max.updateAndGet { if (durationMs > it) durationMs else it }
        }

        fun snapshot(): TimerSnapshot {
            val c = count.get()
            return TimerSnapshot(
                count = c,
                totalMs = total.sum(),
                avgMs = if (c > 0) total.sum().toDouble() / c else 0.0,
                minMs = if (c > 0) min.get() else 0,
                maxMs = if (c > 0) max.get() else 0
            )
        }
    }

    data class TimerSnapshot(
        val count: Long,
        val totalMs: Long,
        val avgMs: Double,
        val minMs: Long,
        val maxMs: Long
    )

    data class MetricsSnapshot(
        val counters: Map<String, Long>,
        val timers: Map<String, TimerSnapshot>,
        val gauges: Map<String, Number>,
        val timestamp: Long
    )
}
