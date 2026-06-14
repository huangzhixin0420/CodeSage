package com.codesage.analysis

import com.codesage.shared.utils.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * 符号索引
 * 缓存项目中所有重要符号，支持快速查找、增量更新和继承关系索引。
 *
 * 设计约束：
 * - 所有可变状态受 ReentrantReadWriteLock 保护，读操作并行，写操作互斥。
 * - 支持增量索引：通过 indexedFileHashes 记录文件 modificationStamp，跳过未变更文件。
 * - 支持继承反向索引：通过 inheritanceIndex 实现 O(1) 的实现类查询。
 * - SymbolInfo 为纯数据类，不持有 PsiElement 引用，已满足 PSI 解耦要求。
 */
class SymbolIndex(private val project: Project) {
    private val logger = Logger.getLogger<SymbolIndex>()

    private val indexLock = ReentrantReadWriteLock()

    // 符号名 -> 符号信息列表
    private val nameIndex = ConcurrentHashMap<String, MutableList<PSIAnalyzer.SymbolInfo>>()

    // 文件路径 -> 符号列表
    private val fileIndex = ConcurrentHashMap<String, List<PSIAnalyzer.SymbolInfo>>()

    // 类型 -> 符号列表
    private val typeIndex = ConcurrentHashMap<PSIAnalyzer.SymbolType, MutableList<PSIAnalyzer.SymbolInfo>>()

    // 6.3.4 前缀树优化：token -> 符号列表（按 camelCase/下划线分词）
    private val tokenIndex = ConcurrentHashMap<String, MutableList<PSIAnalyzer.SymbolInfo>>()

    // 继承反向索引：superType -> 实现/继承该类型的符号列表
    private val inheritanceIndex = ConcurrentHashMap<String, CopyOnWriteArrayList<PSIAnalyzer.SymbolInfo>>()

    // 增量索引：文件路径 -> modificationStamp
    private val indexedFileHashes = ConcurrentHashMap<String, Long>()

    @Volatile
    private var isIndexed = false
    internal val indexingInProgress = AtomicBoolean(false)

    // 索引版本号，每次变更递增，用于外部缓存失效
    val version = AtomicLong(0)

    // 性能统计
    private val buildSkipCount = AtomicLong(0)
    private val buildAnalyzeCount = AtomicLong(0)

    // 可替换的分析器，便于测试注入 mock
    internal var analyzer: PSIAnalyzer = PSIAnalyzer(project)
    internal var fileAnalyzer: ((VirtualFile) -> List<PSIAnalyzer.SymbolInfo>)? = null

    private fun analyzeFile(file: VirtualFile): List<PSIAnalyzer.SymbolInfo> {
        if (fileAnalyzer != null) return fileAnalyzer!!.invoke(file)
        val ext = file.extension?.lowercase() ?: ""
        return when (ext) {
            in CONFIG_EXTENSIONS -> CrossLanguageSymbolExtractor.extractConfigSymbols(file)
            in TEXT_EXTENSIONS -> CrossLanguageSymbolExtractor.extractTextSymbols(file)
            else -> analyzer.analyzeFileDeep(file)
        }
    }

    /**
     * 兼容测试环境的 read action 包装。
     * 当 ApplicationManager.getApplication() 为 null（无平台环境）或已处于 read action 中时直接执行块。
     */
    private fun <T> runInReadAction(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        return if (app != null && !app.isReadAccessAllowed) {
            app.runReadAction(Computable { block() })
        } else {
            block()
        }
    }

    // 测试用文件提供器，若不为 null 则替代 FilenameIndex 扫描
    internal var testFileProvider: (() -> Set<VirtualFile>)? = null

    /**
     * 构建索引（后台执行，不阻塞调用线程）
     */
    fun buildIndex() {
        if (indexingInProgress.compareAndSet(false, true)) {
            Thread {
                try {
                    doBuildIndex()
                } finally {
                    indexingInProgress.set(false)
                }
            }.apply { isDaemon = true; name = "CodeSage-SymbolIndex-${project.name}"; start() }
        } else {
            logger.debug("Index build already in progress, skipping")
        }
    }

