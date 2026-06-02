package com.codesage.agent.multiagent

import com.codesage.shared.utils.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * T4.4 修复：Agent 共享 Scratchpad
 *
 * **目标**：让多 Agent 协作的"阶段产出"可被后续 Agent 读取。
 *
 * **设计**：
 * - 键值存储（String → String）
 * - 写入时附带 `producer`（哪个 agent 写入的）+ `timestamp`
 * - 多个 sub-agent 共享同一个 scratchpad 实例（由 orchestrator 注入）
 * - 提供 TTL 机制（默认 30 分钟），过期自动清理
 * - LRU 容量限制（默认 100 条）
 *
 * **典型用法**：
 * ```
 * Planner 写入：scratchpad.put("plan.dag", dagYaml)
 * Coder 读取：scratchpad.get("plan.dag")
 * Reviewer 读取：scratchpad.get("coder.modified_files")
 * Tester 写入：scratchpad.put("test.results", testOutput)
 * ```
 */
class SharedScratchpad(
    private val config: ScratchpadConfig = ScratchpadConfig()
) {
    private val logger = Logger.getLogger<SharedScratchpad>()

    /**
     * 内部存储
     */
    private data class Entry(
        val value: String,
        val producer: String,
        val timestampMs: Long,
        val expiresAtMs: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * 写入一个值
     *
     * @return true = 写入成功，false = 因容量限制被拒绝
     */
    fun put(key: String, value: String, producer: String = "unknown"): Boolean {
        val now = System.currentTimeMillis()
        val expiresAt = now + config.entryTtlMs
        val entry = Entry(value, producer, now, expiresAt)

        // 容量检查
        if (!entries.containsKey(key) && entries.size >= config.maxEntries) {
            pruneExpired(now)
            if (entries.size >= config.maxEntries) {
                // 仍满，evict 最早写入的
                evictOldest()
            }
        }
        entries[key] = entry
        return true
    }

    /**
     * 读取一个值（自动跳过过期条目）
     */
    fun get(key: String): String? {
        val entry = entries[key] ?: return null
        if (isExpired(entry)) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    /**
     * 读取带元信息
     */
    fun getWithMeta(key: String): ScratchpadEntry? {
        val entry = entries[key] ?: return null
        if (isExpired(entry)) {
            entries.remove(key)
            return null
        }
        return ScratchpadEntry(key, entry.value, entry.producer, entry.timestampMs)
    }

    /**
     * 删除一个键
     */
    fun remove(key: String): Boolean = entries.remove(key) != null

    /**
     * 列出所有未过期的键
     */
    fun keys(): List<String> {
        val now = System.currentTimeMillis()
        return entries.entries
            .filter { !isExpired(it.value) }
            .map { it.key }
            .sorted()
    }

    /**
     * 列出所有未过期的条目
     */
    fun list(): List<ScratchpadEntry> {
        val now = System.currentTimeMillis()
        return entries.entries
            .filter { !isExpired(it.value) }
            .map { (k, v) -> ScratchpadEntry(k, v.value, v.producer, v.timestampMs) }
            .sortedBy { it.key }
    }

    /**
     * 清理过期条目
     */
    fun pruneExpired(now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            if (isExpired(iter.next().value, now)) {
                iter.remove()
                removed++
            }
        }
        return removed
    }

    /**
     * 清空所有内容
     */
    fun clear() {
        entries.clear()
    }

    /**
     * 当前条目数（含已过期的）
     */
    fun size(): Int = entries.size

    private fun isExpired(entry: Entry, now: Long = System.currentTimeMillis()): Boolean =
        entry.expiresAtMs <= now

    private fun evictOldest() {
        val oldest = entries.entries.minByOrNull { it.value.timestampMs } ?: return
        entries.remove(oldest.key)
        logger.debug("[Scratchpad] Evicted oldest entry: ${oldest.key}")
    }

    /**
     * 渲染为 Markdown（注入到 agent system prompt 末尾）
     */
    fun renderForPrompt(): String {
        val now = System.currentTimeMillis()
        val live = entries.entries.filter { !isExpired(it.value, now) }
        if (live.isEmpty()) return "(empty)"

        return buildString {
            appendLine("## Shared Scratchpad")
            appendLine()
            for (entry in live.sortedBy { it.key }) {
                val k = entry.key
                val e = entry.value
                appendLine("### $k")
                appendLine("(produced by ${e.producer})")
                appendLine("```")
                appendLine(e.value)
                appendLine("```")
            }
        }
    }

    /**
     * 配置
     */
    data class ScratchpadConfig(
        val maxEntries: Int = 100,
        val entryTtlMs: Long = 30L * 60L * 1000L  // 30 minutes
    )
}

/**
 * 写入时返回的元信息
 */
data class ScratchpadEntry(
    val key: String,
    val value: String,
    val producer: String,
    val timestampMs: Long
)
