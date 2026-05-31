package com.codesage.observability

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MetricsCollectorTest {

    @Test
    fun `counter increments correctly`() {
        val metrics = MetricsCollector()
        metrics.incrementCounter("requests", 5)
        assertEquals(5, metrics.getCounter("requests"))

        metrics.incrementCounter("requests", 3)
        assertEquals(8, metrics.getCounter("requests"))
    }

    @Test
    fun `timer records correctly`() {
        val metrics = MetricsCollector()
        metrics.recordTimer("llm_call", 150)
        metrics.recordTimer("llm_call", 250)

        val stats = metrics.getTimerStats("llm_call")
        assertNotNull(stats)
        assertEquals(2, stats?.count)
        assertEquals(200.0, stats?.avgMs ?: 0.0, 0.01)
        assertEquals(150, stats?.minMs)
        assertEquals(250, stats?.maxMs)
    }

    @Test
    fun `time block measures duration`() {
        val metrics = MetricsCollector()
        metrics.time("operation") {
            Thread.sleep(50)
        }

        val stats = metrics.getTimerStats("operation")
        assertNotNull(stats)
        assertTrue((stats?.avgMs ?: 0.0) >= 40.0)
    }

    @Test
    fun `gauge reads supplier value`() {
        val metrics = MetricsCollector()
        var value = 42
        metrics.registerGauge("queue_size") { value }

        assertEquals(42, metrics.getGauge("queue_size")?.toInt())

        value = 100
        assertEquals(100, metrics.getGauge("queue_size")?.toInt())
    }

    @Test
    fun `export returns all metrics`() {
        val metrics = MetricsCollector()
        metrics.incrementCounter("a", 1)
        metrics.recordTimer("b", 100)
        metrics.registerGauge("c") { 5 }

        val snapshot = metrics.export()
        assertEquals(1, snapshot.counters["a"])
        assertNotNull(snapshot.timers["b"])
        assertEquals(5, snapshot.gauges["c"])
    }

    @Test
    fun `reset clears all metrics`() {
        val metrics = MetricsCollector()
        metrics.incrementCounter("x", 1)
        metrics.reset()

        assertEquals(0, metrics.getCounter("x"))
    }
}
