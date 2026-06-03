package com.codesage.skill.builtin

import com.codesage.skill.*
import com.codesage.shared.utils.Logger
import com.codesage.skill.registry.SkillRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 内置技能 - 文件读取
 */
class FileReaderSkill : Skill {
    override val id = "builtin_file_reader"
    override val name = "File Reader"
    override val description = "读取文件内容"
    override val version = "1.0.0"
    override val category = SkillCategory.FILE_OPERATION
    override val tags = setOf("file", "read", "io")
    override val inputSchema = mapOf(
        "path" to mapOf("type" to "string", "description" to "文件路径"),
        "encoding" to mapOf("type" to "string", "description" to "编码，默认UTF-8")
    )
    override val outputSchema = mapOf(
        "content" to mapOf("type" to "string", "description" to "文件内容")
    )

    private val logger = Logger.getLogger<FileReaderSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        return CanExecuteResult(true)
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return withContext(Dispatchers.IO) {
            try {
                val path = input.getString("path")
                    ?: return@withContext SkillResult.Failure("Missing path parameter")

                val projectPath = context.projectPath
                    ?: return@withContext SkillResult.Failure("No project context available for path validation")

                val encoding = input.getString("encoding") ?: "UTF-8"
                val file = resolveSafePath(path, projectPath)
                    ?: return@withContext SkillResult.Failure("Access denied: path must be within project directory")

                if (!file.exists()) {
                    return@withContext SkillResult.Failure("File not found: $path")
                }

                if (!file.isFile) {
                    return@withContext SkillResult.Failure("Path is not a file: $path")
                }

                // Size limit: refuse to read files > 10MB
                if (file.length() > 10 * 1024 * 1024) {
                    return@withContext SkillResult.Failure("File too large: ${file.length()} bytes (max 10MB)")
                }

                val content = file.readText(charset(encoding))

                SkillResult.Success(mapOf("content" to content, "path" to path))
            } catch (e: Exception) {
                logger.error("File read failed", e)
                SkillResult.Failure("Failed to read file: ${e.message}", e)
            }
        }
    }
}

/**
 * 内置技能 - 文件写入
 */
class FileWriterSkill : Skill {
    override val id = "builtin_file_writer"
    override val name = "File Writer"
    override val description = "写入内容到文件"
    override val version = "1.0.0"
    override val category = SkillCategory.FILE_OPERATION
    override val tags = setOf("file", "write", "io")
    override val inputSchema = mapOf(
        "path" to mapOf("type" to "string", "description" to "文件路径"),
        "content" to mapOf("type" to "string", "description" to "要写入的内容"),
        "encoding" to mapOf("type" to "string", "description" to "编码，默认UTF-8"),
        "append" to mapOf("type" to "boolean", "description" to "是否追加模式")
    )
    override val outputSchema = mapOf(
        "success" to mapOf("type" to "boolean"),
        "bytesWritten" to mapOf("type" to "integer")
    )

    private val logger = Logger.getLogger<FileWriterSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        return CanExecuteResult(true)
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return withContext(Dispatchers.IO) {
            try {
                val path = input.getString("path")
                    ?: return@withContext SkillResult.Failure("Missing path parameter")
                val content = input.getString("content")
                    ?: return@withContext SkillResult.Failure("Missing content parameter")

                val projectPath = context.projectPath
                    ?: return@withContext SkillResult.Failure("No project context available for path validation")

                val encoding = input.getString("encoding") ?: "UTF-8"
                val append = input.getBoolean("append") ?: false

                val file = resolveSafePath(path, projectPath)
                    ?: return@withContext SkillResult.Failure("Access denied: path must be within project directory")

                // Prevent writing to sensitive paths
                val relativePath = file.relativeToOrSelf(File(projectPath)).path
                if (isSensitivePath(relativePath)) {
                    return@withContext SkillResult.Failure("Access denied: cannot write to sensitive path: $relativePath")
                }

                // Ensure parent directory exists and is within project
                val parentDir = file.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    if (!isPathWithinProject(parentDir, projectPath)) {
                        return@withContext SkillResult.Failure("Access denied: parent directory outside project")
                    }
                    parentDir.mkdirs()
                }

                val bytesWritten = if (append) {
                    file.appendText(content, charset(encoding))
                    content.toByteArray(charset(encoding)).size
                } else {
                    file.writeText(content, charset(encoding))
                    content.toByteArray(charset(encoding)).size
                }

                SkillResult.Success(
                    mapOf(
                        "success" to true,
                        "path" to path,
                        "bytesWritten" to bytesWritten
                    )
                )
            } catch (e: Exception) {
                logger.error("File write failed", e)
                SkillResult.Failure("Failed to write file: ${e.message}", e)
            }
        }
    }
}

