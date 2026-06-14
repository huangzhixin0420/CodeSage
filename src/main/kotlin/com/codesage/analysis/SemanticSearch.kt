package com.codesage.analysis

import com.codesage.agent.memory.EmbeddingMath
import com.codesage.agent.memory.EmbeddingProvider
import com.codesage.agent.memory.EmbeddingProviderFactory
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语义搜索
 * 基于符号索引、代码 chunk 向量索引和代码结构进行智能搜索。
 *
 * 6.3.3 增强：
 * - 优先使用项目级 SQLite 向量索引（chunk 级 embedding）做真实语义召回。
 * - 向量索引为空时，回退到符号级 embedding（由 [EmbeddingProvider] 提供）。
 * - 首次调用且索引为空时，会在后台异步触发索引构建（可通过 `reindex_semantic` 手动重建）。
 *
 * 支持查询结果 LRU 缓存，缓存 TTL 60 秒，容量 100 条。
 */
class SemanticSearch(
    private val project: Project,
    private val symbolIndex: SymbolIndex = SymbolIndex(project),
    private val embeddingProvider: EmbeddingProvider = EmbeddingProviderFactory.create(project),
    private val semanticIndex: SemanticIndexRepository? = project.basePath?.let {
        SemanticIndexRepository(File(it, ".codesage/semantic_index.db"))
    }
) {
    private val logger = Logger.getLogger<SemanticSearch>()

    /**
     * 搜索结果
     */
    data class SearchResult(
        val filePath: String,
        val symbol: PSIAnalyzer.SymbolInfo?,
        val matchType: MatchType,
        val relevanceScore: Double,
        val contextLines: List<String> = emptyList()
    )

    enum class MatchType {
        EXACT_NAME,       // 精确名称匹配
        FUZZY_NAME,       // 模糊名称匹配
        TYPE_MATCH,       // 类型匹配
        SIGNATURE_MATCH,  // 方法签名匹配
        COMMENT_MATCH,    // 注释匹配
        USAGE_MATCH,      // 使用场景匹配
        VECTOR_SIMILARITY // 6.3.3 向量语义相似度
    }

    // 6.3.3 符号向量 embedding 缓存：key = "version:filePath:name:type"
    private val symbolEmbeddingCache = ConcurrentHashMap<String, FloatArray>()

    // 后台索引触发标记，避免重复触发
    private val indexBuildTriggered = AtomicBoolean(false)

    // LRU 缓存：Key -> (timestamp, results)
    private val searchCache = object : LinkedHashMap<String, Pair<Long, List<SearchResult>>>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Pair<Long, List<SearchResult>>>): Boolean {
            val expired = System.currentTimeMillis() - eldest.value.first > 60000
            return size > 100 || expired
        }
    }
    private val cacheLock = Any()
    private var cacheHits = 0L
    private var cacheMisses = 0L

    private fun getCacheKey(method: String, query: String, limit: Int): String {
        return "${method}:${
            query.lowercase().trim()
        }:$limit:${symbolIndex.version.get()}:${embeddingProvider.dimension}"
    }

    private fun getCached(key: String): List<SearchResult>? {
        synchronized(cacheLock) {
            val entry = searchCache[key]
            if (entry != null) {
                val (timestamp, results) = entry
                if (System.currentTimeMillis() - timestamp <= 60000) {
                    cacheHits++
                    logger.debug("Cache hit for key: $key")
                    // 返回副本，避免外部修改缓存内容
                    return results.map { it.copy() }
                } else {
                    searchCache.remove(key)
                }
            }
            cacheMisses++
            return null
        }
    }

    private fun putCache(key: String, results: List<SearchResult>) {
        synchronized(cacheLock) {
            searchCache[key] = Pair(System.currentTimeMillis(), results)
        }
    }

    internal fun clearCache() {
        synchronized(cacheLock) {
            searchCache.clear()
            cacheHits = 0
            cacheMisses = 0
        }
    }

    internal fun getCacheStats(): Pair<Long, Long> = synchronized(cacheLock) { Pair(cacheHits, cacheMisses) }

    /**
     * 智能搜索：综合多种匹配策略
     */
    fun search(query: String, limit: Int = 20): List<SearchResult> {
        val key = getCacheKey("search", query, limit)
        getCached(key)?.let { return it }

        logger.debug("Semantic search: $query")
        val results = mutableListOf<SearchResult>()

        // 1. 精确名称匹配
        results.addAll(searchByExactName(query))

        // 2. 模糊名称匹配
        results.addAll(searchByFuzzyName(query))

        // 3. 方法签名匹配（如果查询看起来像方法签名）
        if (looksLikeMethodSignature(query)) {
            results.addAll(searchBySignature(query))
        }

        // 4. 注释/文档搜索
        results.addAll(searchByDocumentation(query))

        // 5. 6.3.3 向量语义召回
        results.addAll(searchByVector(query, limit))

        // 去重并按相关度排序
        val finalResults = results
            .distinctBy { it.filePath + (it.symbol?.name ?: "") }
            .sortedByDescending { it.relevanceScore }
            .take(limit)

        putCache(key, finalResults)
        return finalResults
    }

    /**
     * 搜索符号定义（精确查询，本身 O(1)，不缓存）
     */
    fun findDefinition(symbolName: String): List<SearchResult> {
        val symbols = symbolIndex.findByName(symbolName)
        return symbols.map { symbol ->
            SearchResult(
                filePath = symbol.filePath,
                symbol = symbol,
                matchType = MatchType.EXACT_NAME,
                relevanceScore = 1.0
            )
        }
    }

    /**
     * 搜索方法调用（结果集小，不缓存）
     */
    fun findMethodCalls(methodName: String): List<SearchResult> {
        val definitions = symbolIndex.findByName(methodName)
            .filter { it.type == PSIAnalyzer.SymbolType.METHOD }

        return definitions.map { symbol ->
            SearchResult(
                filePath = symbol.filePath,
                symbol = symbol,
                matchType = MatchType.USAGE_MATCH,
                relevanceScore = 0.9
            )
        }
    }

    /**
     * 查找相关类（基于继承关系和类型相似性）
     */
    fun findRelatedClasses(className: String): List<SearchResult> {
        val key = getCacheKey("findRelatedClasses", className, 0)
        getCached(key)?.let { return it }

        val results = mutableListOf<SearchResult>()
        val targetClass = symbolIndex.findByName(className)
            .firstOrNull { it.type == PSIAnalyzer.SymbolType.CLASS }
            ?: return emptyList()

        // 查找子类
        val implementations = symbolIndex.findImplementations(className)
        results.addAll(implementations.map {
            SearchResult(
                filePath = it.filePath,
                symbol = it,
                matchType = MatchType.TYPE_MATCH,
                relevanceScore = 0.85
            )
        })

        // 查找同一包/文件中的其他类
        val sameFile = symbolIndex.getFileSymbols(targetClass.filePath)
            .filter { it.type == PSIAnalyzer.SymbolType.CLASS && it.name != className }
        results.addAll(sameFile.map {
            SearchResult(
                filePath = it.filePath,
                symbol = it,
                matchType = MatchType.TYPE_MATCH,
                relevanceScore = 0.6
            )
        })

        putCache(key, results)
        return results
    }

    /**
     * 6.3.3 根据自然语言描述搜索：优先使用 chunk 级向量索引，其次使用符号级向量语义相似度，
     * 并融合关键词匹配分数。
     */
    fun semanticQuery(description: String, limit: Int = 20): List<SearchResult> {
        val key = getCacheKey("semanticQuery", description, limit)
        getCached(key)?.let { return it }

        ensureSemanticIndexBuildTriggered()

        val results = if (semanticIndex != null && semanticIndex.count() > 0) {
            searchByVectorIndex(description, limit)
        } else {
            searchBySymbolVector(description, limit)
        }

        putCache(key, results)
        return results
    }

    /**
     * 使用 chunk 级向量索引做语义召回。
     */
    private fun searchByVectorIndex(description: String, limit: Int): List<SearchResult> {
        val repo = semanticIndex ?: return emptyList()
        val keywords = extractKeywords(description)
        val queryVector = embeddingProvider.embed(description)

        return repo.search(queryVector, limit * 2, minScore = 0.15f)
            .mapNotNull { result ->
                val chunk = result.chunk
                val keywordScore = computeChunkKeywordScore(chunk, keywords)
                val vectorScore = result.score
                val score = 0.65 * vectorScore + 0.35 * keywordScore

                if (score <= 0.05) return@mapNotNull null

                val symbol = resolveChunkSymbol(chunk)
                SearchResult(
                    filePath = chunk.filePath,
                    symbol = symbol,
                    matchType = MatchType.VECTOR_SIMILARITY,
                    relevanceScore = score.coerceAtMost(1.0),
                    contextLines = chunk.content.lineSequence().take(8).toList()
                )
            }
            .sortedByDescending { it.relevanceScore }
            .take(limit)
    }

    /**
     * 使用符号级向量召回（chunk 索引为空时的降级路径）。
     */
    private fun searchBySymbolVector(description: String, limit: Int): List<SearchResult> {
        val keywords = extractKeywords(description)
        val allSymbols = symbolIndex.findByType(PSIAnalyzer.SymbolType.METHOD) +
                symbolIndex.findByType(PSIAnalyzer.SymbolType.CLASS)

        val queryVector = embeddingProvider.embed(description)

        return allSymbols.mapNotNull { symbol ->
            val keywordScore = computeSymbolKeywordScore(symbol, keywords)
            val symbolVector = embedSymbol(symbol)
            val vectorScore = EmbeddingMath.cosineSimilarity(queryVector, symbolVector).toDouble()
            val score = 0.65 * vectorScore + 0.35 * keywordScore

            if (score > 0.05) {
                SearchResult(
                    filePath = symbol.filePath,
                    symbol = symbol,
                    matchType = if (vectorScore > keywordScore) MatchType.VECTOR_SIMILARITY else MatchType.COMMENT_MATCH,
                    relevanceScore = score.coerceAtMost(1.0)
                )
            } else null
        }.sortedByDescending { it.relevanceScore }.take(limit)
    }

    private fun searchByExactName(query: String): List<SearchResult> {
        return symbolIndex.findByName(query).map {
            SearchResult(
                filePath = it.filePath,
                symbol = it,
                matchType = MatchType.EXACT_NAME,
                relevanceScore = 1.0
            )
        }
    }

    private fun searchByFuzzyName(query: String): List<SearchResult> {
        return symbolIndex.fuzzySearch(query, limit = 20).map {
            SearchResult(
                filePath = it.filePath,
                symbol = it,
                matchType = MatchType.FUZZY_NAME,
                relevanceScore = 0.7
            )
        }
    }

    private fun searchBySignature(query: String): List<SearchResult> {
        // 解析方法签名模式：methodName(paramType1, paramType2)
        val match = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)").find(query)
            ?: return emptyList()

        val methodName = match.groupValues[1]
        val paramTypes = match.groupValues[2].split(",").map { it.trim() }.filter { it.isNotBlank() }

        return symbolIndex.findByName(methodName)
            .filter { it.type == PSIAnalyzer.SymbolType.METHOD }
            .filter { symbol ->
                // 参数类型匹配（允许部分匹配）
                val symbolParamTypes = symbol.parameters.map { it.type }
                paramTypes.isEmpty() || paramTypes.any { pt ->
                    symbolParamTypes.any { spt -> spt.contains(pt) || pt.contains(spt) }
                }
            }
            .map {
                SearchResult(
                    filePath = it.filePath,
                    symbol = it,
                    matchType = MatchType.SIGNATURE_MATCH,
                    relevanceScore = 0.8
                )
            }
    }

    private fun searchByDocumentation(query: String): List<SearchResult> {
        val keywords = query.lowercase().split(Regex("\\s+")).filter { it.length > 3 }
        if (keywords.isEmpty()) return emptyList()

        val allSymbols = symbolIndex.findByType(PSIAnalyzer.SymbolType.METHOD) +
                symbolIndex.findByType(PSIAnalyzer.SymbolType.CLASS)

        return allSymbols.filter { symbol ->
            val doc = symbol.docComment?.lowercase() ?: return@filter false
            keywords.any { doc.contains(it) }
        }.map {
            SearchResult(
                filePath = it.filePath,
                symbol = it,
                matchType = MatchType.COMMENT_MATCH,
                relevanceScore = 0.6
            )
        }
    }

    /**
     * 6.3.3 向量语义召回：用本地 embedding 比较查询与符号（名称 + 文档）的相似度。
     */
    private fun searchByVector(query: String, limit: Int): List<SearchResult> {
        val queryVector = embeddingProvider.embed(query)
        val allSymbols = symbolIndex.findByType(PSIAnalyzer.SymbolType.METHOD) +
                symbolIndex.findByType(PSIAnalyzer.SymbolType.CLASS)

        return allSymbols.mapNotNull { symbol ->
            val symbolVector = embedSymbol(symbol)
            val similarity = EmbeddingMath.cosineSimilarity(queryVector, symbolVector)
            if (similarity > 0.15f) {
                SearchResult(
                    filePath = symbol.filePath,
                    symbol = symbol,
                    matchType = MatchType.VECTOR_SIMILARITY,
                    relevanceScore = similarity.toDouble()
                )
            } else null
        }.sortedByDescending { it.relevanceScore }.take(limit)
    }

    private fun embedSymbol(symbol: PSIAnalyzer.SymbolInfo): FloatArray {
        val cacheKey =
            "${symbolIndex.version.get()}:${symbol.filePath}:${symbol.name}:${symbol.type}:${embeddingProvider.dimension}"
        return symbolEmbeddingCache.getOrPut(cacheKey) {
            val text = buildString {
                append(symbol.name)
                symbol.docComment?.let {
                    append(" ")
                    append(it)
                }
            }
            embeddingProvider.embed(text)
        }
    }

    private fun looksLikeMethodSignature(query: String): Boolean {
        return query.contains("(") && query.contains(")")
    }

    private fun extractKeywords(description: String): List<String> {
        return description.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .distinct()
    }

    private fun computeSymbolKeywordScore(symbol: PSIAnalyzer.SymbolInfo, keywords: List<String>): Double {
        val nameTokens = symbol.name.lowercase().split(Regex("(?=[A-Z])|[_\\-]"))
        val docTokens = symbol.docComment?.lowercase()?.split(Regex("\\s+")) ?: emptyList()

        var score = 0.0
        keywords.forEach { keyword ->
            if (nameTokens.any { it.contains(keyword) || keyword.contains(it) }) {
                score += 0.5
            }
            if (docTokens.any { it.contains(keyword) }) {
                score += 0.3
            }
        }
        return score
    }

    private fun computeChunkKeywordScore(chunk: SemanticChunk, keywords: List<String>): Double {
        var score = 0.0
        val nameLower = chunk.symbolName?.lowercase() ?: ""
        val contentLower = chunk.content.lowercase()
        keywords.forEach { keyword ->
            if (nameLower.contains(keyword)) score += 0.5
            if (contentLower.contains(keyword)) score += 0.2
        }
        return score
    }

    private fun resolveChunkSymbol(chunk: SemanticChunk): PSIAnalyzer.SymbolInfo? {
        val name = chunk.symbolName ?: return null
        return symbolIndex.findByName(name).firstOrNull { it.filePath == chunk.filePath }
    }

    /**
     * 首次调用语义搜索且索引为空时，后台异步触发一次索引构建。
     * 不阻塞当前查询，后续调用可逐步使用索引结果。
     */
    private fun ensureSemanticIndexBuildTriggered() {
        if (project.isDisposedSafe() || semanticIndex == null) return
        if (semanticIndex.count() > 0) return
        if (!indexBuildTriggered.compareAndSet(false, true)) return

        Thread {
            try {
                logger.info("Background semantic indexing triggered")
                val indexer = SemanticChunkIndexer(project, semanticIndex, embeddingProvider, symbolIndex = symbolIndex)
                indexer.buildIndex(force = false)
            } catch (e: Exception) {
                logger.warn("Background semantic indexing failed", e)
            }
        }.apply {
            isDaemon = true
            name = "CodeSage-SemanticIndex-${project.name}"
            start()
        }
    }

    /**
     * 兼容测试/stub Project 的 isDisposed 查询，避免 null 返回值导致 NPE。
     */
    private fun Project.isDisposedSafe(): Boolean {
        return try {
            Project::class.java.getMethod("isDisposed").invoke(this) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }
    }
}
