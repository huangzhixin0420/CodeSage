package com.codesage.agent.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OnnxEmbeddingProviderTest {

    @Test
    fun `ONNX provider falls back to hash when model files are missing`(@TempDir tmpDir: File) {
        val provider = OnnxEmbeddingProvider(modelDir = tmpDir)

        assertFalse(provider.isAvailable, "Provider should be unavailable when model files are missing")
        assertEquals(MemoryEmbedding.DIMENSION, provider.dimension, "Fallback dimension should match hash dimension")

        val vector = provider.embed("user authentication service")
        assertEquals(MemoryEmbedding.DIMENSION, vector.size, "Fallback embedding dimension should be hash dimension")

        // 归一化向量
        var norm = 0.0
        for (v in vector) norm += v * v
        assertEquals(1.0, norm, 1e-6, "Fallback vector should be L2-normalized or zero")
    }

    @Test
    fun `Hash provider is always available and produces normalized vectors`() {
        val provider = HashEmbeddingProvider()

        assertTrue(provider.isAvailable, "Hash provider should always be available")
        assertEquals(MemoryEmbedding.DIMENSION, provider.dimension)

        val v1 = provider.embed("kotlin coroutine")
        val v2 = provider.embed("async programming")
        assertEquals(MemoryEmbedding.DIMENSION, v1.size)
        assertEquals(MemoryEmbedding.DIMENSION, v2.size)

        val similarity = EmbeddingMath.cosineSimilarity(v1, v2)
        assertTrue(similarity in -1.0f..1.0f, "Cosine similarity must be in [-1, 1]")
    }

    @Test
    fun `Factory returns hash provider when ONNX model is missing`(@TempDir tmpDir: File) {
        EmbeddingProviderFactory.clearCache()
        val provider = EmbeddingProviderFactory.create(modelDir = tmpDir)

        assertTrue(provider is HashEmbeddingProvider, "Factory should return Hash provider when ONNX unavailable")
        assertEquals(MemoryEmbedding.DIMENSION, provider.dimension)
    }
}