/**
 * 内置技能 - 文件搜索
 */
class FileSearchSkill : Skill {
    override val id = "builtin_file_search"
    override val name = "File Search"
    override val description = "搜索文件和目录"
    override val version = "1.0.0"
    override val category = SkillCategory.FILE_OPERATION
    override val tags = setOf("file", "search", "glob")
    override val inputSchema = mapOf(
        "pattern" to mapOf("type" to "string", "description" to "文件匹配模式"),
        "rootPath" to mapOf("type" to "string", "description" to "搜索根目录"),
        "recursive" to mapOf("type" to "boolean", "description" to "是否递归搜索")
    )
    override val outputSchema = mapOf(
        "files" to mapOf("type" to "array", "description" to "匹配的文件列表")
    )

    private val logger = Logger.getLogger<FileSearchSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        return CanExecuteResult(true)
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return withContext(Dispatchers.IO) {
            try {
                val pattern = input.getString("pattern")
                    ?: return@withContext SkillResult.Failure("Missing pattern parameter")
                val rootPath = input.getString("rootPath") ?: context.projectPath ?: "."
                val recursive = input.getBoolean("recursive") ?: true

                val root = Paths.get(rootPath)
                if (!Files.exists(root)) {
                    return@withContext SkillResult.Failure("Root path does not exist: $rootPath")
                }

                val matcher = root.fileSystem.getPathMatcher("glob:$pattern")
                val results = mutableListOf<String>()

                if (recursive) {
                    Files.walk(root)
                        .filter { Files.isRegularFile(it) }
                        .filter { matcher.matches(it.fileName) }
                        .forEach { results.add(it.toAbsolutePath().toString()) }
                } else {
                    Files.list(root)
                        .filter { Files.isRegularFile(it) }
                        .filter { matcher.matches(it.fileName) }
                        .forEach { results.add(it.toAbsolutePath().toString()) }
                }

                SkillResult.Success(mapOf("files" to results, "count" to results.size))
            } catch (e: Exception) {
                logger.error("File search failed", e)
                SkillResult.Failure("Failed to search files: ${e.message}", e)
            }
        }
    }
}

/**
 * 内置技能 - 项目分析
 */
class ProjectAnalysisSkill : Skill {
    override val id = "builtin_project_analysis"
    override val name = "Project Analysis"
    override val description = "分析项目结构和依赖"
    override val version = "1.0.0"
    override val category = SkillCategory.CODE_SEARCH
    override val tags = setOf("project", "analysis", "structure")
    override val inputSchema = mapOf(
        "projectPath" to mapOf("type" to "string", "description" to "项目路径"),
        "includeDependencies" to mapOf("type" to "boolean", "description" to "是否包含依赖分析")
    )
    override val outputSchema = mapOf(
        "structure" to mapOf("type" to "object"),
        "fileCount" to mapOf("type" to "integer"),
        "languageStats" to mapOf("type" to "object")
    )

