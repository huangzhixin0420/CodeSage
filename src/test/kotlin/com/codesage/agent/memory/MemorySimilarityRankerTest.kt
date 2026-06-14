package com.codesage.agent.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MemorySimilarityRankerTest {

    @Test
    fun `text overlap score returns zero for empty inputs`() {
        assertEquals(0f, MemorySimilarityRanker.textOverlapScore("", "content"), 1e-5f)
        assertEquals(0f, MemorySimilarityRanker.textOverlapScore("query", ""), 1e-5f)
        assertEquals(0f, MemorySimilarityRanker.textOverlapScore("", ""), 1e-5f)
    }

    @Test
    fun `text overlap score is higher for related texts`() {
        val related = MemorySimilarityRanker.textOverlapScore(
            "Kotlin coroutines best practices",
            "Best practices for Kotlin coroutines and flows"
        )
        val unrelated = MemorySimilarityRanker.textOverlapScore(
            "Kotlin coroutines best practices",
            "Docker container orchestration with Kubernetes"
        )

        assertTrue(related > unrelated, "Related texts should have higher overlap: $related vs $unrelated")
        assertTrue(related > 0f, "Related score should be positive")
    }

    @Test
    fun `rank by similarity falls back to text overlap when embedding is unavailable`() {
        val memories = listOf(
            BuiltInMemoryProvider.MemoryRecord(1L, "User prefers dark theme", "preference", 0L),
            BuiltInMemoryProvider.MemoryRecord(2L, "Project uses Spring Boot", "fact", 0L),
            BuiltInMemoryProvider.MemoryRecord(3L, "Docker deployment pattern", "pattern", 0L)
        )

        // Inject a failing embed function to simulate "no vector model"
        val ranker = MemorySimilarityRanker(embed = { null })
        val ranked = ranker.rankBySimilarity(memories, "dark theme preference")

        assertEquals(3, ranked.size)
        // The preference about dark theme should rank highest via text overlap
        assertEquals("User prefers dark theme", ranked.first().record.content)
        assertTrue(ranked.first().score > 0f, "Fallback score should be positive")
    }

    @Test
    fun `rank by similarity orders exact match highest`() {
        val memories = listOf(
            BuiltInMemoryProvider.MemoryRecord(1L, "User prefers dark theme", "preference", 0L),
            BuiltInMemoryProvider.MemoryRecord(2L, "Project uses Spring Boot", "fact", 0L)
        )

        val ranker = MemorySimilarityRanker()
        val ranked = ranker.rankBySimilarity(memories, "User prefers dark theme")

        assertEquals("User prefers dark theme", ranked.first().record.content)
        assertEquals(1.0f, ranked.first().score, 1e-4f)
    }
}
