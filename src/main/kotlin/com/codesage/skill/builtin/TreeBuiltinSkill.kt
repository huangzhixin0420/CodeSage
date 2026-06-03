package com.codesage.skill.builtin

import com.codesage.skill.*
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内置技能 - 目录树（纯 Kotlin 实现，不依赖外部 tree 命令）
 *
 * 历史背景：之前 YAML 里 `declarative_tree` 硬编码 `command: "tree"`，但
 * `tree` 命令在 macOS 默认没装（要 `brew install tree`），导致 Plugin
 * 在 macOS 跑不动。修法：写个纯 Kotlin 的实现，跨平台工作，不依赖任何
 * 外部命令。
 *
 * 输出格式模仿 `tree` 命令风格：
 * ```
 * src/
 * ├── main/
 * │   └── kotlin/
 * └── test/
 * ```
 *
 * 跳过常见噪音目录（.git / build / node_modules 等），不递归过深。
 */
class TreeBuiltinSkill : Skill {

    override val id = "builtin_tree"
    override val name = "Directory Tree"
    override val description = "以树状结构列出目录内容（纯 Kotlin 实现，跨平台，无需 tree 命令）"
    override val version = "1.0.0"
    override val category = SkillCategory.FILE_OPERATION
    override val tags = setOf("file", "tree", "directory", "builtin")

    override val inputSchema = mapOf(
        "path" to mapOf("type" to "string", "description" to "目录路径", "required" to true),
        "depth" to mapOf("type" to "integer", "description" to "最大深度，默认 3")
    )
    override val outputSchema = mapOf(
        "output" to mapOf("type" to "string", "description" to "树状结构文本"),
        "fileCount" to mapOf("type" to "integer", "description" to "文件总数"),
        "dirCount" to mapOf("type" to "integer", "description" to "目录总数")
    )

    private val logger = Logger.getLogger<TreeBuiltinSkill>()

    /** 噪音目录：直接跳过。常见 build / VCS 缓存，避免树过大。 */
    private val NOISE_DIRS = setOf(
        ".git", ".idea", ".gradle", "build", "out", "target",
        "node_modules", "__pycache__", ".DS_Store", "venv", ".venv",
        "dist", "DerivedData", ".claude", ".vscode"
    )

    /** 噪音文件：常见临时文件。 */
    private val NOISE_FILES = setOf(
        ".DS_Store", "Thumbs.db"
    )

    override fun canExecute(context: ExecutionContext): CanExecuteResult = CanExecuteResult(true)

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return withContext(Dispatchers.IO) {
            try {
                val path = input.getString("path")
                    ?: return@withContext SkillResult.Failure("Missing required parameter: path")
                val depth = (input.getInt("depth") ?: 3).coerceIn(1, 10)

                val projectPath = context.projectPath
                    ?: return@withContext SkillResult.Failure("No project context available")

                val root = resolveSafePath(path, projectPath)
                    ?: return@withContext SkillResult.Failure("Access denied: path must be within project directory")

                if (!root.exists()) {
                    return@withContext SkillResult.Failure("Path does not exist: $root")
                }
                if (!root.isDirectory) {
                    return@withContext SkillResult.Failure("Path is not a directory: $root")
                }

                val stats = intArrayOf(0, 0)  // [fileCount, dirCount]
                val sb = StringBuilder()
                renderTree(root, depth, 0, true, sb, stats)

                SkillResult.Success(
                    mapOf(
                        "output" to sb.toString(),
                        "fileCount" to stats[0],
                        "dirCount" to stats[1]
                    )
                )
            } catch (e: Exception) {
                logger.error("TreeBuiltinSkill failed: ${e.message}", e)
                SkillResult.Failure("Tree listing failed: ${e.message}", e)
            }
        }
    }

    /**
     * 递归渲染目录树。
     *
     * @param dir 当前目录
     * @param maxDepth 最大深度
     * @param currentDepth 当前深度（0 = 根）
     * @param isLast 在父目录中是不是最后一个
     * @param sb 渲染目标
     * @param stats [fileCount, dirCount]
     */
    private fun renderTree(
        dir: File,
        maxDepth: Int,
        currentDepth: Int,
        isLast: Boolean,
        sb: StringBuilder,
        stats: IntArray
    ) {
        // 渲染当前节点
        val prefix = buildPrefix(currentDepth, isLast)
        sb.appendLine("$prefix${dir.name}/")
        stats[1]++

        if (currentDepth >= maxDepth) return

        // 收集子项：跳过噪音目录/文件；目录优先，名称排序
        val children = dir.listFiles()
            ?.filter { child ->
                if (child.isDirectory) child.name !in NOISE_DIRS
                else child.name !in NOISE_FILES
            }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return

        for ((index, child) in children.withIndex()) {
            val last = index == children.size - 1
            if (child.isDirectory) {
                renderTree(child, maxDepth, currentDepth + 1, last, sb, stats)
            } else {
                val filePrefix = buildPrefix(currentDepth + 1, last)
                sb.appendLine("$filePrefix${child.name}")
                stats[0]++
            }
        }
    }

    /**
     * 生成缩进+连接符前缀。例如：
     *   currentDepth=0, isLast=true  → ""
     *   currentDepth=1, isLast=false → "├── "
     *   currentDepth=1, isLast=true  → "└── "
     *   currentDepth=2, isLast=false → "│   ├── "
     *   currentDepth=2, isLast=true  → "│   └── "
     */
    private fun buildPrefix(currentDepth: Int, isLast: Boolean): String {
        if (currentDepth == 0) return ""
        val sb = StringBuilder()
        // 父级缩进（depth-1 个 │   ）
        for (i in 1 until currentDepth) {
            sb.append("│   ")
        }
        // 当前节点连接符
        sb.append(if (isLast) "└── " else "├── ")
        return sb.toString()
    }
}
