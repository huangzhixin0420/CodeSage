package com.codesage.analysis

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.lang.reflect.Proxy

class SemanticSearchTest {

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

    @Test
    fun `cached search returns same result on second call`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)

        symbolIndex.updateFileSymbolsForTest(
            "/test/UserService.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "UserService",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = "com.example.UserService",
                    filePath = "/test/UserService.kt",
                    lineNumber = 1,
                    docComment = "Service for user operations",
                    modifiers = emptyList()
                ),
                PSIAnalyzer.SymbolInfo(
                    name = "getUserById",
                    type = PSIAnalyzer.SymbolType.METHOD,
                    qualifiedName = null,
                    filePath = "/test/UserService.kt",
                    lineNumber = 5,
                    docComment = "Get user by id",
                    modifiers = emptyList(),
                    parameters = listOf(PSIAnalyzer.ParameterInfo("id", "Long"))
                )
            )
        )

        val semanticSearch = SemanticSearch(project, symbolIndex)

        // 第一次调用
        val result1 = semanticSearch.search("UserService", limit = 10)
        assertTrue(result1.isNotEmpty())

        // 第二次调用，应从缓存返回
        val result2 = semanticSearch.search("UserService", limit = 10)
        assertEquals(result1, result2)

        val (hits, misses) = semanticSearch.getCacheStats()
        assertTrue(hits >= 1, "应有至少一次缓存命中")
        assertTrue(misses >= 1, "应有至少一次缓存未命中")
    }

    @Test
    fun `cache invalidates when symbolIndex version changes`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)

        symbolIndex.updateFileSymbolsForTest(
            "/test/OrderService.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "OrderService",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = "com.example.OrderService",
                    filePath = "/test/OrderService.kt",
                    lineNumber = 1,
                    docComment = null,
                    modifiers = emptyList()
                )
            )
        )

        val semanticSearch = SemanticSearch(project, symbolIndex)

        // 第一次调用，写入缓存
        val result1 = semanticSearch.search("OrderService", limit = 10)
        assertTrue(result1.isNotEmpty())

        // 修改 symbolIndex version（模拟索引更新）
        symbolIndex.version.incrementAndGet()

        // 再次查询，缓存应失效（因为 key 中包含了 version）
        val result2 = semanticSearch.search("OrderService", limit = 10)
        assertTrue(result2.isNotEmpty())

        // 验证缓存 miss 增加了
        val (_, misses) = semanticSearch.getCacheStats()
        assertTrue(misses >= 2, "版本变更后应重新计算，缓存未命中次数应增加")
    }

    @Test
    fun `semanticQuery uses cache`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)

        symbolIndex.updateFileSymbolsForTest(
            "/test/Calc.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "Calculator",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = null,
                    filePath = "/test/Calc.kt",
                    lineNumber = 1,
                    docComment = "Utility for mathematical calculations",
                    modifiers = emptyList()
                )
            )
        )

        val semanticSearch = SemanticSearch(project, symbolIndex)

        val r1 = semanticSearch.semanticQuery("math calculation utility", limit = 5)
        val r2 = semanticSearch.semanticQuery("math calculation utility", limit = 5)
        assertEquals(r1, r2)

        val (hits, _) = semanticSearch.getCacheStats()
        assertTrue(hits >= 1, "semanticQuery 应命中缓存")
    }

    @Test
    fun `findRelatedClasses uses cache`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)

        symbolIndex.updateFileSymbolsForTest(
            "/test/Shape.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "Shape",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = null,
                    filePath = "/test/Shape.kt",
                    lineNumber = 1,
                    docComment = null,
                    modifiers = emptyList()
                ),
                PSIAnalyzer.SymbolInfo(
                    name = "Circle",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = null,
                    filePath = "/test/Shape.kt",
                    lineNumber = 5,
                    docComment = null,
                    modifiers = emptyList(),
                    superTypes = listOf("Shape")
                )
            )
        )

        val semanticSearch = SemanticSearch(project, symbolIndex)

        val r1 = semanticSearch.findRelatedClasses("Shape")
        val r2 = semanticSearch.findRelatedClasses("Shape")
        assertEquals(r1, r2)

        val (hits, _) = semanticSearch.getCacheStats()
        assertTrue(hits >= 1, "findRelatedClasses 应命中缓存")
    }
}