    /**
     * 同步构建索引，仅供测试使用
     */
    internal fun buildIndexSync() {
        if (indexingInProgress.compareAndSet(false, true)) {
            try {
                doBuildIndex()
            } finally {
                indexingInProgress.set(false)
            }
        } else {
            logger.debug("Index build already in progress, skipping")
        }
    }

    private fun doBuildIndex() {
        logger.info("Building symbol index...")
        val startTime = System.currentTimeMillis()

        try {
            val currentFiles = testFileProvider?.invoke() ?: runInReadAction { collectProjectFiles() }

            val currentFilePaths = currentFiles.map { it.path }.toSet()

            // 在 read action 中分析需要更新的文件，避免在写锁内持有 PSI read lock
            val fileUpdates = mutableListOf<Pair<VirtualFile, List<PSIAnalyzer.SymbolInfo>>>()
            var skipped = 0L
            currentFiles.forEach { file ->
                val currentStamp = file.modificationStamp
                val existingStamp = indexedFileHashes[file.path]

                if (existingStamp != null && existingStamp == currentStamp) {
                    skipped++
                    return@forEach
                }

                try {
                    val symbols = runInReadAction { analyzeFile(file) }
                    fileUpdates.add(file to symbols)
                } catch (e: Exception) {
                    logger.debug("Failed to index file: ${file.path}", e)
                }
            }

            indexLock.writeLock().withLock {
                // 清理已删除文件
                val removedPaths = indexedFileHashes.keys.filter { it !in currentFilePaths }
                removedPaths.forEach { path ->
                    removeFileSymbols(path)
                    indexedFileHashes.remove(path)
                }

                var analyzed = 0L
                fileUpdates.forEach { (file, symbols) ->
                    removeFileSymbols(file.path)
                    addFileSymbols(file.path, symbols)
                    indexedFileHashes[file.path] = file.modificationStamp
                    analyzed++
                }

                buildSkipCount.addAndGet(skipped)
                buildAnalyzeCount.addAndGet(analyzed)
                version.incrementAndGet()
                isIndexed = true

                logger.debug("Incremental build: analyzed=$analyzed, skipped=$skipped, removed=${removedPaths.size}")
            }

            val duration = System.currentTimeMillis() - startTime
            val stats = getStats()
            logger.info("Symbol index built: ${stats.uniqueNames} names, ${stats.indexedFiles} files in ${duration}ms")
        } catch (e: Exception) {
            logger.error("Failed to build symbol index", e)
        }
    }

