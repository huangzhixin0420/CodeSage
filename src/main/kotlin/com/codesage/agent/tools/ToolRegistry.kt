package com.codesage.agent.tools

import com.codesage.agent.tools.handlers.*
import com.codesage.analysis.CodeInsightTools
import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import com.codesage.shared.utils.Logger

/**
 * 工具注册中心
 * 管理所有可向 AI 暴露的工具定义及其执行处理器
 *
 * 支持两种注册方式：
 * 1. 传统方式：register(tool: Tool) —— 仅注册定义，执行仍走 ToolExecutor 硬编码路由（逐步废弃）
 * 2. 推荐方式：register(handler: ToolHandler) —— 将定义与执行逻辑绑定，实现动态扩展
 */
class ToolRegistry {
    private val logger = Logger.getLogger<ToolRegistry>()
    private val tools = mutableMapOf<String, Tool>()
    private val handlers = mutableMapOf<String, ToolHandler>()

    // region 传统注册（向后兼容）

    fun register(tool: Tool) {
        tools[tool.name] = tool
        logger.info("Registered tool (legacy): ${tool.name}")
    }

    // endregion

    // region Handler 注册（推荐）

    fun register(handler: ToolHandler) {
        handlers[handler.name] = handler
        tools[handler.name] = handler.tool
        logger.info("Registered tool handler: ${handler.name}")
    }

    fun unregister(toolName: String) {
        tools.remove(toolName)
        handlers.remove(toolName)
    }

    fun getHandler(toolName: String): ToolHandler? = handlers[toolName]

    fun hasHandler(toolName: String): Boolean = handlers.containsKey(toolName)

    // endregion

    fun get(toolName: String): Tool? = tools[toolName]

    fun getAllTools(): List<Tool> = tools.values.toList()

    /**
     * T6.4 修复：按类别查找工具
     */
    fun findByCategory(category: com.codesage.model.dto.ToolCategory): List<Tool> =
        tools.values.filter { it.category == category }

