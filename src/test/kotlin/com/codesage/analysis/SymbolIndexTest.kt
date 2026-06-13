package com.codesage.analysis

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.lang.reflect.Proxy

class SymbolIndexTest {

    private fun createStubProject(): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "TestProject"
                "toString" -> "TestProject"
                else -> null
            }
        } as Project
    }

    private fun createVirtualFile(path: String, modificationStamp: Long): VirtualFile {
        return object : VirtualFile() {
            override fun getName(): String = path.substringAfterLast('/')
            override fun getFileSystem(): VirtualFileSystem = throw NotImplementedError()
            override fun getPath(): String = path
            override fun isDirectory(): Boolean = false
            override fun getChildren(): Array<VirtualFile> = emptyArray()
            override fun getParent(): VirtualFile? = null
            override fun contentsToByteArray(): ByteArray = ByteArray(0)
            override fun getInputStream() = throw NotImplementedError()
            override fun getModificationStamp(): Long = modificationStamp
            override fun refresh(async: Boolean, recursive: Boolean, postRunnable: Runnable?) {}
            override fun getOutputStream(requestor: Any, newModificationStamp: Long, newTimeStamp: Long) =
                throw NotImplementedError()

            override fun delete(requestor: Any) {}
            override fun move(requestor: Any, newParent: VirtualFile) {}
            override fun rename(requestor: Any, newName: String) {}
            override fun createChildDirectory(requestor: Any, dirName: String): VirtualFile =
                throw NotImplementedError()

            override fun createChildData(requestor: Any, name: String): VirtualFile = throw NotImplementedError()
            override fun isWritable(): Boolean = true
            override fun isValid(): Boolean = true
            override fun getTimeStamp(): Long = 0L
            override fun getLength(): Long = 0L
        }
    }

    @Test
    fun `stats are empty before indexing`() {
        val stats = PSIAnalyzer.FileSummary(
            filePath = "test.kt",
            classes = emptyList(),
            methods = emptyList(),
            fields = emptyList(),
            totalSymbols = 0
        )
        assertEquals(0, stats.totalSymbols)
    }

    @Test
    fun `semantic search result structure`() {
        val result = SemanticSearch.SearchResult(
            filePath = "test.kt",
            symbol = null,
            matchType = SemanticSearch.MatchType.FUZZY_NAME,
            relevanceScore = 0.85
        )
        assertEquals("test.kt", result.filePath)
        assertEquals(0.85, result.relevanceScore, 0.01)
    }

    @Test
    fun `symbol type enum values`() {
        assertEquals(8, PSIAnalyzer.SymbolType.values().size)
        assertTrue(PSIAnalyzer.SymbolType.values().contains(PSIAnalyzer.SymbolType.CLASS))
        assertTrue(PSIAnalyzer.SymbolType.values().contains(PSIAnalyzer.SymbolType.METHOD))
    }

    @Test
    fun `code insight tools are registered`() {
        val tools = CodeInsightTools.getAllTools()
        assertEquals(6, tools.size)
        assertTrue(tools.any { it.name == "analyze_symbol" })
        assertTrue(tools.any { it.name == "semantic_search" })
        assertTrue(tools.any { it.name == "get_file_summary" })
    }

    @Test
    fun `incremental build skips unchanged files`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)

        var analyzeCallCount = 0
        symbolIndex.fileAnalyzer = { file ->
            analyzeCallCount++
            listOf(
                PSIAnalyzer.SymbolInfo(
                    name = file.name.removeSuffix(".kt"),
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = null,
                    filePath = file.path,
                    lineNumber = 1,
                    docComment = null,
                    modifiers = emptyList()
                )
            )
        }

        val fileA = createVirtualFile("/test/A.kt", 1L)
        val fileB = createVirtualFile("/test/B.kt", 2L)
        symbolIndex.testFileProvider = { setOf(fileA, fileB) }

        // 首次构建
        symbolIndex.buildIndexSync()
        assertEquals(2, analyzeCallCount, "首次构建应分析两个文件")

        // 再次构建，无任何变更
        symbolIndex.buildIndexSync()
        assertEquals(2, analyzeCallCount, "无变更时应跳过所有文件")

        // 修改 fileA 的 stamp
        val fileANew = createVirtualFile("/test/A.kt", 3L)
        symbolIndex.testFileProvider = { setOf(fileANew, fileB) }
        symbolIndex.buildIndexSync()
        assertEquals(3, analyzeCallCount, "仅变更 fileA，应只分析一次")

        // 验证删除文件会被清理
        symbolIndex.testFileProvider = { setOf(fileB) }
        symbolIndex.buildIndexSync()
        val hashes = symbolIndex.getIndexedFileHashesForTest()
        assertFalse(hashes.containsKey("/test/A.kt"), "已删除文件应从 hash 表中移除")
        assertTrue(hashes.containsKey("/test/B.kt"), "剩余文件应保留在 hash 表中")
    }

    @Test
    fun `findImplementations should be O1`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)

        // 预构建 10k 符号，其中每 100 个实现 MyInterface
        val symbols = (1..10_000).map { i ->
            PSIAnalyzer.SymbolInfo(
                name = "Class$i",
                type = PSIAnalyzer.SymbolType.CLASS,
                qualifiedName = "com.example.Class$i",
                filePath = "/test/Class$i.kt",
                lineNumber = i,
                docComment = null,
                modifiers = emptyList(),
                superTypes = if (i % 100 == 0) listOf("MyInterface") else emptyList()
            )
        }

        symbols.chunked(100).forEachIndexed { idx, chunk ->
            symbolIndex.updateFileSymbolsForTest("/test/batch$idx.kt", chunk)
        }

        // warm-up
        symbolIndex.findImplementations("MyInterface")

        val start = System.nanoTime()
        val results = symbolIndex.findImplementations("MyInterface")
        val durationMs = (System.nanoTime() - start) / 1_000_000.0

        assertEquals(100, results.size, "应有 100 个类实现 MyInterface")
        assertTrue(durationMs < 1.0, "findImplementations 应在 1ms 内完成，实际耗时 ${durationMs}ms")
    }

    @Test
    fun `concurrent updateFileSymbols and findByName should not throw or lose symbols`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)

        // 预填充初始数据
        symbolIndex.updateFileSymbolsForTest(
            "/test/Base.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    "Base",
                    PSIAnalyzer.SymbolType.CLASS,
                    null,
                    "/test/Base.kt",
                    1,
                    null,
                    emptyList()
                )
            )
        )

        val exceptions = mutableListOf<Throwable>()
        val threads = mutableListOf<Thread>()

        // 50 个写线程
        repeat(50) { i ->
            threads.add(Thread {
                try {
                    symbolIndex.updateFileSymbolsForTest(
                        "/test/File$i.kt", listOf(
                            PSIAnalyzer.SymbolInfo(
                                "Symbol$i",
                                PSIAnalyzer.SymbolType.METHOD,
                                null,
                                "/test/File$i.kt",
                                i,
                                null,
                                emptyList()
                            )
                        )
                    )
                } catch (e: Throwable) {
                    synchronized(exceptions) { exceptions.add(e) }
                }
            })
        }

        // 50 个读线程
        repeat(50) { i ->
            threads.add(Thread {
                try {
                    repeat(10) {
                        symbolIndex.findByName("Symbol$i")
                        symbolIndex.findByName("Base")
                        symbolIndex.fuzzySearch("Sym", limit = 10)
                        symbolIndex.findImplementations("SomeInterface")
                    }
                } catch (e: Throwable) {
                    synchronized(exceptions) { exceptions.add(e) }
                }
            })
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(exceptions.isEmpty(), "并发操作不应抛出异常，但收到: $exceptions")

        val baseSymbols = symbolIndex.findByName("Base")
        assertEquals(1, baseSymbols.size, "Base 符号不应丢失")
    }

    @Test
    fun `getStats returns correct indexVersion and cacheHitRate`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)

        val version0 = symbolIndex.getStats().indexVersion
        assertEquals(0L, version0)

        symbolIndex.updateFileSymbolsForTest(
            "/test/Foo.kt", listOf(
                PSIAnalyzer.SymbolInfo("Foo", PSIAnalyzer.SymbolType.CLASS, null, "/test/Foo.kt", 1, null, emptyList())
            )
        )

        val stats = symbolIndex.getStats()
        assertEquals(1, stats.totalSymbols)
        assertTrue(stats.indexVersion > 0)
        assertEquals(0.0, stats.cacheHitRate, 0.001)
    }

    // ========== getStats 等待行为 (修复 get_project_stats 全 0 回归) ==========

    /**
     * 模拟 SymbolIndex 启动后立即 getStats() (用户从未调过搜索/搜索类工具)。
     * 修复前: 全 0。修复后: getStats 主动 ensureIndexed + 限时等, 拿到真实数据。
     *
     * 用 buildIndex() 异步 + 1000ms waitMs 验证:
     *   - 在 waitMs 内能等到 indexingInProgress 翻为 false
     *   - 最终 stats 与 buildIndex 注入的符号一致
     */
    @Test
    fun `getStats triggers indexing and waits for completion when not yet built`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)

        val fileA = createVirtualFile("/test/A.kt", 1L)
        val fileB = createVirtualFile("/test/B.kt", 2L)
        symbolIndex.testFileProvider = { setOf(fileA, fileB) }
        // 用 fileAnalyzer 直接给符号, 不走 PSI 解析(测试只关心索引管线)
        symbolIndex.fileAnalyzer = { file ->
            listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "Sym_${file.name}",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = null,
                    filePath = file.path,
                    lineNumber = 1,
                    docComment = null,
                    modifiers = emptyList(),
                )
            )
        }

        // 还没 build 过, getStats 应触发并阻塞等到完成
        val stats = symbolIndex.getStats(waitMs = SymbolIndex.DEFAULT_STATS_WAIT_MS)

        assertEquals(2, stats.indexedFiles, "应等异步 buildIndex 完成, 索引 2 个文件")
        assertEquals(2, stats.classCount, "应有 2 个 CLASS 符号")
        assertEquals(2, stats.uniqueNames)
        assertEquals(2, stats.totalSymbols)
    }

    /**
     * waitMs=0 时不阻塞, 立即返回当前快照(可能仍是 0, 用于测试或实时敏感调用方)。
     */
    @Test
    fun `getStats with waitMs=0 does not block`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        val fileA = createVirtualFile("/test/A.kt", 1L)
        symbolIndex.testFileProvider = { setOf(fileA) }
        symbolIndex.fileAnalyzer = { file ->
            listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "Sym",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = null,
                    filePath = file.path,
                    lineNumber = 1,
                    docComment = null,
                    modifiers = emptyList(),
                )
            )
        }

        // waitMs=0: 立即返回, 不等异步 buildIndex
        val stats = symbolIndex.getStats(waitMs = 0)
        assertEquals(0, stats.indexedFiles, "waitMs=0 不阻塞, 拿到的是 buildIndex 启动前的快照")
    }

    /**
     * 第二次 getStats 时 buildIndex 已完成, 不应再触发新 build, 也不阻塞。
     */
    @Test
    fun `getStats after manual build is fast and reflects data`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        val fileA = createVirtualFile("/test/A.kt", 1L)
        symbolIndex.testFileProvider = { setOf(fileA) }
        symbolIndex.fileAnalyzer = { file ->
            listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "Sym",
                    type = PSIAnalyzer.SymbolType.METHOD,
                    qualifiedName = null,
                    filePath = file.path,
                    lineNumber = 1,
                    docComment = null,
                    modifiers = emptyList(),
                )
            )
        }

        symbolIndex.buildIndexSync()
        // 已经 build 过, indexingInProgress=false, getStats 不阻塞
        val t0 = System.currentTimeMillis()
        val stats = symbolIndex.getStats(waitMs = SymbolIndex.DEFAULT_STATS_WAIT_MS)
        val elapsed = System.currentTimeMillis() - t0
        assertTrue(elapsed < 100, "已 build 后 getStats 应是 O(1), 实测 ${elapsed}ms")
        assertEquals(1, stats.indexedFiles)
        assertEquals(1, stats.methodCount)
    }}
