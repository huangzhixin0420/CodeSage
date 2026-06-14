package com.codesage.agent.memory

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.*
import com.codesage.shared.utils.Logger
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * 6.3.3 / 6.9.1：embedding 能力抽象层。
 *
 * 设计目标：
 * - 统一真实 embedding 模型（ONNX）与本地 hash-based 降级实现的接口。
 * - 让 `SemanticSearch`、`BuiltInMemoryProvider` 等调用方无需关心底层模型。
 * - 真实模型不可用时自动回退到 [HashEmbeddingProvider]，保持功能可用。
 */
interface EmbeddingProvider {

    /** 输出向量的维度。 */
    val dimension: Int

    /** 当前 provider 是否可用（模型/依赖加载成功）。 */
    val isAvailable: Boolean

    /** 将单段文本编码为向量。 */
    fun embed(text: String): FloatArray

    /**
     * 批量编码。默认实现逐条调用；真实模型实现应做 batch 推理以提升吞吐。
     */
    fun embed(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
}

/**
 * 基于 [MemoryEmbedding] 的降级实现，不依赖任何外部模型。
 *
 * 保持 128 维 hash-based 词袋向量，用于 ONNX 模型缺失或加载失败时的兜底。
 */
class HashEmbeddingProvider : EmbeddingProvider {

    override val dimension: Int = MemoryEmbedding.DIMENSION
    override val isAvailable: Boolean = true

    override fun embed(text: String): FloatArray = MemoryEmbedding.embed(text)

    override fun embed(texts: List<String>): List<FloatArray> = texts.map { MemoryEmbedding.embed(it) }
}

/**
 * 基于本地 ONNX 模型的 embedding provider。
 *
 * 默认读取用户级缓存目录 `~/.codesage/models/all-MiniLM-L6-v2/` 下的：
 * - `model.onnx`：ONNX 格式模型（如 sentence-transformers/all-MiniLM-L6-v2）
 * - `tokenizer.json`：HuggingFace Tokenizer 配置
 *
 * 推理流程：
 * 1. 使用 DJL HuggingFaceTokenizer 将文本转为 input_ids / attention_mask / token_type_ids。
 * 2. 通过 ONNX Runtime 运行模型，得到 last_hidden_state。
 * 3. 对 masked token 做 mean pooling 并 L2 归一化。
 *
 * 若模型文件缺失或加载失败，[isAvailable] 为 false，调用方应回退到 [HashEmbeddingProvider]。
 */
class OnnxEmbeddingProvider(
    private val modelDir: File = File(System.getProperty("user.home"), ".codesage/models/all-MiniLM-L6-v2")
) : EmbeddingProvider {

    private val logger = Logger.getLogger<OnnxEmbeddingProvider>()

    companion object {
        /** 默认模型文件名。 */
        const val MODEL_FILE: String = "model.onnx"

        /** 默认 tokenizer 文件名。 */
        const val TOKENIZER_FILE: String = "tokenizer.json"
    }

    private val modelFile: File = File(modelDir, MODEL_FILE)
    private val tokenizerFile: File = File(modelDir, TOKENIZER_FILE)

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: HuggingFaceTokenizer? = null

    override val dimension: Int
        get() = if (isAvailable) 384 else MemoryEmbedding.DIMENSION

    override val isAvailable: Boolean
        get() = modelFile.exists() && tokenizerFile.exists() && session != null && tokenizer != null

    init {
        loadModel()
    }

    private fun loadModel() {
        if (!modelFile.exists() || !tokenizerFile.exists()) {
            logger.info("ONNX embedding model not found at ${modelDir.absolutePath}, will use hash fallback")
            return
        }

        try {
            tokenizer = HuggingFaceTokenizer.newInstance(
                tokenizerFile.toPath(),
                mapOf("maxLength" to "256")
            )

            environment = OrtEnvironment.getEnvironment()
            session = environment?.createSession(
                modelFile.absolutePath,
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
            )

            logger.info("ONNX embedding provider loaded: model=${modelFile.absolutePath}, dimension=$dimension")
        } catch (e: Exception) {
            logger.warn("Failed to load ONNX embedding model, falling back to hash: ${e.message}")
            close()
        }
    }

    override fun embed(text: String): FloatArray {
        if (!isAvailable) return HashEmbeddingProvider().embed(text)
        return embed(listOf(text)).first()
    }

    override fun embed(texts: List<String>): List<FloatArray> {
        if (!isAvailable || tokenizer == null || session == null || environment == null) {
            return HashEmbeddingProvider().embed(texts)
        }

        return try {
            runInference(texts)
        } catch (e: Exception) {
            logger.warn("ONNX inference failed, falling back to hash: ${e.message}")
            HashEmbeddingProvider().embed(texts)
        }
    }

    private fun runInference(texts: List<String>): List<FloatArray> {
        val t = tokenizer ?: throw IllegalStateException("Tokenizer not initialized")
        val s = session ?: throw IllegalStateException("ONNX session not initialized")
        val env = environment ?: throw IllegalStateException("ORT environment not initialized")

        val encodings = texts.map { t.encode(it) }
        val batchSize = encodings.size
        val maxLength = encodings.maxOfOrNull { it.ids.size } ?: 1

        val inputIds = LongArray(batchSize * maxLength)
        val attentionMask = LongArray(batchSize * maxLength)
        val typeIds = LongArray(batchSize * maxLength)

        encodings.forEachIndexed { b, encoding ->
            val ids = encoding.ids
            val mask = encoding.attentionMask
            val types = encoding.typeIds
            for (i in 0 until maxLength) {
                val idx = b * maxLength + i
                if (i < ids.size) {
                    inputIds[idx] = ids[i]
                    attentionMask[idx] = mask[i]
                    typeIds[idx] = if (i < types.size) types[i] else 0L
                } else {
                    inputIds[idx] = 0L
                    attentionMask[idx] = 0L
                    typeIds[idx] = 0L
                }
            }
        }

        val shape = longArrayOf(batchSize.toLong(), maxLength.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape).use { inputIdsTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape).use { attentionMaskTensor ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), shape).use { typeIdsTensor ->
                    val inputs = mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor,
                        "token_type_ids" to typeIdsTensor
                    )

                    s.run(inputs).use { outputs ->
                        val outputTensor = outputs[0] as? OnnxTensor
                            ?: throw IllegalStateException("ONNX output is not a tensor")

                        val hiddenSize = outputTensor.info.shape[2].toInt()
                        val floatBuffer = outputTensor.floatBuffer
                        val vectors = mutableListOf<FloatArray>()

                        for (b in 0 until batchSize) {
                            val sum = FloatArray(hiddenSize)
                            var maskSum = 0f
                            for (i in 0 until maxLength) {
                                val mask = attentionMask[b * maxLength + i]
                                if (mask == 0L) continue
                                maskSum += mask.toFloat()
                                val offset = ((b * maxLength + i) * hiddenSize)
                                for (d in 0 until hiddenSize) {
                                    sum[d] += floatBuffer.get(offset + d) * mask.toFloat()
                                }
                            }

                            val mean = if (maskSum > 0f) FloatArray(hiddenSize) { d -> sum[d] / maskSum } else sum
                            vectors.add(normalize(mean))
                        }

                        return vectors
                    }
                }
            }
        }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var normSq = 0f
        for (v in vector) normSq += v * v
        if (normSq == 0f) return vector
        val norm = sqrt(normSq.toDouble()).toFloat()
        return FloatArray(vector.size) { i -> vector[i] / norm }
    }

    private fun close() {
        try {
            session?.close()
            environment?.close()
        } catch (_: Exception) {
        }
        session = null
        environment = null
        tokenizer = null
    }
}