    /**
     * T6.4 修复：按名称/描述/标签模糊搜索工具
     */
    fun search(query: String): List<Tool> {
        val lowerQuery = query.lowercase()
        return tools.values.filter { tool ->
            tool.name.lowercase().contains(lowerQuery) ||
                    tool.description.lowercase().contains(lowerQuery) ||
                    tool.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    fun clear() {
        tools.clear()
        handlers.clear()
    }

    companion object {
        /**
         * 创建默认的工具注册表，包含所有 IDE 内置工具
         */
        fun createDefault(project: com.intellij.openapi.project.Project? = null): ToolRegistry {
            return ToolRegistry().apply {
                // === IDE 文件操作工具（通过 Handler 注册） ===
                val ideTools = IDETools(project)
                register(IDEFileHandlers.createReadFileHandler(ideTools))
                register(IDEFileHandlers.createWriteFileHandler(ideTools))
                register(IDEFileHandlers.createListDirectoryHandler(ideTools))
                register(IDEFileHandlers.createFindFileHandler(ideTools))
                register(IDEFileHandlers.createGrepCodeHandler(ideTools))
                register(IDEFileHandlers.createGetFileInfoHandler(ideTools))
                register(IDEFileHandlers.createReadMultipleFilesHandler(ideTools))
                register(IDEFileHandlers.createEditFileHandler(ideTools))
                register(IDEFileHandlers.createDeleteFileHandler(ideTools))
                register(IDEFileHandlers.createCopyFileHandler(ideTools))
                register(IDEFileHandlers.createMoveFileHandler(ideTools))
                register(IDEFileHandlers.createSearchCodeHandler(ideTools))
                register(IDEFileHandlers.createRunCommandHandler(ideTools))
                register(IDEFileHandlers.createGetProjectStructureHandler(ideTools))

                // === 扩展工具（Git / Shell / HTTP / 数据处理） ===
                val extendedTools = ExtendedTools(project)
                register(ExtendedToolHandlers.createGitStatusHandler(extendedTools))
                register(ExtendedToolHandlers.createGitDiffHandler(extendedTools))
                register(ExtendedToolHandlers.createGitLogHandler(extendedTools))
                register(ExtendedToolHandlers.createGitBranchHandler(extendedTools))
                register(ExtendedToolHandlers.createExecShellHandler(extendedTools))
                register(ExtendedToolHandlers.createHttpRequestHandler(extendedTools))
                register(ExtendedToolHandlers.createParseJsonHandler(extendedTools))
                register(ExtendedToolHandlers.createEncodeBase64Handler(extendedTools))
                register(ExtendedToolHandlers.createDecodeBase64Handler(extendedTools))
                register(ExtendedToolHandlers.createFormatJsonHandler(extendedTools))
                register(ExtendedToolHandlers.createHashMd5Handler(extendedTools))
                register(ExtendedToolHandlers.createHashSha256Handler(extendedTools))

                // === 增强版 Git 工具 ===
                register(ExtendedToolHandlers.createGitAddHandler(extendedTools))
                register(ExtendedToolHandlers.createGitCommitHandler(extendedTools))
                register(ExtendedToolHandlers.createGitStashHandler(extendedTools))
                register(ExtendedToolHandlers.createGitBlameHandler(extendedTools))

                // === 增强版文件操作工具 ===
                register(IDEFileHandlers.createCreateDirectoryHandler(ideTools))
                register(IDEFileHandlers.createZipDirectoryHandler(ideTools))
                register(IDEFileHandlers.createUnzipArchiveHandler(ideTools))

                // === 代码洞察工具（传统方式，通过 CodeInsightExecutor 执行） ===
                CodeInsightTools.getAllTools().forEach { register(it) }

                // === 新工具生态 ===
                register(BuildToolHandlers.createMavenHandler())
                register(BuildToolHandlers.createGradleHandler())
                register(TestToolHandlers.createRunTestsHandler(project))
                register(RegexToolHandlers.createRegexTestHandler())
                register(RegexToolHandlers.createRegexExtractHandler())
                register(DiffToolHandlers.createDiffFilesHandler())
                register(DocumentationToolHandlers.createGenerateDocHandler(project))
                register(DatabaseToolHandlers.createSqlExecuteHandler(project))
                register(DockerToolHandlers.createDockerHandler())
                register(DependencyToolHandlers.createAnalyzeDependenciesHandler(project))
                register(WebScraperToolHandlers.createWebScraperHandler())
                register(ClipboardToolHandlers.createClipboardHandler())
                register(TimestampToolHandlers.createTimestampHandler())
                register(UUIDToolHandlers.createUUIDHandler())

                // === T6.1: Code Insight 工具改用 UnifiedTool 类（消除 ToolExecutor 中的 when 硬编码）===
                val codeInsightExecutor = com.codesage.analysis.CodeInsightExecutor(project)
                register(AnalyzeSymbolTool(codeInsightExecutor))
                register(FindUsagesTool(codeInsightExecutor))
                register(GetInheritanceChainTool(codeInsightExecutor))
                register(SemanticSearchTool(codeInsightExecutor))
                register(GetFileSummaryTool(codeInsightExecutor))
                register(GetProjectStatsTool(codeInsightExecutor))

                // === T6.5: 高价值工具 ===
                register(CreatePullRequestTool())
                register(RunLinterTool())
                register(StartDebuggerTool(project))
                register(DatabaseSchemaTool())
                register(GitWorktreeTool())
                register(SymbolSearchTool(project))

                // === 子 Agent 委托工具（特殊：分发由 EnhancedAgentLoop.executeTool 处理） ===
                // 单独注册其 schema 让 LLM 看到可用工具。
                // 注意：执行不通过 ToolHandler，而是由 EnhancedAgentLoop 中
                // `toolCall.name == "delegate_task"` 的分支路由到 SubAgentExecutor。
                register(delegateTaskTool())

                logger.info("ToolRegistry initialized with ${getAllTools().size} tools")
            }
        }
    }
}

// === 工具定义工厂函数（保留，供 Legacy 和 Handler 共用） ===

internal fun readFileTool() = Tool(
    name = "read_file",
    description = "读取指定文件的内容。支持相对项目根目录的路径或绝对路径。可以指定偏移量和读取行数限制。",
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty(
                type = "string",
                description = "文件路径（相对项目根目录或绝对路径）"
            ),
            "offset" to ToolProperty(
                type = "integer",
                description = "起始行号（从0开始），可选"
            ),
            "limit" to ToolProperty(
                type = "integer",
                description = "最大读取行数，可选，默认读取整个文件"
            )
        ),
        required = listOf("path")
    )
)

