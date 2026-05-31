package com.codesage.ide.ui.web

import com.codesage.shared.utils.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.charset.StandardCharsets

/**
 * 文件引用解析器
 *
 * 支持用户在聊天输入中使用 @文件路径 引用项目中的文件，
 * 自动将文件内容注入到对话上下文中。
 *
 * 功能：
 * - 根据输入前缀搜索项目文件（自动补全）
 * - 解析 @引用标记，读取文件内容
 * - 将引用文件内容格式化为上下文注入消息
 */
class FileReferenceResolver(private val project: Project?) {

    private val logger = Logger.getLogger<FileReferenceResolver>()

    /**
     * 文件引用信息
     */
    data class FileReference(
        val path: String,
        val name: String,
        val relativePath: String,
        val content: String? = null,
        val language: String = ""
    )

    /**
     * 自动补全建议
     */
    data class FileSuggestion(
        val name: String,
        val path: String,
        val relativePath: String,
        val icon: String = "📄"
    )

    /**
     * 搜索项目文件，用于 @ 自动补全
     *
     * @param query 用户输入的搜索前缀（不含 @）
     * @param limit 最大返回结果数
     * @return 文件建议列表
     */
    fun searchFiles(query: String, limit: Int = 20): List<FileSuggestion> {
        if (project == null) return emptyList()

        return ApplicationManager.getApplication().runReadAction(Computable {
            try {
                val suggestions = mutableListOf<FileSuggestion>()
                val basePath = project.guessProjectDir()?.path ?: return@Computable emptyList()

                val queryLower = query.lowercase()
                val scope = GlobalSearchScope.projectScope(project)

                // 1. 获取所有文件名并筛选
                val allFileNames = FilenameIndex.getAllFilenames(project)
                val matchedNames = allFileNames
                    .filter { it.lowercase().contains(queryLower) }
                    .sortedBy {
                        when {
                            it.lowercase() == queryLower -> 0
                            it.lowercase().startsWith(queryLower) -> 1
                            else -> 2
                        }
                    }
                    .take(limit * 2) // 多取一些，因为有些可能找不到 VirtualFile

                // 2. 对每个匹配的文件名，获取 VirtualFile 并过滤
                for (name in matchedNames) {
                    if (suggestions.size >= limit) break

                    val files = FilenameIndex.getVirtualFilesByName(name, scope)
                    for (file in files) {
                        if (suggestions.size >= limit) break
                        if (shouldIgnoreFile(file)) continue

                        val relPath = getRelativePath(file, basePath) ?: file.name
                        // 如果用户输入了路径的一部分，也匹配相对路径
                        if (query.isNotEmpty() && !relPath.lowercase().contains(queryLower) && !file.name.lowercase().contains(queryLower)) {
                            continue
                        }

                        val icon = getFileIcon(file.name)
                        suggestions.add(
                            FileSuggestion(
                                name = file.name,
                                path = file.path,
                                relativePath = relPath,
                                icon = icon
                            )
                        )
                    }
                }

                // 3. 如果结果太少，尝试遍历项目目录（fallback）
                if (suggestions.size < limit / 2 && query.isNotEmpty()) {
                    val projectDir = project.guessProjectDir()
                    if (projectDir != null) {
                        searchDirectory(projectDir, queryLower, basePath, suggestions, limit, 0)
                    }
                }

                suggestions.distinctBy { it.path }
            } catch (e: Exception) {
                logger.error("Failed to search files for query: $query", e)
                emptyList()
            }
        })
    }

