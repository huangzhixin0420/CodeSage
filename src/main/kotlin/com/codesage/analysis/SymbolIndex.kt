package com.codesage.analysis

import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
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
        return fileAnalyzer?.invoke(file) ?: analyzer.analyzeFileDeep(file)
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
            val currentFiles = testFileProvider?.invoke() ?: run {
                val scope = GlobalSearchScope.projectScope(project)
                mutableSetOf<VirtualFile>().apply {
                    listOf("kt", "java", "scala", "py", "js", "ts", "go", "rs", "cpp", "c", "h").forEach { ext ->
                        try {
                            FilenameIndex.getAllFilesByExt(project, ext, scope).forEach { add(it) }
                        } catch (e: Exception) {
                            logger.debug("Failed to get files for extension $ext", e)
                        }
                    }
                }
            }

            val currentFilePaths = currentFiles.map { it.path }.toSet()

            indexLock.writeLock().withLock {
                // 清理已删除文件
                val removedPaths = indexedFileHashes.keys.filter { it !in currentFilePaths }
                removedPaths.forEach { path ->
                    removeFileSymbols(path)
                    indexedFileHashes.remove(path)
                }

                var skipped = 0L
                var analyzed = 0L

                currentFiles.forEach { file ->
                    val currentStamp = file.modificationStamp
                    val existingStamp = indexedFileHashes[file.path]

                    if (existingStamp != null && existingStamp == currentStamp) {
                        skipped++
                        return@forEach
                    }

                    try {
                        val symbols = analyzeFile(file)
                        removeFileSymbols(file.path)
                        addFileSymbols(file.path, symbols)
                        indexedFileHashes[file.path] = currentStamp
                        analyzed++
                    } catch (e: Exception) {
                        logger.debug("Failed to index file: ${file.path}", e)
                    }
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

    private fun removeFileSymbols(path: String) {
        fileIndex[path]?.forEach { symbol ->
            nameIndex[symbol.name]?.remove(symbol)
            typeIndex[symbol.type]?.remove(symbol)
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
            symbol.superTypes.forEach { superType ->
                inheritanceIndex.getOrPut(superType) { CopyOnWriteArrayList() }.add(symbol)
            }
        }
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
     * 模糊搜索符号
     */
    fun fuzzySearch(query: String, limit: Int = 20): List<PSIAnalyzer.SymbolInfo> {
        ensureIndexed()
        return indexLock.readLock().withLock {
            val lowerQuery = query.lowercase()
            nameIndex.entries
                .filter { it.key.lowercase().contains(lowerQuery) }
                .flatMap { it.value }
                .take(limit)
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
     */
    fun getStats(): IndexStats {
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
                indexVersion = version.get()
            )
        }
    }

    /**
     * 更新单个文件的索引（原子操作，写锁保护）
     */
    fun updateFile(file: VirtualFile) {
        try {
            indexLock.writeLock().withLock {
                removeFileSymbols(file.path)
                val symbols = analyzeFile(file)
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
}