internal fun writeFileTool() = Tool(
    name = "write_file",
    description = "将内容写入指定文件。如果文件不存在会自动创建（包括父目录）。支持追加或覆盖模式。",
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty(
                type = "string",
                description = "文件路径（相对项目根目录或绝对路径）"
            ),
            "content" to ToolProperty(
                type = "string",
                description = "要写入的文件内容"
            ),
            "append" to ToolProperty(
                type = "boolean",
                description = "是否追加到文件末尾，默认为 false（覆盖）"
            )
        ),
        required = listOf("path", "content")
    )
)

internal fun listDirectoryTool() = Tool(
    name = "list_directory",
    description = "列出指定目录下的文件和子目录。返回文件名、类型（file/directory）和相对路径。",
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty(
                type = "string",
                description = "目录路径（相对项目根目录或绝对路径），默认为项目根目录"
            ),
            "recursive" to ToolProperty(
                type = "boolean",
                description = "是否递归列出子目录内容，默认为 false"
            )
        ),
        required = listOf()
    )
)

internal fun searchCodeTool() = Tool(
    name = "search_code",
    description = "在项目中搜索代码。支持正则表达式或普通文本搜索，可按文件类型过滤。",
    parameters = ToolParameters(
        properties = mapOf(
            "query" to ToolProperty(
                type = "string",
                description = "搜索关键词或正则表达式"
            ),
            "file_pattern" to ToolProperty(
                type = "string",
                description = "文件匹配模式，如 *.kt、*.java，可选"
            ),
            "path" to ToolProperty(
                type = "string",
                description = "搜索根目录，默认为项目根目录"
            )
        ),
        required = listOf("query")
    )
)

internal fun runCommandTool() = Tool(
    name = "run_command",
    description = "在指定工作目录下执行系统命令（如 shell 命令）。返回 stdout、stderr 和退出码。",
    parameters = ToolParameters(
        properties = mapOf(
            "command" to ToolProperty(
                type = "string",
                description = "要执行的命令"
            ),
            "working_dir" to ToolProperty(
                type = "string",
                description = "工作目录（相对项目根目录或绝对路径），默认为项目根目录"
            ),
            "timeout" to ToolProperty(
                type = "integer",
                description = "超时时间（毫秒），默认 30000"
            )
        ),
        required = listOf("command")
    )
)

internal fun getProjectStructureTool() = Tool(
    name = "get_project_structure",
    description = "获取当前项目的整体结构概览，包括模块、源代码目录、关键配置文件等。",
    parameters = ToolParameters(
        properties = mapOf(
            "depth" to ToolProperty(
                type = "integer",
                description = "目录递归深度，默认 2"
            )
        ),
        required = listOf()
    )
)

internal fun delegateTaskTool() = Tool(
    name = "delegate_task",
    description = "Spawn an isolated sub-agent to handle a specific workstream in parallel. " +
            "Use when a task can be decomposed into independent sub-tasks.",
    parameters = ToolParameters(
        properties = mapOf(
            "task_description" to ToolProperty(
                type = "string",
                description = "Detailed description of what the sub-agent should do"
            ),
            "toolset" to ToolProperty(
                type = "string",
                description = "Which toolset to give the sub-agent (dev, research, test, browser)",
                enum = listOf("dev", "research", "test", "browser")
            ),
            "max_iterations" to ToolProperty(
                type = "integer",
                description = "Budget for the sub-agent, default 10"
            ),
            "context_files" to ToolProperty(
                type = "array",
                description = "Files the sub-agent needs access to"
            )
        ),
        required = listOf("task_description")
    )
)

internal fun findFileTool() = Tool(
    name = "find_file",
    description = "Find files by name pattern (supports glob/regex). Returns matching file paths.",
    parameters = ToolParameters(
        properties = mapOf(
            "pattern" to ToolProperty("string", "File name pattern to search for (e.g. '*.kt', 'build.gradle')"),
            "path" to ToolProperty("string", "Search root directory, defaults to project root"),
            "max_results" to ToolProperty("integer", "Maximum number of results to return, default 50")
        ),
        required = listOf("pattern")
    )
)

