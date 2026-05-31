package com.codesage.ide.ui.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * FileReferenceResolver 单元测试
 *
 * 测试文件引用解析的核心逻辑（不依赖 IntelliJ Platform API 的部分）
 */
class FileReferenceResolverTest {

    @Test
    fun `stripReferences should remove valid file references from message`() {
        // 注意：此测试仅验证 stripReferences 的文本处理逻辑
        // 实际文件查找需要 IntelliJ Project 上下文

        val resolver = FileReferenceResolver(null)

        // 测试无引用的情况
        val plainMessage = "Hello, how are you?"
        assertEquals("Hello, how are you?", resolver.stripReferences(plainMessage))

        // 测试包含 @ 但无有效文件的情况（project 为 null，所以引用都无效）
        val messageWithRef = "Check @build.gradle.kts for dependencies"
        // 当 project 为 null 时，findFileByReference 返回 null，所以引用不会被移除
        assertEquals("Check @build.gradle.kts for dependencies", resolver.stripReferences(messageWithRef))
    }

    @Test
    fun `hasFileReferences should return false when project is null`() {
        val resolver = FileReferenceResolver(null)

        assertFalse(resolver.hasFileReferences("Check @build.gradle.kts"))
        assertFalse(resolver.hasFileReferences("Hello world"))
    }

    @Test
    fun `formatReferencesForContext should format file references correctly`() {
        val resolver = FileReferenceResolver(null)

        val references = listOf(
            FileReferenceResolver.FileReference(
                path = "/project/src/Main.kt",
                name = "Main.kt",
                relativePath = "src/Main.kt",
                content = "fun main() {\n    println(\"Hello\")\n}",
                language = "kotlin"
            ),
            FileReferenceResolver.FileReference(
                path = "/project/README.md",
                name = "README.md",
                relativePath = "README.md",
                content = "# Project",
                language = "markdown"
            )
        )

        val formatted = resolver.formatReferencesForContext(references)

        assertTrue(formatted.contains("Referenced Files:"))
        assertTrue(formatted.contains("### src/Main.kt"))
        assertTrue(formatted.contains("### README.md"))
        assertTrue(formatted.contains("```kotlin"))
        assertTrue(formatted.contains("```markdown"))
        assertTrue(formatted.contains("fun main()"))
        assertTrue(formatted.contains("# Project"))
    }

    @Test
    fun `formatReferencesForContext should return empty string for empty list`() {
        val resolver = FileReferenceResolver(null)
        val result = resolver.formatReferencesForContext(emptyList())
        assertEquals("", result)
    }

    @Test
    fun `formatReferencesForContext should truncate large files`() {
        val resolver = FileReferenceResolver(null)

        // 创建超过 200 行的内容
        val largeContent = (1..250).joinToString("\n") { "Line $it" }

        val references = listOf(
            FileReferenceResolver.FileReference(
                path = "/project/src/Large.kt",
                name = "Large.kt",
                relativePath = "src/Large.kt",
                content = largeContent,
                language = "kotlin"
            )
        )

        val formatted = resolver.formatReferencesForContext(references)

        assertTrue(formatted.contains("(50 more lines)"))
        assertFalse(formatted.contains("Line 250")) // 被截断的内容不应出现
    }

    @Test
    fun `searchFiles should return empty list when project is null`() {
        val resolver = FileReferenceResolver(null)
        val results = resolver.searchFiles("test")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `resolveReferences should return empty list when project is null`() {
        val resolver = FileReferenceResolver(null)
        val results = resolver.resolveReferences("Check @build.gradle.kts")
        assertTrue(results.isEmpty())
    }
}