/**
 * EmbeddingProvider 工厂。
 *
 * 优先返回本地 ONNX provider；模型不可用时回退到 [HashEmbeddingProvider]。
 * 使用进程级缓存避免重复加载模型。
 */
object EmbeddingProviderFactory {

    private val cache = ConcurrentHashMap<String, EmbeddingProvider>()

    /**
     * 创建或复用当前进程内的 [EmbeddingProvider]。
     *
     * @param project 用于定位项目级模型目录（预留），当前实现使用用户级缓存。
     * @param modelDir 自定义 ONNX 模型目录，为 null 时使用默认 `~/.codesage/models/all-MiniLM-L6-v2`。
     */
    @JvmStatic
    fun create(
        project: com.intellij.openapi.project.Project? = null,
        modelDir: File? = null
    ): EmbeddingProvider {
        val key = modelDir?.absolutePath ?: "global"
        return cache.getOrPut(key) {
            val onnx = OnnxEmbeddingProvider(
                modelDir ?: File(
                    System.getProperty("user.home"),
                    ".codesage/models/all-MiniLM-L6-v2"
                )
            )
            if (onnx.isAvailable) onnx else HashEmbeddingProvider()
        }
    }

    /** 清空缓存，主要用于测试。 */
    @JvmStatic
    fun clearCache() {
        cache.clear()
    }
}

/**
 * 向量数学工具函数。
 *
 * 由于各 provider 输出向量维度不同，需要维度无关的余弦相似度实现。
 */
object EmbeddingMath {

    /**
     * 计算两个向量的余弦相似度，结果裁剪到 [-1, 1]。
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must have the same dimension: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(-1f, 1f)
    }
}