internal fun grepCodeTool() = Tool(
    name = "grep_code",
    description = "Search for text patterns in file contents with line context (like grep). Returns matches with surrounding lines.",
    parameters = ToolParameters(
        properties = mapOf(
            "query" to ToolProperty("string", "Search text or regex pattern"),
            "path" to ToolProperty("string", "Search root directory, defaults to project root"),
            "file_pattern" to ToolProperty("string", "File name filter pattern, e.g. '*.kt'"),
            "context_lines" to ToolProperty("integer", "Number of context lines around each match, default 2")
        ),
        required = listOf("query")
    )
)

internal fun getFileInfoTool() = Tool(
    name = "get_file_info",
    description = "Get metadata about a file: size, type, extension, modification time.",
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty("string", "File path")
        ),
        required = listOf("path")
    )
)

internal fun readMultipleFilesTool() = Tool(
    name = "read_multiple_files",
    description = "Read contents of multiple files at once. More efficient than multiple read_file calls.",
    parameters = ToolParameters(
        properties = mapOf(
            "paths" to ToolProperty("array", "List of file paths to read")
        ),
        required = listOf("paths")
    )
)

internal fun editFileTool() = Tool(
    name = "edit_file",
    description = "Precisely edit a file by replacing old_string with new_string, or by replacing lines in a range. Use this for small edits instead of rewriting entire files.",
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty("string", "File path"),
            "old_string" to ToolProperty("string", "Text to replace (optional if using line range)"),
            "new_string" to ToolProperty("string", "Replacement text"),
            "start_line" to ToolProperty("integer", "Start line number for range replacement (1-based)"),
            "end_line" to ToolProperty("integer", "End line number for range replacement (1-based)")
        ),
        required = listOf("path", "new_string")
    )
)

internal fun deleteFileTool() = Tool(
    name = "delete_file",
    description = "Delete a file or directory. Use with caution.",
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty("string", "File or directory path to delete")
        ),
        required = listOf("path")
    )
)

internal fun copyFileTool() = Tool(
    name = "copy_file",
    description = "Copy a file from source to destination.",
    parameters = ToolParameters(
        properties = mapOf(
            "source" to ToolProperty("string", "Source file path"),
            "destination" to ToolProperty("string", "Destination file path")
        ),
        required = listOf("source", "destination")
    )
)

internal fun moveFileTool() = Tool(
    name = "move_file",
    description = "Move/rename a file from source to destination.",
    parameters = ToolParameters(
        properties = mapOf(
            "source" to ToolProperty("string", "Source file path"),
            "destination" to ToolProperty("string", "Destination file path")
        ),
        required = listOf("source", "destination")
    )
)

// === Git Tools ===

internal fun gitStatusTool() = Tool(
    name = "git_status",
    description = "查看 Git 仓库状态，返回当前分支和变更文件列表。",
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录")
        ),
        required = listOf()
    )
)

internal fun gitDiffTool() = Tool(
    name = "git_diff",
    description = "查看 Git 文件差异。支持查看暂存区差异或指定文件的差异。",
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录"),
            "cached" to ToolProperty("boolean", "是否查看暂存区差异 (--cached)，默认为 false"),
            "file" to ToolProperty("string", "指定查看差异的文件路径，可选")
        ),
        required = listOf()
    )
)

internal fun gitLogTool() = Tool(
    name = "git_log",
    description = "查看 Git 提交历史（单行格式）。",
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录"),
            "limit" to ToolProperty("integer", "返回的最大提交数，默认 20，最大 100")
        ),
        required = listOf()
    )
)

internal fun gitBranchTool() = Tool(
    name = "git_branch",
    description = "查看 Git 分支列表，识别当前分支。",
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录")
        ),
        required = listOf()
    )
)

// === Shell Tool ===

internal fun execShellTool() = Tool(
    name = "exec_shell",
    description = "执行 Shell 命令，返回 stdout、stderr 和退出码。支持超时控制和安全限制（自动阻止危险命令）。",
    parameters = ToolParameters(
        properties = mapOf(
            "command" to ToolProperty("string", "要执行的 Shell 命令"),
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录"),
            "timeout" to ToolProperty("integer", "超时时间（毫秒），默认 60000，最大 300000")
        ),
        required = listOf("command")
    )
)

// === HTTP Tool ===

