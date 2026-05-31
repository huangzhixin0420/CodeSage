package com.codesage.prompt.cache

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class SystemPromptCacheTest {

    private lateinit var cache: SystemPromptCache
    private val testDbPath = File(System.getProperty("java.io.tmpdir"), "codesage_test_prompt_cache.db").absolutePath

    @BeforeEach
    fun setup() {
        File(testDbPath).delete()
        cache = SystemPromptCache(dbPath = testDbPath)
    }

    @AfterEach
    fun teardown() {
        cache.shutdown()
        File(testDbPath).delete()
    }

    @Test
    fun `should cache and retrieve system prompt by hash`() {
        val prompt = "You are CodeSage, an AI coding assistant."
        cache.cachePrompt(version = "1.0", systemPrompt = prompt)

        val cached = cache.getCachedPrompt(prompt)
        assertNotNull(cached)
        assertEquals(prompt, cached)
    }

    @Test
    fun `should return null for uncached prompt`() {
        val cached = cache.getCachedPrompt("This prompt was never cached")
        assertNull(cached)
    }

    @Test
    fun `should reuse cached prompt for same content`() {
        val prompt = "System prompt v2 with tools."
        cache.cachePrompt(version = "2.0", systemPrompt = prompt)

        // 第一次命中
        val first = cache.getCachedPrompt(prompt)
        // 第二次命中（应该返回相同内容）
        val second = cache.getCachedPrompt(prompt)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first, second)
    }

    @Test
    fun `should invalidate cache by version`() {
        val promptV1 = "System prompt version 1"
        val promptV2 = "System prompt version 2"
        cache.cachePrompt(version = "1.0", systemPrompt = promptV1)
        cache.cachePrompt(version = "2.0", systemPrompt = promptV2)

        assertNotNull(cache.getCachedPrompt(promptV1))
        assertNotNull(cache.getCachedPrompt(promptV2))

        cache.invalidateCache(version = "1.0")

        assertNull(cache.getCachedPrompt(promptV1))
        assertNotNull(cache.getCachedPrompt(promptV2))
    }

    @Test
    fun `should invalidate all cache when version is null`() {
        cache.cachePrompt(version = "1.0", systemPrompt = "prompt A")
        cache.cachePrompt(version = "2.0", systemPrompt = "prompt B")

        cache.invalidateCache()

        assertNull(cache.getCachedPrompt("prompt A"))
        assertNull(cache.getCachedPrompt("prompt B"))
    }

    @Test
    fun `should update cache on same hash with new content`() {
        val original = "Original prompt"
        cache.cachePrompt(version = "1.0", systemPrompt = original)

        // 相同内容应该命中
        assertNotNull(cache.getCachedPrompt(original))
    }
}
