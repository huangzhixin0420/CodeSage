package com.codesage.agent.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T0.4 修复验证测试：有界 LRU Map
 *
 * 验证：
 * 1. 超过 maxSize 时自动淘汰最久未使用的条目
 * 2. LRU 顺序：最近 get/put 的 key 不会被淘汰
 * 3. 线程安全：并发读写不抛异常
 * 4. clear / remove / size 正确性
 * 5. computeIfAbsent 不会重复创建 value
 */
class BoundedConcurrentMapTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `evicts least recently used entry when over capacity`() {
        val map = BoundedConcurrentMap<String, Int>(maxSize = 3)
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 3)
        assertEquals(3, map.size())

        // 添加第 4 个：应淘汰 a（最久未使用）
        map.put("d", 4)
        assertEquals(3, map.size())
        assertNull(map.peek("a"), "a should be evicted")
        assertEquals(2, map.peek("b"))
        assertEquals(3, map.peek("c"))
        assertEquals(4, map.peek("d"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `get updates LRU order and prevents eviction`() {
        val map = BoundedConcurrentMap<String, Int>(maxSize = 3)
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 3)

        // 访问 a，让它变成最近使用
        val v = map["a"]
        assertEquals(1, v)

        // 添加 d：应淘汰 b（现在最久未使用）
        map.put("d", 4)
        assertEquals(3, map.size())
        assertEquals(1, map.peek("a"), "a should survive due to recent access")
        assertNull(map.peek("b"), "b should be evicted")
        assertEquals(3, map.peek("c"))
        assertEquals(4, map.peek("d"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `keysInLruOrder returns most-recent-first order`() {
        val map = BoundedConcurrentMap<String, Int>(maxSize = 5)
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 3)
        // a 现在是最久未使用
        map["c"]  // 访问 c
        // 期望顺序：c, b, a（c 最近，a 最久）
        val order = map.keysInLruOrder()
        assertEquals(listOf("c", "b", "a"), order)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `remove deletes from both data and LRU order`() {
        val map = BoundedConcurrentMap<String, Int>(maxSize = 3)
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 3)

        val prev = map.remove("b")
        assertEquals(2, prev)
        assertEquals(2, map.size())
        assertNull(map.peek("b"))
        assertEquals(listOf("c", "a"), map.keysInLruOrder())
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `clear empties the map`() {
        val map = BoundedConcurrentMap<String, Int>(maxSize = 5)
        repeat(10) { map.put("k$it", it) }
        assertEquals(5, map.size())
        map.clear()
        assertEquals(0, map.size())
        assertTrue(map.keysInLruOrder().isEmpty())
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `computeIfAbsent does not create value when key exists`() {
        val map = BoundedConcurrentMap<String, AtomicInteger>(maxSize = 5)
        val counter = AtomicInteger(0)
        map.computeIfAbsent("k") { AtomicInteger(0) }
        val first = map["k"]!!
        first.incrementAndGet()

        // 第二次 computeIfAbsent 不应创建新的 AtomicInteger
        val second = map.computeIfAbsent("k") {
            counter.incrementAndGet()
            AtomicInteger(999)
        }
        assertSame(first, second, "Should return the same AtomicInteger instance")
        assertEquals(1, first.get())
        assertEquals(0, counter.get(), "factory should not be called when key exists")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `concurrent reads and writes are safe`() {
        val map = BoundedConcurrentMap<Int, Int>(maxSize = 100)
        val start = CountDownLatch(1)
        val threads = (1..20).map { tid ->
            Thread {
                start.await()
                try {
                    repeat(1000) { i ->
                        val key = (tid * 1000 + i) % 50  // 让 key 数量在容量范围内反复循环
                        map.computeIfAbsent(key) { key * 10 }
                        map[key]  // 触发 LRU 更新
                    }
                } catch (e: Exception) {
                    fail("Thread $tid got exception: $e")
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }

        // size 应 ≤ 100
        assertTrue(map.size() <= 100, "Map size ${map.size()} should not exceed maxSize=100")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `capacity enforcement under 10000 distinct keys`() {
        val map = BoundedConcurrentMap<String, Int>(maxSize = 100)
        for (i in 0 until 10_000) {
            map.put("key_$i", i)
        }
        // size 严格 ≤ maxSize
        assertEquals(100, map.size(), "Size should be capped at maxSize")

        // 最近 100 个 key 应全部存在
        for (i in 9_900 until 10_000) {
            assertEquals(i, map.peek("key_$i"), "Recent key key_$i should be present")
        }
        // 最早的 key 应被淘汰
        assertNull(map.peek("key_0"), "Oldest key should be evicted")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `rejects non-positive capacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedConcurrentMap<String, Int>(maxSize = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedConcurrentMap<String, Int>(maxSize = -1)
        }
    }
}