internal fun httpRequestTool() = Tool(
    name = "http_request",
    description = "发送 HTTP 请求，支持 GET/POST/PUT/DELETE/PATCH。自动格式化 JSON 响应。",
    parameters = ToolParameters(
        properties = mapOf(
            "url" to ToolProperty("string", "请求 URL"),
            "method" to ToolProperty(
                "string",
                "HTTP 方法",
                enum = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
            ),
            "headers" to ToolProperty("object", "请求头键值对对象，可选"),
            "body" to ToolProperty("string", "请求体字符串，可选"),
            "timeout" to ToolProperty("integer", "超时时间（毫秒），默认 30000")
        ),
        required = listOf("url")
    )
)

// === Data Processing Tools ===

internal fun parseJsonTool() = Tool(
    name = "parse_json",
    description = "解析 JSON 字符串，支持 xpath-style 点号路径查询（如 user.address.city）。",
    parameters = ToolParameters(
        properties = mapOf(
            "json" to ToolProperty("string", "要解析的 JSON 字符串"),
            "query" to ToolProperty("string", "点号分隔的路径查询，可选")
        ),
        required = listOf("json")
    )
)

internal fun encodeBase64Tool() = Tool(
    name = "encode_base64",
    description = "将字符串编码为 Base64。",
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要编码的字符串")
        ),
        required = listOf("input")
    )
)

internal fun decodeBase64Tool() = Tool(
    name = "decode_base64",
    description = "将 Base64 字符串解码为普通字符串。",
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要解码的 Base64 字符串")
        ),
        required = listOf("input")
    )
)

internal fun formatJsonTool() = Tool(
    name = "format_json",
    description = "JSON 美化或压缩格式化。",
    parameters = ToolParameters(
        properties = mapOf(
            "json" to ToolProperty("string", "要格式化的 JSON 字符串"),
            "compact" to ToolProperty("boolean", "是否压缩输出（单行），默认为 false（美化）")
        ),
        required = listOf("json")
    )
)

internal fun hashMd5Tool() = Tool(
    name = "hash_md5",
    description = "计算字符串的 MD5 哈希值。",
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要计算哈希的字符串")
        ),
        required = listOf("input")
    )
)

internal fun hashSha256Tool() = Tool(
    name = "hash_sha256",
    description = "计算字符串的 SHA-256 哈希值。",
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要计算哈希的字符串")
        ),
        required = listOf("input")
    )
)

// === 新增增强工具定义 ===

// Git 增强
internal fun gitAddTool() = Tool(
    name = "git_add",
    description = "将文件添加到 Git 暂存区。支持添加单个文件、多个文件或全部变更。",
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录"),
            "files" to ToolProperty("array", "要添加的文件路径列表，空数组或省略表示添加所有变更"),
            "all" to ToolProperty("boolean", "是否添加所有变更（git add -A），默认为 false")
        ),
        required = listOf()
    )
)

internal fun gitCommitTool() = Tool(
    name = "git_commit",
    description = "创建 Git 提交。如果 message 为空则执行 git commit --amend。",
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录"),
            "message" to ToolProperty("string", "提交信息，为空则 amend"),
            "amend" to ToolProperty("boolean", "是否修改上一次提交，默认为 false"),
            "no_verify" to ToolProperty("boolean", "是否跳过钩子（--no-verify），默认为 false")
        ),
        required = listOf()
    )
)

internal fun gitStashTool() = Tool(
    name = "git_stash",
    description = "Git stash 操作：保存、弹出、查看或清空暂存。",
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录"),
            "action" to ToolProperty(
                "string",
                "操作类型: save/pop/list/clear/drop",
                enum = listOf("save", "pop", "list", "clear", "drop")
            ),
            "message" to ToolProperty("string", "stash 消息（仅 save 时有效）")
        ),
        required = listOf("action")
    )
)

internal fun gitBlameTool() = Tool(
    name = "git_blame",
    description = "查看指定文件每行的最后修改者和提交信息。",
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录"),
            "file" to ToolProperty("string", "要查看的文件路径"),
            "line_start" to ToolProperty("integer", "起始行号（可选）"),
            "line_end" to ToolProperty("integer", "结束行号（可选）")
        ),
        required = listOf("file")
    )
)

// 文件操作增强
internal fun createDirectoryTool() = Tool(
    name = "create_directory",
    description = "创建目录（包括不存在的父目录）。",
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty("string", "要创建的目录路径")
        ),
        required = listOf("path")
    )
)

internal fun zipDirectoryTool() = Tool(
    name = "zip_directory",
    description = "将目录压缩为 zip 文件。",
    parameters = ToolParameters(
        properties = mapOf(
            "source" to ToolProperty("string", "要压缩的目录路径"),
            "destination" to ToolProperty("string", "输出的 zip 文件路径")
        ),
        required = listOf("source", "destination")
    )
)

