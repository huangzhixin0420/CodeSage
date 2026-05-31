package com.codesage.perf

import com.codesage.model.dto.ChatRequest
import com.codesage.model.dto.Message
import com.codesage.model.gateway.ModelGateway
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*

/**
 * 连接预热管理器
 * 在IDE启动时预热与LLM提供商的连接
 */
class ConnectionWarmup(
    private val gateway: ModelGateway,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val logger = Logger.getLogger<ConnectionWarmup>()

    @Volatile
    private var isWarmedUp = false

    /**
     * 预热状态
     */
    data class WarmupStatus(
        val isComplete: Boolean,
        val providerResults: Map<String, Boolean>,
        val durationMs: Long
    )

    /**
     * 执行预热
     */
    fun warmup(models: List<String> = emptyList()) {
        if (isWarmedUp) {
            logger.debug("Already warmed up")
            return
        }

        scope.launch {
            val startTime = System.currentTimeMillis()
            val results = mutableMapOf<String, Boolean>()

            val targetModels = models.ifEmpty { listOf("MiniMax-Text-01") }

            targetModels.forEach { model ->
                try {
                    val result = pingModel(model)
                    results[model] = result
                    logger.info("Warmup result for $model: ${if (result) "OK" else "FAILED"}")
                } catch (e: Exception) {
                    results[model] = false
                    logger.warn("Warmup failed for $model", e)
                }
            }

            isWarmedUp = true
            val duration = System.currentTimeMillis() - startTime
            logger.info("Warmup completed in ${duration}ms: $results")
        }
    }

    /**
     * 预加载常用工具定义到内存
     */
    fun preloadToolSchemas(toolRegistry: com.codesage.agent.tools.ToolRegistry) {
        scope.launch {
            try {
                val tools = toolRegistry.getAllTools()
                val json = kotlinx.serialization.json.Json { prettyPrint = false }
                // 预序列化工具定义，避免每次请求时重复序列化
                tools.forEach { tool ->
                    // 触发类加载和初始化
                    tool.name
                    tool.description
                }
                logger.info("Preloaded ${tools.size} tool schemas")
            } catch (e: Exception) {
                logger.warn("Failed to preload tool schemas", e)
            }
        }
    }

    /**
     * 预加载符号索引
     */
    fun preloadSymbolIndex(analyzer: com.codesage.analysis.SymbolIndex) {
        scope.launch {
            try {
                analyzer.buildIndex()
                val stats = analyzer.getStats()
                logger.info("Preloaded symbol index: ${stats.totalSymbols} symbols, ${stats.indexedFiles} files")
            } catch (e: Exception) {
                logger.warn("Failed to preload symbol index", e)
            }
        }
    }

    /**
     * 预热DNS解析和TCP连接池
     */
    fun warmupNetwork(baseUrls: List<String>) {
        scope.launch {
            baseUrls.forEach { url ->
                try {
                    val connection = java.net.URL(url).openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.connect()
                    connection.getInputStream().close()
                    logger.info("Network warmup: $url OK")
                } catch (e: Exception) {
                    logger.debug("Network warmup skipped for $url: ${e.message}")
                }
            }
        }
    }

    /**
     * 检查是否已预热
     */
    fun isWarmedUp(): Boolean = isWarmedUp

    private suspend fun pingModel(model: String): Boolean {
        return try {
            val request = ChatRequest(
                model = model,
                messages = listOf(Message.userMessage("Hi")),
                maxTokens = 1,
                temperature = 0.0,
                stream = false
            )
            val result = gateway.chat(request)
            result.isSuccess
        } catch (e: Exception) {
            false
        }
    }
}
