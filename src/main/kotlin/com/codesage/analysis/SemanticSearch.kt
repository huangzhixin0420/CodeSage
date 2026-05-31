package com.codesage.analysis

import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project

/**
 * 语义搜索
 * 基于符号索引和代码结构进行智能搜索
 * 支持查询结果 LRU 缓存，缓存 TTL 60 秒，容量 100 条
 */
class SemanticSearch(
    private val project: Project,
    private val symbolIndex: SymbolIndex = SymbolIndex(project)
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
        EXACT_NAME,      // 精确名称匹配
        FUZZY_NAME,      // 模糊名称匹配
        TYPE_MATCH,      // 类型匹配
        SIGNATURE_MATCH, // 方法签名匹配
        COMMENT_MATCH,   // 注释匹配
        USAGE_MATCH      // 使用场景匹配
    }

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
        return "${method}:${query.lowercase().trim()}:$limit:${symbolIndex.version.get()}"
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
     * 根据自然语言描述搜索（简化版：基于关键词提取）
     */
    fun semanticQuery(description: String, limit: Int = 20): List<SearchResult> {
        val key = getCacheKey("semanticQuery", description, limit)
        getCached(key)?.let { return it }

        // 提取关键词（简单分词）
        val keywords = description.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .distinct()

        if (keywords.isEmpty()) return emptyList()

        val allSymbols = symbolIndex.findByType(PSIAnalyzer.SymbolType.METHOD) +
                symbolIndex.findByType(PSIAnalyzer.SymbolType.CLASS)

        val results = allSymbols.mapNotNull { symbol ->
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

            if (score > 0) {
                SearchResult(
                    filePath = symbol.filePath,
                    symbol = symbol,
                    matchType = MatchType.COMMENT_MATCH,
                    relevanceScore = score.coerceAtMost(1.0)
                )
            } else null
        }.sortedByDescending { it.relevanceScore }.take(limit)

        putCache(key, results)
        return results
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

    private fun looksLikeMethodSignature(query: String): Boolean {
        return query.contains("(") && query.contains(")")
    }
}
