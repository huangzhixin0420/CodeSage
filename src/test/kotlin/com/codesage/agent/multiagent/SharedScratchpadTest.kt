package com.codesage.agent.multiagent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T4.4 修复验证测试：Agent 共享 Scratchpad
 */
class SharedScratchpadTest {

    @Test
    fun `put and get round trip`() {
        val pad = SharedScratchpad()
        assertTrue(pad.put("plan.dag", "yaml content here", producer = "planner"))
        val value = pad.get("plan.dag")
        assertEquals("yaml content here", value)
    }

    @Test
    fun `get returns null for missing key`() {
        val pad = SharedScratchpad()
        assertNull(pad.get("nonexistent"))
    }

    @Test
    fun `getWithMeta includes producer and timestamp`() {
        val pad = SharedScratchpad()
        pad.put("test.results", "ok", producer = "tester")
        val entry = pad.getWithMeta("test.results")
        assertNotNull(entry)
        assertEquals("test.results", entry!!.key)
        assertEquals("ok", entry.value)
        assertEquals("tester", entry.producer)
        assertTrue(entry.timestampMs > 0L)
    }

    @Test
    fun `overwrite same key replaces value`() {
        val pad = SharedScratchpad()
        pad.put("k", "v1", producer = "a")
        pad.put("k", "v2", producer = "b")
        assertEquals("v2", pad.get("k"))
        // producer should be updated
        assertEquals("b", pad.getWithMeta("k")?.producer)
    }

    @Test
    fun `remove deletes the key`() {
        val pad = SharedScratchpad()
        pad.put("k", "v")
        assertTrue(pad.remove("k"))
        assertNull(pad.get("k"))
        assertFalse(pad.remove("k"))
    }

    @Test
    fun `keys returns sorted keys`() {
        val pad = SharedScratchpad()
        pad.put("b", "1")
        pad.put("a", "2")
        pad.put("c", "3")
        assertEquals(listOf("a", "b", "c"), pad.keys())
    }

    @Test
    fun `expired entries are pruned on read`() {
        val pad = SharedScratchpad(
            SharedScratchpad.ScratchpadConfig(
                maxEntries = 10,
                entryTtlMs = 50L
            )
        )
        pad.put("k", "v")
        // 等过期
        Thread.sleep(80)
        assertNull(pad.get("k"), "expired entry should return null on get")
        assertEquals(0, pad.keys().size)
    }

    @Test
    fun `pruneExpired removes expired entries`() {
        val pad = SharedScratchpad(
            SharedScratchpad.ScratchpadConfig(
                maxEntries = 10,
                entryTtlMs = 50L
            )
        )
        pad.put("alive", "1")
        pad.put("dead", "2")
        Thread.sleep(80)
        pad.put("alive2", "3")  // 后续写入会触发 pruneExpired 自动
        // alive 也会过期（写入时间早于 50ms 前）
        // alive2 是新的
        val keys = pad.keys()
        // alive 应该被 prune；dead 已被显式清理
        assertFalse("dead" in keys)
    }

    @Test
    fun `capacity limit evicts oldest entry`() {
        val pad = SharedScratchpad(
            SharedScratchpad.ScratchpadConfig(
                maxEntries = 3,
                entryTtlMs = 60_000L
            )
        )
        pad.put("a", "1")
        Thread.sleep(10)
        pad.put("b", "2")
        Thread.sleep(10)
        pad.put("c", "3")
        Thread.sleep(10)
        pad.put("d", "4")  // 应触发 evict oldest
        val keys = pad.keys()
        assertEquals(3, keys.size, "size should remain at maxEntries")
        assertFalse("a" in keys, "oldest entry 'a' should be evicted")
        assertTrue("d" in keys)
    }

    @Test
    fun `clear removes all entries`() {
        val pad = SharedScratchpad()
        pad.put("a", "1")
        pad.put("b", "2")
        pad.clear()
        assertEquals(0, pad.size())
    }

    @Test
    fun `renderForPrompt produces markdown`() {
        val pad = SharedScratchpad()
        pad.put("plan.dag", "step1: x", producer = "planner")
        val md = pad.renderForPrompt()
        assertTrue(md.contains("## Shared Scratchpad"))
        assertTrue(md.contains("### plan.dag"))
        assertTrue(md.contains("planner"))
        assertTrue(md.contains("step1: x"))
    }

    @Test
    fun `renderForPrompt returns empty for empty pad`() {
        val pad = SharedScratchpad()
        assertEquals("(empty)", pad.renderForPrompt())
    }

    @Test
    fun `scratchpad is thread-safe for concurrent put`() {
        // 用足够大的 capacity 以便所有写入都保留
        val pad = SharedScratchpad(
            SharedScratchpad.ScratchpadConfig(
                maxEntries = 5000,
                entryTtlMs = 60_000L
            )
        )
        val threads = (0 until 10).map { i ->
            Thread {
                repeat(100) { j ->
                    pad.put("k_$i$j", "v_$i$j")
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        assertEquals(1000, pad.size(), "all 1000 puts should succeed without loss")
    }
}