    private val logger = Logger.getLogger<ProjectAnalysisSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        val path = context.projectPath
        return if (path != null && File(path).exists()) {
            CanExecuteResult(true)
        } else {
            CanExecuteResult(false, "Project path not available")
        }
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return withContext(Dispatchers.IO) {
            try {
                val projectPath = input.getString("projectPath") ?: context.projectPath
                ?: return@withContext SkillResult.Failure("Project path not specified")

                val projectDir = File(projectPath)
                if (!projectDir.exists()) {
                    return@withContext SkillResult.Failure("Project does not exist: $projectPath")
                }

                val structure = analyzeDirectory(projectDir)
                val languageStats = analyzeLanguages(projectDir)

                SkillResult.Success(
                    mapOf(
                        "structure" to structure,
                        "fileCount" to countFiles(projectDir),
                        "languageStats" to languageStats
                    )
                )
            } catch (e: Exception) {
                logger.error("Project analysis failed", e)
                SkillResult.Failure("Failed to analyze project: ${e.message}", e)
            }
        }
    }

    private fun analyzeDirectory(dir: File, depth: Int = 0): Map<String, Any> {
        if (depth > 3) return mapOf("truncated" to true)

        val children = mutableListOf<Map<String, Any>>()
        dir.listFiles()?.filter { !it.name.startsWith(".") }?.take(50)?.forEach { file ->
            if (file.isDirectory) {
                children.add(
                    mapOf(
                        "name" to file.name,
                        "type" to "directory",
                        "children" to analyzeDirectory(file, depth + 1)
                    )
                )
            } else {
                children.add(
                    mapOf(
                        "name" to file.name,
                        "type" to "file"
                    )
                )
            }
        }

        return mapOf("entries" to children)
    }

    private fun analyzeLanguages(dir: File): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()

        Files.walk(dir.toPath())
            .filter { Files.isRegularFile(it) }
            .filter { !it.toString().contains("/.") }
            .forEach { path ->
                val ext = path.toString().substringAfterLast(".", "")
                stats[ext] = (stats[ext] ?: 0) + 1
            }

        return stats
    }

    private fun countFiles(dir: File): Int {
        return Files.walk(dir.toPath())
            .filter { Files.isRegularFile(it) }
            .filter { !it.toString().contains("/.") }
            .count().toInt()
    }
}

/**
 * 内置技能工厂
 */
/**
 * 安全路径工具函数
 */
internal fun resolveSafePath(path: String, projectPath: String): File? {
    val base = File(projectPath).canonicalFile
    val target = File(path).let {
        if (it.isAbsolute) it else File(base, path)
    }.canonicalFile

    return if (isPathWithinProject(target, base.path)) target else null
}

internal fun isPathWithinProject(target: File, projectPath: String): Boolean {
    val base = File(projectPath).canonicalFile
    val canonical = target.canonicalFile
    return canonical.startsWith(base)
}

internal fun isSensitivePath(relativePath: String): Boolean {
    val lower = relativePath.lowercase().replace("\\", "/")
    val sensitivePatterns = listOf(
        ".env", ".env.", ".ssh/", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        ".p12", ".pfx", ".key", "keystore", "credentials", "secret", "password",
        "token", ".aws/", ".kube/", ".docker/", "/etc/", "/usr/", "/bin/", "/sbin/",
        "/sys/", "/proc/", "/dev/", "../", "..\\"
    )
    return sensitivePatterns.any { lower.contains(it) }
}

object BuiltInSkills {
    private val logger = Logger.getLogger<BuiltInSkills>()
    private val skills = listOf(
        FileReaderSkill(),
        FileWriterSkill(),
        FileSearchSkill(),
        ProjectAnalysisSkill(),
        GitOperationSkill(),
        CommandExecutionSkill(),
        WebRequestSkill(),
        CodeSearchSkill(),
        TreeBuiltinSkill()  // 纯 Kotlin 目录树，不依赖外部 tree 命令（macOS 默认没装）
    )

    private val aiSkills = listOf(
        CodeExplanationSkill(),
        RefactoringSuggestionSkill(),
        TestGenerationSkill(),
        CodeReviewSkill(),
        DependencyAnalysisSkill()
    )

    fun getAll(): List<Skill> = skills + aiSkills

    fun registerAll(registry: com.codesage.skill.registry.SkillRegistry) {
        getAll().forEach { registry.register(it) }
        logger.info("Registered ${getAll().size} built-in skills")
    }
}
