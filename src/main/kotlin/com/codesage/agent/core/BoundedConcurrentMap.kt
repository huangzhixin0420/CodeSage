package com.codesage.agent.core

import java.util.concurrent.ConcurrentHashMap

/**
 * T0.4 修复：有界 + LRU 语义的线程安全 Map
 *
 * 设计目标：
 * 1. 容量上限：超过 [maxSize] 时淘汰最久未访问的条目
 * 2. 线程安全：所有公共方法都是线程安全的
 * 3. 零新依赖：基于 [ConcurrentHashMap] + [LinkedHashSet] (synchronized) 实现
 *
 * 适用场景：用作 bounded LRU cache、错误计数器、限流 token 桶等
 * 不适用：对极致 LRU 命中率有要求（用 Caffeine / Guava Cache 更好）
 *
 * 复杂度：
 * - `get` / `computeIfAbsent` / `put` / `remove` / `size` 都是 O(1) 均摊
 * - 淘汰时 O(k)，k = 需要淘汰的条目数（通常为 1）
 *
 * LRU 语义：每次 `get` / `computeIfAbsent` / `put` 都会把该 key 移到链表头部（最近使用）。
 */
class BoundedConcurrentMap<K, V>(private val maxSize: Int) {

    init {
        require(maxSize > 0) { "maxSize must be > 0, got $maxSize" }
    }

    // 数据存储：key -> value
    private val data = ConcurrentHashMap<K, V>()

    // LRU 顺序追踪：accessOrder=true 的 LinkedHashMap 在 put/get 时自动维护访问顺序
    // 只需要 synchronized 保护；只存 key（value=Unit），删除时仅需在两个结构中删除
    private val accessOrder: LinkedHashMap<K, Unit> = LinkedHashMap(16, 0.75f, true)

    /**
     * 获取 key 对应的 value；命中时同时更新 LRU 顺序。
     */
    operator fun get(key: K): V? {
        val v = data[key] ?: return null
        // 更新 LRU 顺序
        synchronized(accessOrder) {
            // accessOrder=true 的 LinkedHashMap 会在 get/put 时自动维护顺序；
            // 但它不是线程安全的，所以需要 synchronized。这里重新 put 来触发顺序更新。
            accessOrder.remove(key)
            accessOrder[key] = Unit
        }
        return v
    }

    /**
     * 不更新 LRU 顺序的 get（用于纯读取 / 监控）
     */
    fun peek(key: K): V? = data[key]

    /**
     * 计算或获取值；命中或计算后均更新 LRU 顺序。
     */
    fun computeIfAbsent(key: K, factory: (K) -> V): V {
        val existing = data[key]
        if (existing != null) {
            synchronized(accessOrder) {
                accessOrder.remove(key)
                accessOrder[key] = Unit
            }
            return existing
        }
        // 双重检查：避免在 factory 慢路径下重复创建
        val newValue = synchronized(this) {
            val current = data[key]
            if (current != null) return@synchronized current
            val created = factory(key)
            data[key] = created
            created
        }
        synchronized(accessOrder) {
            accessOrder[key] = Unit
        }
        evictIfNeeded()
        return newValue
    }

    /**
     * 直接 put；存在则覆盖，同时更新 LRU 顺序。
     */
    fun put(key: K, value: V): V? {
        val previous = data.put(key, value)
        synchronized(accessOrder) {
            accessOrder.remove(key)
            accessOrder[key] = Unit
        }
        evictIfNeeded()
        return previous
    }

    /**
     * 删除 key，返回删除前的 value。
     */
    fun remove(key: K): V? {
        val v = data.remove(key)
        synchronized(accessOrder) {
            accessOrder.remove(key)
        }
        return v
    }

    /**
     * 清空所有数据。
     */
    fun clear() {
        data.clear()
        synchronized(accessOrder) {
            accessOrder.clear()
        }
    }

    /**
     * 当前大小。
     */
    fun size(): Int = data.size

    /**
     * 是否包含 key（不更新 LRU 顺序）。
     */
    fun containsKey(key: K): Boolean = data.containsKey(key)

    /**
     * 返回所有 key 的快照（按 LRU 顺序，最近使用在前）。
     *
     * [LinkedHashMap] 在 [accessOrder] 模式下迭起老访问顺序（oldest first），
     * 反转后变成 newest first，这是实际中更直观的表示。
     */
    fun keysInLruOrder(): List<K> = synchronized(accessOrder) {
        accessOrder.keys.toList().asReversed()
    }

    /**
     * 显式触发一次 LRU 淘汰（测试用）。
     */
    internal fun evictIfNeeded() {
        if (data.size <= maxSize) return
        synchronized(accessOrder) {
            while (data.size > maxSize && accessOrder.isNotEmpty()) {
                // LinkedHashMap 在 accessOrder=true 时，迭代器按访问顺序；
                // 第一个就是最久未使用的
                val oldest: K = accessOrder.keys.iterator().next()
                accessOrder.remove(oldest)
                data.remove(oldest)
            }
        }
    }
}
