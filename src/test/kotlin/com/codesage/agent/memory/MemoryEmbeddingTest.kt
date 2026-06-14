package com.codesage.agent.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MemoryEmbeddingTest {

    @Test
    fun `embed returns normalized vector of correct dimension`() {
        val vector = MemoryEmbedding.embed("I prefer Kotlin over Java")
        assertEquals(MemoryEmbedding.DIMENSION, vector.size)
        val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() })
        assertEquals(1.0, norm, 1e-4, "Vector should be L2-normalized")
    }

    @Test
    fun `same text has cosine similarity of one`() {
        val text = "Use Gradle with Kotlin DSL"
        val a = MemoryEmbedding.embed(text)
        val b = MemoryEmbedding.embed(text)
        assertEquals(1.0f, MemoryEmbedding.cosineSimilarity(a, b), 1e-5f)
    }

    @Test
    fun `similar texts have higher cosine similarity than unrelated texts`() {
        val v1 = MemoryEmbedding.embed("I prefer Kotlin for backend development")
        val v2 = MemoryEmbedding.embed("The user likes Kotlin programming language")
        val v3 = MemoryEmbedding.embed("Docker containers are lightweight")

        val sim12 = MemoryEmbedding.cosineSimilarity(v1, v2)
        val sim13 = MemoryEmbedding.cosineSimilarity(v1, v3)

        assertTrue(sim12 > sim13, "Similar texts should have higher similarity: $sim12 vs $sim13")
        assertTrue(sim12 > 0.0f, "Similarity should be positive")
    }
}