internal fun unzipArchiveTool() = Tool(
    name = "unzip_archive",
    description = "解压 zip 文件到指定目录。",
    parameters = ToolParameters(
        properties = mapOf(
            "source" to ToolProperty("string", "zip 文件路径"),
            "destination" to ToolProperty("string", "解压目标目录路径")
        ),
        required = listOf("source", "destination")
    )
)

// 构建工具
internal fun mavenTool() = Tool(
    name = "maven",
    description = "执行 Maven 命令（mvn）。自动检测项目中的 pom.xml。",
    parameters = ToolParameters(
        properties = mapOf(
            "goals" to ToolProperty("string", "Maven 目标，如 'clean install'、'test'、'dependency:tree'"),
            "working_dir" to ToolProperty("string", "工作目录，默认为项目根目录"),
            "profiles" to ToolProperty("string", "激活的 profile，多个用逗号分隔"),
            "properties" to ToolProperty("object", "额外的系统属性键值对")
        ),
        required = listOf("goals")
    )
)

internal fun gradleTool() = Tool(
    name = "gradle",
    description = "执行 Gradle 命令（./gradlew 或 gradle）。自动检测项目中的 build.gradle/build.gradle.kts。",
    parameters = ToolParameters(
        properties = mapOf(
            "tasks" to ToolProperty("string", "Gradle 任务，如 'build'、'test'、'dependencies'"),
            "working_dir" to ToolProperty("string", "工作目录，默认为项目根目录"),
            "args" to ToolProperty("string", "额外参数，如 '--info'、'--refresh-dependencies'")
        ),
        required = listOf("tasks")
    )
)

// 测试工具
internal fun runTestsTool() = Tool(
    name = "run_tests",
    description = "运行项目中的测试。支持 JUnit、TestNG。可指定类、方法或包。",
    parameters = ToolParameters(
        properties = mapOf(
            "test_class" to ToolProperty("string", "测试类全限定名（可选）"),
            "test_method" to ToolProperty("string", "测试方法名（可选，需同时指定 test_class）"),
            "package_path" to ToolProperty("string", "测试包路径（可选）"),
            "working_dir" to ToolProperty("string", "工作目录，默认为项目根目录")
        ),
        required = listOf()
    )
)

// 正则工具
internal fun regexTestTool() = Tool(
    name = "regex_test",
    description = "测试正则表达式是否匹配给定文本。返回匹配结果和捕获组。",
    parameters = ToolParameters(
        properties = mapOf(
            "pattern" to ToolProperty("string", "正则表达式模式"),
            "text" to ToolProperty("string", "要测试的文本"),
            "flags" to ToolProperty("string", "标志，如 'i'（忽略大小写）、'm'（多行），可选")
        ),
        required = listOf("pattern", "text")
    )
)

internal fun regexExtractTool() = Tool(
    name = "regex_extract",
    description = "使用正则表达式从文本中提取所有匹配项。支持命名捕获组。",
    parameters = ToolParameters(
        properties = mapOf(
            "pattern" to ToolProperty("string", "正则表达式模式"),
            "text" to ToolProperty("string", "要提取的文本"),
            "flags" to ToolProperty("string", "标志，如 'i'、'm'，可选")
        ),
        required = listOf("pattern", "text")
    )
)

// Diff 工具
internal fun diffFilesTool() = Tool(
    name = "diff_files",
    description = "比较两个文件或两段文本的差异，返回统一 diff 格式结果。",
    parameters = ToolParameters(
        properties = mapOf(
            "source" to ToolProperty("string", "原始文件路径或文本"),
            "target" to ToolProperty("string", "目标文件路径或文本"),
            "is_paths" to ToolProperty("boolean", "source/target 是否为文件路径，默认为 true"),
            "context_lines" to ToolProperty("integer", "上下文行数，默认 3")
        ),
        required = listOf("source", "target")
    )
)