    private fun collectProjectFiles(): Set<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        return mutableSetOf<VirtualFile>().apply {
            (CODE_EXTENSIONS + CONFIG_EXTENSIONS + TEXT_EXTENSIONS).forEach { ext ->
                try {
                    FilenameIndex.getAllFilesByExt(project, ext, scope).forEach { add(it) }
                } catch (e: Exception) {
                    logger.debug("Failed to get files for extension $ext", e)
                }
            }
        }
    }

    private fun removeFileSymbols(path: String) {
        fileIndex[path]?.forEach { symbol ->
            nameIndex[symbol.name]?.remove(symbol)
            typeIndex[symbol.type]?.remove(symbol)
            tokenizeSymbolName(symbol.name).forEach { token ->
                tokenIndex[token]?.remove(symbol)
            }
            symbol.superTypes.forEach { superType ->
                inheritanceIndex[superType]?.remove(symbol)
            }
        }
        fileIndex.remove(path)
    }

    private fun addFileSymbols(path: String, symbols: List<PSIAnalyzer.SymbolInfo>) {
        fileIndex[path] = symbols
        symbols.forEach { symbol ->
            nameIndex.getOrPut(symbol.name) { mutableListOf() }.add(symbol)
            typeIndex.getOrPut(symbol.type) { mutableListOf() }.add(symbol)
            tokenizeSymbolName(symbol.name).forEach { token ->
                tokenIndex.getOrPut(token) { CopyOnWriteArrayList() }.add(symbol)
            }
            symbol.superTypes.forEach { superType ->
                inheritanceIndex.getOrPut(superType) { CopyOnWriteArrayList() }.add(symbol)
            }
        }
    }

    /**
     * 6.3.4 将符号名拆分为可前缀匹配的小写 token。
     *
     * 例如：
     * - `UserService` → [userservice, user, service]
     * - `get_user_by_id` → [get_user_by_id, get, user, by, id]
     */
    private fun tokenizeSymbolName(name: String): List<String> {
        val tokens = mutableSetOf<String>()
        val lower = name.lowercase()
        tokens.add(lower)

        // camelCase / PascalCase 拆分
        Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
            .split(name)
            .map { it.lowercase().trim() }
            .filter { it.length > 1 }
            .forEach { tokens.add(it) }

        // 下划线 / 连字符 / 点号拆分
        lower.split(Regex("[_.\\-]+"))
            .filter { it.length > 1 }
            .forEach { tokens.add(it) }

        return tokens.toList()
    }

    /**
     * 按名称查找符号
     */
    fun findByName(name: String): List<PSIAnalyzer.SymbolInfo> {
        ensureIndexed()
        return indexLock.readLock().withLock {
            nameIndex[name]?.toList() ?: emptyList()
        }
    }

    /**
     * 6.3.4 模糊搜索符号（token 前缀索引优化）。
     *
     * 先按 query 拆分出的 token 在前缀索引中命中候选，再按匹配 token 数量排序；
     * 同时保留子串匹配作为兜底，避免 token 拆分遗漏。
     */
    fun fuzzySearch(query: String, limit: Int = 20): List<PSIAnalyzer.SymbolInfo> {
        ensureIndexed()
        return indexLock.readLock().withLock {
            val scores = mutableMapOf<PSIAnalyzer.SymbolInfo, Double>()
            val lowerQuery = query.lowercase()
            val queryTokens = tokenizeSymbolName(query)

            // token 前缀匹配：命中 token 越多、token 本身越完整，分数越高
            queryTokens.forEach { qt ->
                tokenIndex.entries
                    .filter { (token, _) -> token.startsWith(qt) || qt.startsWith(token) }
                    .forEach { (token, symbols) ->
                        val tokenScore = if (token == qt) 2.0 else 1.0 + qt.length.toDouble() / token.length
                        symbols.forEach { symbol ->
                            scores.merge(symbol, tokenScore) { existing, added -> existing + added }
                        }
                    }
            }

            // 兜底：名称子串匹配
            nameIndex.entries
                .filter { it.key.lowercase().contains(lowerQuery) }
                .flatMap { it.value }
                .forEach { symbol ->
                    scores.merge(symbol, 0.5) { existing, added -> existing + added }
                }

            scores.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
        }
    }

    /**
     * 按类型查找
     */
    fun findByType(type: PSIAnalyzer.SymbolType): List<PSIAnalyzer.SymbolInfo> {
        ensureIndexed()
        return indexLock.readLock().withLock {
            typeIndex[type]?.toList() ?: emptyList()
        }
    }

    /**
     * 获取文件中的所有符号
     */
    fun getFileSymbols(filePath: String): List<PSIAnalyzer.SymbolInfo> {
        ensureIndexed()
        return indexLock.readLock().withLock {
            fileIndex[filePath]?.toList() ?: emptyList()
        }
    }

    /**
     * 查找实现某接口或继承某类的所有符号（O(1) 查询）
     */
    fun findImplementations(interfaceName: String): List<PSIAnalyzer.SymbolInfo> {
        ensureIndexed()
        return indexLock.readLock().withLock {
            inheritanceIndex[interfaceName]?.toList() ?: emptyList()
        }
    }

    /**
     * 获取索引统计
     *
     * 与其他读路径(searchSymbol/findByName 等)不同, getStats 之前从未调
     * ensureIndexed() — 用户在项目刚打开、未跑过任何搜索时就调
     * get_project_stats 会拿到全 0。修:
     *   1. 先 ensureIndexed()(如未起过则 fire-and-forget 触发后台构建)
     *   2. 若 indexingInProgress = true, 限时阻塞等待已完成 — 让 LLM 拿到的
     *      stats 反映真实索引, 而不是 0
     *   3. 等待上限 0 表示不等待(给单元测试和"对实时性敏感"的调用方用)
     */
    fun getStats(waitMs: Long = DEFAULT_STATS_WAIT_MS): IndexStats {
        ensureIndexed()
        if (waitMs > 0) {
            awaitIndexingDone(waitMs)
        }
        return indexLock.readLock().withLock {
            val total = nameIndex.values.sumOf { it.size }
            val skips = buildSkipCount.get()
            val analyzed = buildAnalyzeCount.get()
            val totalBuildOps = skips + analyzed
            IndexStats(
                totalSymbols = total,
                uniqueNames = nameIndex.size,
                indexedFiles = fileIndex.size,
                classCount = typeIndex[PSIAnalyzer.SymbolType.CLASS]?.size ?: 0,
                methodCount = typeIndex[PSIAnalyzer.SymbolType.METHOD]?.size ?: 0,
                fieldCount = typeIndex[PSIAnalyzer.SymbolType.FIELD]?.size ?: 0,
                cacheHitRate = if (totalBuildOps > 0) skips.toDouble() / totalBuildOps else 0.0,
                indexVersion = version.get(),
            )
        }
    }

    /** 默认 getStats 阻塞上限,避免 LLM 工具调用卡死 IDE 太久。 */
    private fun awaitIndexingDone(deadlineMs: Long) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (indexingInProgress.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * 更新单个文件的索引（原子操作，写锁保护）
     */
    fun updateFile(file: VirtualFile) {
        try {
            val symbols = runInReadAction { analyzeFile(file) }
            indexLock.writeLock().withLock {
                removeFileSymbols(file.path)
                addFileSymbols(file.path, symbols)
                indexedFileHashes[file.path] = file.modificationStamp
                version.incrementAndGet()
            }
            logger.debug("Updated index for ${file.path}")
        } catch (e: Exception) {
            logger.warn("Failed to update index for ${file.path}", e)
        }
    }

    /**
     * 直接更新指定路径的符号（供测试使用，绕过 PSI 分析）
     */
    internal fun updateFileSymbolsForTest(path: String, symbols: List<PSIAnalyzer.SymbolInfo>) {
        indexLock.writeLock().withLock {
            removeFileSymbols(path)
            addFileSymbols(path, symbols)
            version.incrementAndGet()
        }
    }

    /**
     * 清空所有索引（供测试使用）
     */
    internal fun clearIndexesForTest() {
        indexLock.writeLock().withLock {
            nameIndex.clear()
            fileIndex.clear()
            typeIndex.clear()
            tokenIndex.clear()
            inheritanceIndex.clear()
            indexedFileHashes.clear()
            buildSkipCount.set(0)
            buildAnalyzeCount.set(0)
            version.incrementAndGet()
            isIndexed = false
        }
    }

    /**
     * 获取已索引文件哈希表副本（供测试使用）
     */
    internal fun getIndexedFileHashesForTest(): Map<String, Long> =
        indexLock.readLock().withLock { indexedFileHashes.toMap() }

    private fun ensureIndexed() {
        if (!isIndexed && !indexingInProgress.get()) {
            buildIndex()
        }
    }

    data class IndexStats(
        val totalSymbols: Int,
        val uniqueNames: Int,
        val indexedFiles: Int,
        val classCount: Int,
        val methodCount: Int,
        val fieldCount: Int,
        val cacheHitRate: Double = 0.0,
        val indexVersion: Long = 0
    )

    companion object {
        /**
         * getStats 默认阻塞上限 (3s)。大项目首扫可能更慢, 3s 拿到部分数据也好
         * 过让 LLM 看到全 0; 真要等就用更长的 waitMs 显式调。
         */
        const val DEFAULT_STATS_WAIT_MS: Long = 3_000

        /**
         * 6.5.3：跨语言索引支持的代码文件扩展名。
         */
        val CODE_EXTENSIONS = setOf(
            "kt", "kts", "java", "scala", "py", "js", "jsx", "ts", "tsx",
            "go", "rs", "cpp", "c", "h", "vue", "svelte"
        )

        /**
         * 6.5.3：配置文件扩展名，索引顶层 key 与文件名。
         */
        val CONFIG_EXTENSIONS = setOf("json", "yaml", "yml")

        /**
         * 6.5.3：文本类文件扩展名，索引标题 / SQL 对象 / 组件名。
         */
        val TEXT_EXTENSIONS = setOf("sql", "md")
    }
}
