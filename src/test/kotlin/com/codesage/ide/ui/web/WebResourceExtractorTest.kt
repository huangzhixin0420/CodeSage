package com.codesage.ide.ui.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File

/**
 * WebResourceExtractor 集成测试
 *
 * 验证:
 *   - 能从 classpath 提取 webui/ 到 temp 目录
 *   - index.html 真的出现在目标目录
 *   - 主要子目录(styles / js / lib)都存在
 *   - 至少能列出 N 个文件
 *
 * 在 classpath 没有 webui 资源的环境(例如纯单元测试 sandbox)下会跳过。
 */
@EnabledIf("isWebuiOnClasspath")
class WebResourceExtractorTest {

    companion object {
        @JvmStatic
        fun isWebuiOnClasspath(): Boolean {
            return try {
                WebResourceExtractor::class.java.classLoader.getResource("webui") != null
            } catch (e: Exception) {
                false
            }
        }
    }

    @Test
    fun `extract produces a directory with index html and subfolders`() {
        val dir = WebResourceExtractor.extract()
        assertNotNull(dir, "extract should return a directory")
        assertTrue(dir.exists() && dir.isDirectory, "target should be a real directory")
        val index = File(dir, "index.html")
        assertTrue(index.exists(), "index.html should exist in extracted dir: $dir")
        assertTrue(index.length() > 100, "index.html should not be empty")

        // Sanity check: 主要子目录
        listOf("styles", "js", "lib").forEach { sub ->
            val subFile = File(dir, sub)
            assertTrue(subFile.exists() && subFile.isDirectory, "missing $sub")
        }

        // 至少有一些 CSS 和 JS
        val cssCount = File(dir, "styles").listFiles()?.count { it.extension == "css" } ?: 0
        val jsCount = File(dir, "js").walkTopDown().count { it.extension == "js" }
        assertTrue(cssCount >= 5, "expected at least 5 CSS files, got $cssCount")
        assertTrue(jsCount >= 10, "expected at least 10 JS files, got $jsCount")
    }

    @Test
    fun `extract reuses cache when reuseCacheDir points to existing extract`() {
        val first = WebResourceExtractor.extract()
        val second = WebResourceExtractor.extract(reuseCacheDir = first)
        assertEquals(first.absolutePath, second.absolutePath, "second call should reuse cache dir")
    }
}