// 文档工具
internal fun generateDocTool() = Tool(
    name = "generate_doc",
    description = "为代码生成文档注释（Javadoc/KDoc/JSDoc）。需要提供文件路径和行号范围。",
    parameters = ToolParameters(
        properties = mapOf(
            "file_path" to ToolProperty("string", "文件路径"),
            "line_start" to ToolProperty("integer", "文档开始行号（1-based）"),
            "line_end" to ToolProperty("integer", "文档结束行号（1-based）"),
            "style" to ToolProperty("string", "文档风格: javadoc/kdoc/jsdoc", enum = listOf("javadoc", "kdoc", "jsdoc"))
        ),
        required = listOf("file_path", "line_start", "line_end")
    )
)

// 数据库工具
internal fun sqlExecuteTool() = Tool(
    name = "sql_execute",
    description = "执行 SQL 查询或命令。需要项目已配置数据库数据源（IntelliJ Database Tools）。",
    parameters = ToolParameters(
        properties = mapOf(
            "data_source_name" to ToolProperty("string", "数据源名称"),
            "sql" to ToolProperty("string", "SQL 语句"),
            "limit" to ToolProperty("integer", "返回最大行数，默认 100")
        ),
        required = listOf("data_source_name", "sql")
    )
)

// Docker 工具
internal fun dockerTool() = Tool(
    name = "docker",
    description = "执行 Docker 命令：查看容器、镜像、日志，或运行容器。",
    parameters = ToolParameters(
        properties = mapOf(
            "command" to ToolProperty(
                "string",
                "Docker 子命令: ps/images/logs/run/exec/stop/rmi",
                enum = listOf("ps", "images", "logs", "run", "exec", "stop", "rmi", "inspect")
            ),
            "target" to ToolProperty("string", "容器/镜像名称或 ID（部分命令需要）"),
            "options" to ToolProperty("string", "额外选项，如 '-a'、'--follow'")
        ),
        required = listOf("command")
    )
)

// 依赖分析工具
internal fun analyzeDependenciesTool() = Tool(
    name = "analyze_dependencies",
    description = "分析项目依赖：查找过时依赖、检测冲突、生成依赖树。",
    parameters = ToolParameters(
        properties = mapOf(
            "action" to ToolProperty(
                "string",
                "操作: tree/outdated/conflicts",
                enum = listOf("tree", "outdated", "conflicts")
            ),
            "working_dir" to ToolProperty("string", "工作目录，默认为项目根目录")
        ),
        required = listOf("action")
    )
)

// 网页抓取工具
internal fun webScraperTool() = Tool(
    name = "web_scraper",
    description = "抓取网页内容并提取纯文本。支持指定 CSS 选择器提取特定元素。",
    parameters = ToolParameters(
        properties = mapOf(
            "url" to ToolProperty("string", "目标网页 URL"),
            "selector" to ToolProperty("string", "CSS 选择器（可选，如 'article'、'.content'）"),
            "timeout" to ToolProperty("integer", "超时时间（毫秒），默认 15000")
        ),
        required = listOf("url")
    )
)

// 剪贴板工具
internal fun clipboardTool() = Tool(
    name = "clipboard",
    description = "操作系统剪贴板：获取当前内容或设置新内容。",
    parameters = ToolParameters(
        properties = mapOf(
            "action" to ToolProperty("string", "操作: get/set", enum = listOf("get", "set")),
            "content" to ToolProperty("string", "要设置的内容（set 时必填）")
        ),
        required = listOf("action")
    )
)

// 时间戳工具
internal fun timestampTool() = Tool(
    name = "timestamp",
    description = "时间戳与日期互转。支持多种格式。",
    parameters = ToolParameters(
        properties = mapOf(
            "value" to ToolProperty("string", "时间戳（毫秒）或日期字符串"),
            "input_format" to ToolProperty(
                "string",
                "输入格式: timestamp/iso/rfc",
                enum = listOf("timestamp", "iso", "rfc")
            ),
            "output_format" to ToolProperty(
                "string",
                "输出格式: timestamp/iso/rfc/relative",
                enum = listOf("timestamp", "iso", "rfc", "relative")
            )
        ),
        required = listOf("value", "input_format", "output_format")
    )
)

// UUID 工具
internal fun uuidTool() = Tool(
    name = "uuid",
    description = "生成 UUID（v4 随机）或验证 UUID 格式。",
    parameters = ToolParameters(
        properties = mapOf(
            "action" to ToolProperty("string", "操作: generate/validate", enum = listOf("generate", "validate")),
            "uuid" to ToolProperty("string", "要验证的 UUID（validate 时必填）")
        ),
        required = listOf("action")
    )
)
