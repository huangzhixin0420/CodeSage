package com.codesage.perf

import com.codesage.model.dto.ChatRequest
import com.codesage.model.dto.ChatResponse
import com.codesage.shared.utils.Logger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 响应缓存
 * 缓存LLM响应以减少重复请求
 */
class ResponseCache(
    private val maxEntries: Int = 100,
    private val defaultTtlMs: Long = 5 * 60 * 1000 // 5分钟
) {
    private val logger = Logger.getLogger<ResponseCache>()
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    data class CacheEntry(
        val response: ChatResponse,
        val cachedAt: Long,
        val ttlMs: Long
    )

    /**
     * 生成缓存键
     */
    fun generateKey(request: ChatRequest): String {
        val keyData = buildString {
            append(request.model)
            append("|")
            append(request.temperature)
            append("|")
            append(request.maxTokens)
            append("|")
            request.messages.forEach { msg ->
                append(msg.role)
                append(":")
                append(msg.content ?: "")
                append("|")
            }
            request.tools?.forEach { tool ->
                append(tool.name)
                append("|")
            }
        }
        return hash(keyData)
    }

    /**
     * 获取缓存响应
     */
    fun get(request: ChatRequest): ChatResponse? {
        val key = generateKey(request)
        val entry = cache[key] ?: return null

        if (isExpired(entry)) {
            cache.remove(key)
            logger.debug("Cache entry expired: $key")
            return null
        }

        logger.debug("Cache hit: $key")
        return entry.response
    }

    /**
     * 存储响应到缓存
     */
    fun put(request: ChatRequest, response: ChatResponse, ttlMs: Long = defaultTtlMs) {
        // 不缓存流式请求
        if (request.stream) return

        // 不缓存包含错误的响应
        if (response.choices.isEmpty()) return

        cleanupIfNeeded()

        val key = generateKey(request)
        cache[key] = CacheEntry(
            response = response,
            cachedAt = System.currentTimeMillis(),
            ttlMs = ttlMs
        )
        logger.debug("Cached response: $key")
    }

    /**
     * 使缓存失效
     */
    fun invalidate(model: String? = null) {
        if (model == null) {
            val count = cache.size
            cache.clear()
            logger.info("Invalidated all cache entries ($count)")
        } else {
            val toRemove = cache.keys.filter { it.startsWith("${model}|") }
            toRemove.forEach { cache.remove(it) }
            logger.info("Invalidated ${toRemove.size} cache entries for model: $model")
        }
    }

    /**
     * 获取缓存统计
     */
    fun getStats(): CacheStats {
        val now = System.currentTimeMillis()
        val valid = cache.values.count { !isExpired(it) }
        val expired = cache.size - valid
        return CacheStats(
            totalEntries = cache.size,
            validEntries = valid,
            expiredEntries = expired,
            hitRate = if (totalRequests > 0) cacheHits.toDouble() / totalRequests else 0.0
        )
    }

    @Volatile
    private var totalRequests: Long = 0

    @Volatile
    private var cacheHits: Long = 0

    fun recordRequest(hit: Boolean) {
        totalRequests++
        if (hit) cacheHits++
    }

    private fun isExpired(entry: CacheEntry): Boolean {
        return System.currentTimeMillis() - entry.cachedAt > entry.ttlMs
    }

    private fun cleanupIfNeeded() {
        if (cache.size >= maxEntries) {
            // 移除最旧的条目
            val now = System.currentTimeMillis()
            val sorted = cache.entries.sortedBy { it.value.cachedAt }
            val toRemove = sorted.take(maxEntries / 4)
            toRemove.forEach { cache.remove(it.key) }
            logger.debug("Cleaned up ${toRemove.size} old cache entries")
        }
    }

    private fun hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    data class CacheStats(
        val totalEntries: Int,
        val validEntries: Int,
        val expiredEntries: Int,
        val hitRate: Double
    )
}