    /**
     * 解析用户消息中的 @引用，读取文件内容
     *
     * @param message 用户输入的消息
     * @return 解析出的文件引用列表（包含内容）
     */
    fun resolveReferences(message: String): List<FileReference> {
        if (project == null) return emptyList()

        val references = mutableListOf<FileReference>()
        val basePath = project.guessProjectDir()?.path ?: return emptyList()

        // 匹配 @路径 模式（支持相对路径和文件名）
        val pattern = Regex("@([^\\s@]+)")
        val matches = pattern.findAll(message)

        matches.forEach { match ->
            val refPath = match.groupValues[1]
            val file = findFileByReference(refPath)

            if (file != null && !file.isDirectory) {
                try {
                    val content = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                    val relPath = getRelativePath(file, basePath) ?: file.name
                    val language = detectLanguage(file.name)

                    references.add(
                        FileReference(
                            path = file.path,
                            name = file.name,
                            relativePath = relPath,
                            content = content,
                            language = language
                        )
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to read referenced file: ${file.path}", e)
                }
            }
        }

        return references
    }

    /**
     * 将文件引用内容格式化为上下文注入文本
     */
    fun formatReferencesForContext(references: List<FileReference>): String {
        if (references.isEmpty()) return ""

        return buildString {
            appendLine()
            appendLine("---")
            appendLine("Referenced Files:")
            references.forEach { ref ->
                appendLine()
                appendLine("### ${ref.relativePath}")
                appendLine("```${ref.language}")
                // 限制文件内容长度，避免超出上下文窗口
                val truncated = truncateContent(ref.content ?: "", maxLines = 200)
                appendLine(truncated)
                appendLine("```")
            }
            appendLine("---")
        }
    }

    /**
     * 从消息中移除 @引用标记，返回纯消息文本
     */
    fun stripReferences(message: String): String {
        return message.replace(Regex("@([^\\s@]+)")) { match ->
            val refPath = match.groupValues[1]
            val file = findFileByReference(refPath)
            if (file != null) "" else match.value
        }.trim()
    }

    /**
     * 检查消息是否包含文件引用
     */
    fun hasFileReferences(message: String): Boolean {
        if (project == null) return false
        val pattern = Regex("@([^\\s@]+)")
        return pattern.findAll(message).any { match ->
            findFileByReference(match.groupValues[1]) != null
        }
    }

    // ===== Private Helpers =====

    private fun findFileByReference(refPath: String): VirtualFile? {
        if (project == null) return null

        return ApplicationManager.getApplication().runReadAction(Computable {
            // 1. 尝试作为绝对路径查找
            var file = LocalFileSystem.getInstance().findFileByPath(refPath)
            if (file != null) return@Computable file

            // 2. 尝试作为项目相对路径查找
            val basePath = project.guessProjectDir()?.path
            if (basePath != null) {
                file = LocalFileSystem.getInstance().findFileByPath("$basePath/$refPath")
                if (file != null) return@Computable file
            }

            // 3. 尝试通过文件名查找（在项目范围内）
            val scope = GlobalSearchScope.projectScope(project)
            val files = FilenameIndex.getVirtualFilesByName(refPath, scope)
            if (files.isNotEmpty()) {
                return@Computable files.first()
            }

            // 4. 尝试部分匹配文件名（通过所有文件名）
            val allFileNames = FilenameIndex.getAllFilenames(project)
            val exactMatch = allFileNames.find { it.equals(refPath, ignoreCase = true) }
            if (exactMatch != null) {
                val matchedFiles = FilenameIndex.getVirtualFilesByName(exactMatch, scope)
                if (matchedFiles.isNotEmpty()) {
                    return@Computable matchedFiles.first()
                }
            }

            // 5. 尝试不带扩展名匹配
            val withoutExtMatch = allFileNames.find {
                it.substringBeforeLast('.', it).equals(refPath, ignoreCase = true)
            }
            if (withoutExtMatch != null) {
                val matchedFiles = FilenameIndex.getVirtualFilesByName(withoutExtMatch, scope)
                if (matchedFiles.isNotEmpty()) {
                    return@Computable matchedFiles.first()
                }
            }

            null
        })
    }

    /**
     * 递归搜索目录中的文件（fallback 方法）
     */
    private fun searchDirectory(
        dir: VirtualFile,
        queryLower: String,
        basePath: String,
        suggestions: MutableList<FileSuggestion>,
        limit: Int,
        depth: Int
    ) {
        if (depth > 5 || suggestions.size >= limit) return
        if (shouldIgnoreDirectory(dir)) return

        dir.children?.forEach { child ->
            if (suggestions.size >= limit) return

            if (child.isDirectory) {
                searchDirectory(child, queryLower, basePath, suggestions, limit, depth + 1)
            } else {
                if (!shouldIgnoreFile(child)) {
                    val name = child.name.lowercase()
                    val relPath = (getRelativePath(child, basePath) ?: child.name).lowercase()
                    if (name.contains(queryLower) || relPath.contains(queryLower)) {
                        suggestions.add(
                            FileSuggestion(
                                name = child.name,
                                path = child.path,
                                relativePath = getRelativePath(child, basePath) ?: child.name,
                                icon = getFileIcon(child.name)
                            )
                        )
                    }
                }
            }
        }
    }

    private fun shouldIgnoreDirectory(dir: VirtualFile): Boolean {
        val ignoredDirs = setOf(
            ".git", ".svn", ".hg", "node_modules", "build", "dist", "out",
            ".gradle", "target", "__pycache__", ".idea", ".vscode",
            "bin", "obj", ".next", ".nuxt"
        )
        return dir.name in ignoredDirs || dir.name.startsWith(".")
    }

    private fun getRelativePath(file: VirtualFile, basePath: String): String? {
        return try {
            VfsUtil.getRelativePath(file, LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    private fun shouldIgnoreFile(file: VirtualFile): Boolean {
        val ignoredDirs = setOf(
            ".git", ".svn", ".hg", "node_modules", "build", "dist", "out",
            ".gradle", "target", "__pycache__", ".idea", ".vscode",
            "bin", "obj", ".next", ".nuxt"
        )
        val ignoredExts = setOf(
            "class", "jar", "war", "ear", "zip", "tar", "gz", "rar",
            "7z", "exe", "dll", "so", "dylib", "o", "a", "lib",
            "png", "jpg", "jpeg", "gif", "bmp", "svg", "ico", "webp",
            "mp3", "mp4", "avi", "mov", "wmv", "flv", "wav",
            "ttf", "otf", "woff", "woff2", "eot",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "lock", "log", "tmp", "temp", "swp"
        )

        // 检查是否在忽略目录中
        var parent = file.parent
        while (parent != null) {
            if (parent.name in ignoredDirs) return true
            parent = parent.parent
        }

        // 检查扩展名
        val ext = file.extension?.lowercase() ?: ""
        if (ext in ignoredExts) return true

        // 检查文件大小（跳过超过 1MB 的文件）
        if (file.length > 1024 * 1024) return true

        return false
    }

    private fun getFileIcon(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "🟣"
            "java" -> "☕"
            "py" -> "🐍"
            "js", "jsx" -> "🟨"
            "ts", "tsx" -> "🔷"
            "go" -> "🐹"
            "rs" -> "🦀"
            "cpp", "c", "h", "hpp" -> "⚙️"
            "swift" -> "🐦"
            "rb" -> "💎"
            "php" -> "🐘"
            "html", "htm" -> "🌐"
            "css", "scss", "sass", "less" -> "🎨"
            "json" -> "📋"
            "xml", "yaml", "yml" -> "📄"
            "md", "markdown" -> "📝"
            "sql" -> "🗄️"
            "sh", "bash", "zsh" -> "🐚"
            "gradle" -> "🐘"
            "dockerfile" -> "🐳"
            else -> "📄"
        }
    }

    private fun detectLanguage(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js" -> "javascript"
            "jsx" -> "jsx"
            "ts" -> "typescript"
            "tsx" -> "tsx"
            "go" -> "go"
            "rs" -> "rust"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "h", "hpp" -> "cpp"
            "swift" -> "swift"
            "rb" -> "ruby"
            "php" -> "php"
            "html", "htm" -> "html"
            "css" -> "css"
            "scss" -> "scss"
            "sass" -> "sass"
            "json" -> "json"
            "xml" -> "xml"
            "yaml", "yml" -> "yaml"
            "md", "markdown" -> "markdown"
            "sql" -> "sql"
            "sh", "bash" -> "bash"
            "gradle" -> "groovy"
            "dockerfile" -> "dockerfile"
            else -> ""
        }
    }

    private fun truncateContent(content: String, maxLines: Int): String {
        val lines = content.lines()
        return if (lines.size > maxLines) {
            lines.take(maxLines).joinToString("\n") + "\n... (${lines.size - maxLines} more lines)"
        } else {
            content
        }
    }
}
