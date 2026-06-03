package com.codesage.ide.ui.web

import com.codesage.shared.utils.Logger
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Enumeration
import java.util.jar.JarEntry
import java.util.jar.JarFile

/**
 * 将 `webui/` 资源从 classpath 提取到本地磁盘目录,供 JCEF 通过 file 协议加载。
 *
 * 为什么需要这个:
 *   - JCEF 的 `loadHTML(content, baseUrl)` 不会把 `baseUrl` 真的注册为服务;
 *     浏览器请求 `http://codesage.local/styles/tokens.css` 时 404
 *   - 新的 `index.html` 用相对路径(`styles/tokens.css` 与 `js/main.js`),
 *     不像旧 `chat.html` 全是 CDN URL 能被 ResourceInliner 转 data URI
 *   - ES module(`<script type="module" src="js/main.js">`)无法 inline,
 *     必须真的能 fetch
 *   - 提取到本地后,用 `loadURL(file-url)`,所有相对路径自然解析
 *
 * 提取策略:
 *   - 开发态(IDE 直接跑):classloader 给的是 file:// URL → 直接 copy 目录
 *   - 打包后(jar 协议,CodeSage.jar):从 JAR 里枚举 `webui/` 条目
 *   - 提取到 `~/.codesage/webui-cache/<timestamp>/`
 *   - Plugin unload 时清理太早会导致 refresh 失败,先保留,后续清理
 */
object WebResourceExtractor {
    private val logger = Logger.getLogger<WebResourceExtractor>()

    /**
     * 提取 `webui/` 整个子树到本地目录,返回目标目录的 File
     * @param reuseCacheDir 如果非 null 且已存在 `index.html`,直接复用
     */
    fun extract(reuseCacheDir: File? = null): File {
        // 确保父目录存在
        val cacheRoot = Paths.get(System.getProperty("user.home"), ".codesage", "webui-cache").toFile()
        if (!cacheRoot.exists()) cacheRoot.mkdirs()
        val targetDir = reuseCacheDir?.takeIf { File(it, "index.html").exists() }
            ?: Files.createTempDirectory(cacheRoot.toPath(), "run-").toFile()
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val indexFile = File(targetDir, "index.html")
        if (indexFile.exists()) {
            logger.info("[WebResourceExtractor] reusing cache: $targetDir")
            return targetDir
        }
        val classLoader = WebResourceExtractor::class.java.classLoader
        val webuiUrl = classLoader.getResource("webui")
        if (webuiUrl == null) {
            logger.error("[WebResourceExtractor] cannot find 'webui' in classpath")
            throw IllegalStateException("webui/ not found in classpath")
        }
        logger.info("[WebResourceExtractor] source: $webuiUrl -> target: $targetDir")
        try {
            when (webuiUrl.protocol) {
                "file" -> {
                    val sourceDir = File(webuiUrl.toURI())
                    copyDirectoryRecursive(sourceDir, targetDir)
                }

                "jar" -> {
                    extractFromJar(webuiUrl, targetDir)
                }

                else -> {
                    logger.error("[WebResourceExtractor] unsupported protocol: ${webuiUrl.protocol}")
                    throw IllegalStateException("Unsupported protocol: ${webuiUrl.protocol}")
                }
            }
        } catch (e: Exception) {
            logger.error("[WebResourceExtractor] extraction failed", e)
            throw e
        }
        logger.info("[WebResourceExtractor] done: $targetDir (${countFiles(targetDir)} files)")
        return targetDir
    }

    private fun copyDirectoryRecursive(source: File, target: File) {
        if (!source.exists()) return
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child ->
                copyDirectoryRecursive(child, File(target, child.name))
            }
        } else {
            target.parentFile?.mkdirs()
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun extractFromJar(webuiUrl: java.net.URL, targetDir: File) {
// jar:file:/path/to.jar!/webui
        val path = webuiUrl.path
        val bangIdx = path.indexOf("!/")
        require(bangIdx >= 0) { "Invalid jar URL: $webuiUrl" }
        val jarPath = path.substring("file:".length, bangIdx)
        val prefixInJar = path.substring(bangIdx + 2) // "webui"
        val jar = JarFile(URI.create("file://$jarPath").let { java.io.File(it).absolutePath })
        jar.use { j ->
            val entries: Enumeration<JarEntry> = j.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.startsWith("$prefixInJar/")) continue
                val rel = name.substring(prefixInJar.length + 1)
                if (rel.isEmpty()) continue
                val outFile = File(targetDir, rel)
                outFile.parentFile?.mkdirs()
                j.getInputStream(entry).use { input ->
                    Files.copy(input, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun countFiles(dir: File): Int {
        var count = 0
        dir.walkTopDown().forEach { if (it.isFile) count++ }
        return count
    }
}
