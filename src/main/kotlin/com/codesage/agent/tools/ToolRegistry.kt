package com.codesage.agent.tools

import com.codesage.agent.tools.handlers.*
import com.codesage.analysis.CodeInsightTools
import com.codesage.mcp.server.MCPServerManager
import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import com.codesage.shared.security.CommandSandbox
import com.codesage.shared.utils.Logger
import com.codesage.skill.executor.SkillExecutor
import com.codesage.skill.registry.SkillRegistry
import java.io.File

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
        fun createDefault(
            project: com.intellij.openapi.project.Project? = null,
            auditLog: com.codesage.tools.guardrails.ToolAuditLog? = null,
            mcpServerManager: MCPServerManager? = null,
            skillRegistry: SkillRegistry? = null,
            skillExecutor: SkillExecutor? = null,
        ): ToolRegistry {
            return ToolRegistry().apply {
                // Phase 3: 为命令执行工具注入 OS 级沙箱（默认开启 workspace 写权限）
                val projectRoot = project?.basePath?.let { File(it) }
                val commandSandbox = CommandSandbox.create(
                    projectRoot,
                    CommandSandbox.Mode.WORKSPACE_WRITE
                )

                // === IDE 文件操作工具（通过 Handler 注册） ===
                val ideTools = IDETools(project, auditLog, commandSandbox)
                val readFileHandler = IDEFileHandlers.createReadFileHandler(ideTools)
                val runCommandHandler = IDEFileHandlers.createRunCommandHandler(ideTools)

                register(readFileHandler)
                register(ReadDocumentTool(ideTools))
                register(IDEFileHandlers.createWriteFileHandler(ideTools))
                register(IDEFileHandlers.createListDirectoryHandler(ideTools))
                register(IDEFileHandlers.createFindFileHandler(ideTools))
                register(IDEFileHandlers.createGlobHandler(ideTools))
                register(IDEFileHandlers.createGrepCodeHandler(ideTools))
                register(IDEFileHandlers.createGetFileInfoHandler(ideTools))
                register(IDEFileHandlers.createReadMultipleFilesHandler(ideTools))
                register(IDEFileHandlers.createEditFileHandler(ideTools))
                register(ApplyPatchTool(ideTools))
                register(MultiEditTool(ideTools))
                register(IDEFileHandlers.createDeleteFileHandler(ideTools))
                register(IDEFileHandlers.createCopyFileHandler(ideTools))
                register(IDEFileHandlers.createMoveFileHandler(ideTools))
                register(IDEFileHandlers.createSearchCodeHandler(ideTools))
                register(runCommandHandler)
                register(IDEFileHandlers.createGetProjectStructureHandler(ideTools))
                register(KillProcessTool())
                register(ReadProcessOutputTool())

                // === 扩展工具（Git / Shell / HTTP / 数据处理） ===
                val extendedTools = ExtendedTools(project, commandSandbox)
                register(ExtendedToolHandlers.createGitStatusHandler(extendedTools))
                register(ExtendedToolHandlers.createGitDiffHandler(extendedTools))
                register(ExtendedToolHandlers.createGitLogHandler(extendedTools))
                register(ExtendedToolHandlers.createGitBranchHandler(extendedTools))
                register(ExtendedToolHandlers.createExecShellHandler(extendedTools, ideTools))
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
                register(ExtendedToolHandlers.createGitPushHandler(extendedTools))

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
                register(DependencyTreeTool(project))
                register(WebScraperToolHandlers.createWebScraperHandler())
                register(FetchUrlMarkdownTool())
                register(ClipboardToolHandlers.createClipboardHandler())
                register(TimestampToolHandlers.createTimestampHandler())
                register(UUIDToolHandlers.createUUIDHandler())

                // === T6.1: Code Insight 工具改用 UnifiedTool 类（消除 ToolExecutor 中的 when 硬编码）===
                val codeInsightExecutor = com.codesage.analysis.CodeInsightExecutor(project)
                register(AnalyzeSymbolTool(codeInsightExecutor))
                register(FindUsagesTool(codeInsightExecutor))
                register(FindCallersTool(codeInsightExecutor))
                register(FindCalleesTool(codeInsightExecutor))
                register(GetInheritanceChainTool(codeInsightExecutor))
                register(SemanticSearchTool(codeInsightExecutor))
                register(ReindexSemanticTool(codeInsightExecutor))
                register(GetFileSummaryTool(codeInsightExecutor))
                register(GetProjectStatsTool(codeInsightExecutor))

                // === T6.5: 高价值工具 ===
                register(CreatePullRequestTool())
                register(RunLinterTool())
                register(StartDebuggerTool(project))
                register(DatabaseSchemaTool())
                register(GitWorktreeTool())
                register(SymbolSearchTool(project))

                // === 6.11.1 MCP 动态工具发现 ===
                register(McpToolHandlers.createMcpToolSearchHandler(mcpServerManager))

                // === 6.11.3 Skill 统一调用元工具 ===
                if (skillRegistry != null && skillExecutor != null) {
                    register(UseSkillTool(skillRegistry, skillExecutor, project))
                }

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
    description = """
        Summary: 读取单个文件内容，支持相对/绝对路径、offset/limit 分页和可选行号输出。
        Args: path (string, required): 文件路径；offset (int): 起始行号（0-based）；limit (int): 最大读取行数；line_numbers (boolean): 是否额外返回带行号的 content_with_line_numbers，默认 false。
        Do: 大文件先不带 offset 读前 1000 行摘要；需要后续内容时用 offset 续读；读取前用 list_directory 确认路径。需要直接引用行号时传 line_numbers=true。
        Don't: 不要读取 node_modules/build/.gradle/target 等生成目录；不要一次性读取超大文件而不分页。
        Parallel: Yes，多个文件同时读取时用 read_multiple_files 更高效。
        Cap: 全文读取时内容超过 MAX_CONTENT_LENGTH 会被截断并标记 truncated；offset 越界返回明确错误。
    """.trimIndent(),
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
            ),
            "line_numbers" to ToolProperty(
                type = "boolean",
                description = "是否额外返回带行号的内容（content_with_line_numbers），默认 false"
            )
        ),
        required = listOf("path")
    )
)

internal fun writeFileTool() = Tool(
    name = "write_file",
    description = """
        Summary: 将内容写入指定文件，不存在则自动创建（含父目录），支持追加或覆盖。
        Args: path (string, required): 文件路径；content (string, required): 要写入的内容；append (boolean): 是否追加，默认 false。
        Do: 创建新文件或小文件完全重写时使用；写入前确认路径正确；关键文件修改后运行测试验证。
        Don't: 不要重写与任务无关的文件；不要用 write_file 做局部修改（改用 edit_file）；不要写入敏感信息。
        Parallel: No，同一文件的并发写入会导致冲突。
        Cap: 无显式大小限制，但极大 content 会消耗大量上下文。
    """.trimIndent(),
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
    description = """
        Summary: 列出目录内容，支持递归和深度控制，帮助理解项目结构。
        Args: path (string): 目录路径，默认项目根目录；recursive (boolean): 是否递归；max_depth (int): 递归深度 0-20，默认 3；include_hidden (boolean): 是否包含隐藏文件；exclude_dirs (array): 要跳过的目录名。
        Do: 探索项目结构时使用；先用小 depth，需要再加大；配合 read_file 读取关键文件。
        Don't: 不要对 node_modules 等大型生成目录做深层递归；不要假设目录存在，先用 get_project_structure 或 list_directory 确认。
        Parallel: Yes，多个独立目录可并行列出。
        Cap: 默认跳过 node_modules/build/.gradle/target/__pycache__/.idea；truncated=true 表示还有子目录未展开。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty(
                type = "string",
                description = "目录路径（相对项目根目录或绝对路径），默认为项目根目录"
            ),
            "recursive" to ToolProperty(
                type = "boolean",
                description = "是否递归列出子目录内容，默认为 false"
            ),
            "max_depth" to ToolProperty(
                type = "integer",
                description = "recursive=true 时的最大递归深度，0-20，默认 3"
            ),
            "include_hidden" to ToolProperty(
                type = "boolean",
                description = "是否包含 . 开头的隐藏文件（默认 false）"
            ),
            "exclude_dirs" to ToolProperty(
                type = "array",
                description = "要跳过的目录名列表；传空数组禁用过滤"
            )
        ),
        required = listOf()
    )
)

internal fun searchCodeTool() = Tool(
    name = "search_code",
    description = """
        Summary: 在项目中搜索代码，支持正则或普通文本，可按文件类型过滤。
        Args: query (string, required): 搜索关键词或正则；file_pattern (string): 文件匹配模式如 *.kt；path (string): 搜索根目录，默认项目根。
        Do: 查找符号定义、调用点、配置项时使用；先用精确 pattern；结合 file_pattern 缩小范围。
        Don't: 不要用过于宽泛的 query（如单个字母）；不要忽略 truncated=true 而基于不完整结果下结论。
        Parallel: Yes，多个独立搜索可并行执行。
        Cap: max_results 默认 200、上限 1000；truncated=true 或 partial_scan_files>0 表示结果不完整。
    """.trimIndent(),
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
    description = """
        Summary: 在工作目录下执行系统命令（shell），返回 stdout、stderr 和 exit_code；支持后台运行。
        Args: command (string, required): 要执行的命令；working_dir (string): 工作目录，默认项目根；timeout (int): 超时毫秒，默认 120000、最大 600000；run_in_background (boolean): 是否后台运行，默认 false。
        Do: 运行测试、构建、lint 等验证命令；用 `| head` 控制输出；命令前确认依赖已安装。长期进程（如 dev server）用 run_in_background=true。
        Don't: 不要执行 rm -rf /、curl | sh、修改系统配置等危险命令；不要假设命令存在而不检查；不要在沙箱内尝试网络命令。
        Parallel: Yes，多个相互独立的命令可并行执行；后台进程之间也独立。
        Cap: 单流输出超过 1M 字符会被截断并标记 stdout_truncated/stderr_truncated；命令运行在 OS 级沙箱中（禁网络、禁项目外写入）。后台命令当前不走 OS 级沙箱；传 stream_output=true 时后台命令会实时发送 CommandOutputStream 事件。
    """.trimIndent(),
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
                description = "超时时间（毫秒），默认 120000，最大 600000"
            ),
            "run_in_background" to ToolProperty(
                type = "boolean",
                description = "是否在后台运行命令。为 true 时返回 process_id，可用 read_process_output / kill_process 管理。"
            ),
            "stream_output" to ToolProperty(
                type = "boolean",
                description = "是否流式输出命令的 stdout/stderr。为 true 时命令执行期间会实时发送 command_output_delta 事件（后台命令同步支持；沙箱命令暂不支持）。默认 false。"
            )
        ),
        required = listOf("command")
    )
)

internal fun getProjectStructureTool() = Tool(
    name = "get_project_structure",
    description = """
        Summary: 获取项目整体结构概览，包括模块、源码目录和关键配置文件。
        Args: depth (int): 目录递归深度，默认 2。
        Do: 初识项目或确认模块边界时使用；先获取概览再深入具体文件。
        Don't: 不要用它代替 list_directory 读取详细文件列表；depth 不要设过大。
        Parallel: No，通常一次调用即可。
        Cap: 受 depth 限制，不会递归太深。
    """.trimIndent(),
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
    description = """
        Summary: 派生子 Agent 并行处理独立任务流，返回结构化 JSON 结果（非纯文本）。
        Args: task_description (string, required): 子任务详细描述；toolset (string): 工具集（coder/explorer/verifier/webfetcher 或旧别名 dev/research/test/browser）；context_files (array): 子 Agent 需要访问的文件；isolated_worktree (boolean): 是否在独立 git worktree 中运行，默认 false；max_depth (integer): 最大递归深度，范围 1-5，默认 2；allowed_tools (array of string): 显式白名单，子 Agent 只能使用列表中的工具；denied_tools (array of string): 黑名单，优先于 allowed_tools。
        Do: 任务可拆分为独立子任务时使用；选择最小权限的 toolset；需要进一步限制工具时传 allowed_tools/denied_tools；为子 Agent 提供清晰边界和必要上下文文件；当子任务需要修改文件且父 Agent 希望保持主工作区干净时，设 isolated_worktree=true。
        Don't: 不要用于高度耦合、必须连续沟通的任务；不要递归委托过深；不要把 delegate_task 加入 denied_tools（会报错）。
        Parallel: Yes，多个 delegate_task 可与其他工具并行（注意子 Agent 独立运行）。
        Cap: 返回 JSON 包含 success/result/files/blockers/tools_used/iterations_used/completed_tool_calls/session_id/raw_output/worktree_diff/worktree_changes；UI 仍通过事件流展示自然语言总结。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "task_description" to ToolProperty(
                type = "string",
                description = "Detailed description of what the sub-agent should do"
            ),
            "toolset" to ToolProperty(
                type = "string",
                description = "Which toolset to give the sub-agent. " +
                        "New names: `coder` (write code, default), `explorer` (read/search), " +
                        "`verifier` (run tests/commands), `webfetcher` (network/browser). " +
                        "Old aliases still work: dev / research / test / browser (deprecated, will trigger a WARN). " +
                        "Pick the most restrictive toolset that can do the task.",
                enum = listOf(
                    // 新名（推荐）
                    "coder", "explorer", "verifier", "webfetcher",
                    // 旧名 alias（兼容老 prompt）
                    "dev", "research", "test", "browser"
                )
            ),
            "context_files" to ToolProperty(
                type = "array",
                description = "Files the sub-agent needs access to"
            ),
            "isolated_worktree" to ToolProperty(
                type = "boolean",
                description = "Whether to run the sub-agent in an isolated git worktree. " +
                        "When true, a temporary worktree is created from the current HEAD, " +
                        "the sub-agent's project basePath points to the worktree, and after " +
                        "completion the worktree diff is returned as worktree_diff/worktree_changes. " +
                        "Default false."
            ),
            "max_depth" to ToolProperty(
                type = "integer",
                description = "Maximum sub-agent recursion depth for this task. " +
                        "Range: 1-5. Default: 2. " +
                        "depth=0 is the parent agent; the sub-agent may spawn further sub-agents " +
                        "until depth reaches max_depth."
            ),
            "allowed_tools" to ToolProperty(
                type = "array",
                description = "Explicit allow-list of tool names the sub-agent may use. " +
                        "When provided, the sub-agent's toolset is further restricted to the intersection " +
                        "of this list and the selected toolset. `delegate_task` is always retained unless " +
                        "explicitly listed in denied_tools."
            ),
            "denied_tools" to ToolProperty(
                type = "array",
                description = "Explicit deny-list of tool names. Takes precedence over allowed_tools. " +
                        "Do NOT include `delegate_task` here unless you want to prevent the sub-agent from " +
                        "delegating further, which will cause an error."
            )
        ),
        required = listOf("task_description")
    )
)

internal fun findFileTool() = Tool(
    name = "find_file",
    description = """
        Summary: 按文件名模式（支持 glob/regex）查找文件，返回匹配路径。
        Args: pattern (string, required): 文件名模式，如 *.kt、build.gradle；path (string): 搜索根目录，默认项目根；max_results (int): 最大结果数 1-1000，默认 50。
        Do: 定位特定文件、配置文件或测试文件时使用；pattern 尽量精确。
        Don't: 不要用 .* 等过宽模式；不要忽略 truncated=true。
        Parallel: Yes，多个独立查找可并行。
        Cap: 默认最多 50 条，truncated=true 表示还有更多匹配。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "pattern" to ToolProperty("string", "File name pattern to search for (e.g. '*.kt', 'build.gradle')"),
            "path" to ToolProperty("string", "Search root directory, defaults to project root"),
            "max_results" to ToolProperty("integer", "Maximum number of results to return, default 50")
        ),
        required = listOf("pattern")
    )
)

internal fun globTool() = Tool(
    name = "glob",
    description = """
        Summary: 按 glob 模式批量定位文件或目录，支持 `**` 递归匹配。
        Args: pattern (string, required): glob 模式，如 `src/**/*.kt`、`*.md`；path (string): 搜索根目录，默认项目根；max_results (int): 最大结果数 1-1000，默认 100；include_dirs (boolean): 是否返回匹配的目录，默认 false；exclude_dirs (array): 要排除的目录名；include_hidden (boolean): 是否包含隐藏文件，默认 false。
        Do: 需要批量读取某类文件时先用 glob 定位；比 find_file 更适合模式化批量匹配。
        Don't: 不要用过宽模式（如 `**/*`）而不加 max_results；不要忽略 truncated=true。
        Parallel: Yes，多个独立 glob 可并行。
        Cap: 默认排除 node_modules/.git/build/.gradle/target/__pycache__/.idea；结果超过 max_results 会截断。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "pattern" to ToolProperty("string", "Glob pattern to match (e.g. 'src/**/*.kt', '*.md')"),
            "path" to ToolProperty("string", "Search root directory, defaults to project root"),
            "max_results" to ToolProperty("integer", "Maximum number of results to return, default 100, max 1000"),
            "include_dirs" to ToolProperty(
                "boolean",
                "Whether to include matching directories in results, default false"
            ),
            "exclude_dirs" to ToolProperty("array", "Directory names to skip"),
            "include_hidden" to ToolProperty("boolean", "Whether to include hidden files/directories, default false")
        ),
        required = listOf("pattern")
    )
)

internal fun grepCodeTool() = Tool(
    name = "grep_code",
    description = """
        Summary: 在文件内容中搜索文本模式（类似 grep），返回匹配行及上下文。
        Args: query (string, required): 搜索文本或正则；path (string): 搜索根目录；file_pattern (string): 文件过滤模式；context_lines (int): 上下文行数 0-50，默认 2。
        Do: 查找代码引用、字符串常量、函数调用时使用；先用精确 query。
        Don't: 不要搜索过于宽泛的模式；不要忽略 truncated 或 partial_scan_files 标记。
        Parallel: Yes，多个独立搜索可并行。
        Cap: max_results 默认 200、上限 1000；大文件仅扫描前 1000 行。
    """.trimIndent(),
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
    description = """
        Summary: 获取文件元数据：大小、类型、扩展名、修改时间、读写权限、行数（<1MB）。
        Args: path (string, required): 文件路径。
        Do: 读取大文件前先用它判断大小；验证文件是否存在及可写。
        Don't: 不要用它代替 read_file 获取内容；不要对目录使用（结果可能无意义）。
        Parallel: Yes。
        Cap: 行数仅对小于 1MB 文件有效。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty("string", "File path")
        ),
        required = listOf("path")
    )
)

internal fun readMultipleFilesTool() = Tool(
    name = "read_multiple_files",
    description = """
        Summary: 一次性读取多个文件，比多次 read_file 更高效，支持可选行号输出。
        Args: paths (array, required): 文件路径列表；line_numbers (boolean): 是否为每个文件额外返回 content_with_line_numbers，默认 false。
        Do: 需要同时读取多个相关文件（如接口与实现、测试与被测代码）时使用。需要直接引用行号时传 line_numbers=true。
        Don't: 不要用它读取生成目录或超大文件；单个文件过大时改用 read_file 分页。
        Parallel: Yes，内部并行读取。
        Cap: 每个文件内容超过 MAX_CONTENT_LENGTH 会被截断并标记 truncated 和 original_length。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "paths" to ToolProperty("array", "List of file paths to read"),
            "line_numbers" to ToolProperty(
                type = "boolean",
                description = "是否为每个文件额外返回带行号的内容（content_with_line_numbers），默认 false"
            )
        ),
        required = listOf("paths")
    )
)

internal fun editFileTool() = Tool(
    name = "edit_file",
    description = """
        Summary: 精确编辑文件，用 old_string 替换为 new_string，或按行范围替换。
        Args: path (string, required): 文件路径；old_string (string): 要被替换的文本；new_string (string, required): 替换后的文本；start_line/end_line (int): 1-based 行范围；fuzzy_match (boolean): 为 true 时忽略行首/行尾空白差异，并在 old_string 不唯一时自动用上下文去歧。
        Do: 小范围修改时使用；old_string 提供足够上下文确保唯一匹配；修改后验证文件仍能编译/通过测试。
        Don't: 不要在不确认上下文的情况下使用；不要用它做大规模重写（改用 write_file）。
        Parallel: No，同一文件并发编辑会冲突。
        Cap: start_line/end_line 越界会明确报错；old_string 不唯一且无法去歧时返回候选位置。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty("string", "File path"),
            "old_string" to ToolProperty("string", "Text to replace (optional if using line range)"),
            "new_string" to ToolProperty("string", "Replacement text"),
            "start_line" to ToolProperty("integer", "Start line number for range replacement (1-based)"),
            "end_line" to ToolProperty("integer", "End line number for range replacement (1-based)"),
            "fuzzy_match" to ToolProperty(
                "boolean",
                "Ignore leading/trailing whitespace and use surrounding context to disambiguate non-unique old_string"
            )
        ),
        required = listOf("path", "new_string")
    )
)

internal fun deleteFileTool() = Tool(
    name = "delete_file",
    description = """
        Summary: 删除文件；删除目录需要显式传 recursive=true。
        Args: path (string, required): 文件或目录路径；recursive (boolean): 删除目录及其内容时必须为 true。
        Do: 清理确认不再需要的文件；删除目录前 double-check 路径。
        Don't: 不要删除与任务无关的文件；不要未确认就递归删除目录；不要删除 .git、node_modules 等关键目录。
        Parallel: No。
        Cap: 默认拒绝删除目录，防止误删。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty("string", "File path (directories require recursive=true)"),
            "recursive" to ToolProperty("boolean", "Required true to delete a directory and all its contents")
        ),
        required = listOf("path")
    )
)

internal fun copyFileTool() = Tool(
    name = "copy_file",
    description = """
        Summary: 复制文件或目录到目标路径，覆盖已存在目标。
        Args: source (string, required): 源路径；destination (string, required): 目标路径。
        Do: 备份文件、创建模板副本时使用；确认 destination 正确。
        Don't: 不要覆盖用户未同意的重要文件；不要复制到项目外。
        Parallel: No，目标路径冲突会导致不可预期结果。
        Cap: 自动处理目录递归复制。
    """.trimIndent(),
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
    description = """
        Summary: 移动/重命名文件；跨文件系统时自动回退为 copy+delete。
        Args: source (string, required): 源路径；destination (string, required): 目标路径。
        Do: 重命名文件、整理目录结构时使用；移动后更新相关引用。
        Don't: 不要移动到项目外；不要覆盖重要文件而不确认。
        Parallel: No。
        Cap: 跨文件系统会报告 method=copy_and_delete；部分失败会返回错误。
    """.trimIndent(),
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
    description = """
        Summary: 查看 Git 仓库状态，返回当前分支和变更文件列表。
        Args: working_dir (string): 工作目录，默认项目根。
        Do: 开始任务前了解当前变更；确认分支后再提交。
        Don't: 不要在非 Git 目录调用（会报错）。
        Parallel: Yes，可与其他只读 Git 工具并行。
        Cap: 只读工具，不修改仓库。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录")
        ),
        required = listOf()
    )
)

internal fun gitDiffTool() = Tool(
    name = "git_diff",
    description = """
        Summary: 查看 Git 文件差异，返回结构化结果（文件/hunk/行级增删）。
        Args: working_dir (string): 工作目录；cached (boolean): 是否查看暂存区；file (string): 指定文件路径；include_raw (boolean): 是否额外返回原始 diff 文本，默认 false。
        Returns: files[]（含 old_path/new_path/change_type/additions/deletions/hunks[]），以及 total_additions/total_deletions/total_changes/has_changes。
        Do: 提交前审查变更；按文件和行号分析改动。
        Don't: 不要用于未跟踪文件；差异过大时按 file 参数分批查询。
        Parallel: Yes。
        Cap: 只读工具。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录"),
            "cached" to ToolProperty("boolean", "是否查看暂存区差异 (--cached)，默认为 false"),
            "file" to ToolProperty("string", "指定查看差异的文件路径，可选"),
            "include_raw" to ToolProperty("boolean", "是否额外返回原始 diff 文本，默认 false")
        ),
        required = listOf()
    )
)

internal fun gitLogTool() = Tool(
    name = "git_log",
    description = """
        Summary: 查看 Git 提交历史（单行格式）。
        Args: working_dir (string): 工作目录；limit (int): 最大提交数 1-100，默认 20。
        Do: 了解近期提交、定位引入问题的提交时使用。
        Don't: limit 不要传过大值；不要用于非 Git 目录。
        Parallel: Yes。
        Cap: 只读工具，最大返回 100 条。
    """.trimIndent(),
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
    description = """
        Summary: 查看 Git 分支列表，识别当前分支。
        Args: working_dir (string): 工作目录。
        Do: 确认当前分支、查看远程分支时使用。
        Don't: 不要用于非 Git 目录。
        Parallel: Yes。
        Cap: 只读工具。
    """.trimIndent(),
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
    description = """
        Summary: [DEPRECATED] 已合并到 run_command。保留此工具仅用于兼容旧 prompt，内部会转发到 run_command。
        Args: 与 run_command 相同：command (string, required)；working_dir (string)；timeout (int): 默认 120000、最大 600000；run_in_background (boolean)；stream_output (boolean)。
        Do: 新实现请直接使用 run_command。
        Don't: 不要在新建 workflow 中继续使用 exec_shell。
        Parallel: Yes。
        Cap: 同 run_command。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "command" to ToolProperty("string", "要执行的 Shell 命令"),
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录"),
            "timeout" to ToolProperty("integer", "超时时间（毫秒），默认 120000，最大 600000"),
            "run_in_background" to ToolProperty("boolean", "是否在后台运行命令")
        ),
        required = listOf("command")
    )
)

internal fun killProcessTool() = Tool(
    name = "kill_process",
    description = """
        Summary: 终止由 run_command --run_in_background 启动的后台进程。
        Args: process_id (string, required): run_command 返回的进程 ID。
        Do: 当后台任务不再需要，或在启动冲突进程前清理时使用。
        Don't: 不要随意终止不了解的进程。
        Parallel: Yes。
        Cap: 若进程正在运行则返回 killed=true；exit_code 可能为 -1。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "process_id" to ToolProperty("string", "run_command 返回的进程 ID")
        ),
        required = listOf("process_id")
    )
)

internal fun readProcessOutputTool() = Tool(
    name = "read_process_output",
    description = """
        Summary: 读取 run_command --run_in_background 启动的后台进程的最新 stdout/stderr。
        Args: process_id (string, required): 进程 ID；max_output_chars (int): 单流最大字符数，默认 100000。
        Do: 用于轮询长期运行的命令（如 dev server、test watcher）或捕获最终输出。
        Don't: 不要对噪声很大的进程设置过大的 max_output_chars。
        Parallel: Yes。
        Cap: 进程存活时 running=true；退出前 exit_code 为 null。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "process_id" to ToolProperty("string", "run_command 返回的进程 ID"),
            "max_output_chars" to ToolProperty("integer", "单流最大字符数，默认 100000")
        ),
        required = listOf("process_id")
    )
)

// === HTTP Tool ===

internal fun httpRequestTool() = Tool(
    name = "http_request",
    description = """
        Summary: 发送 HTTP 请求，支持 GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS，自动格式化 JSON 响应；支持响应大小限制与流式下载到文件。
        Args: url (string, required): 请求 URL；method (string): HTTP 方法，默认 GET；headers (object): 请求头；body (string): 请求体；timeout (int): 超时毫秒，默认 30000；max_size_bytes (int): 内存中最大响应字节数，默认 1MB，传 0 不限制；output_file (string): 若提供，完整响应流式写入该文件而不进入内存。
        Do: 调用外部 API、下载文档；大文件用 output_file 保存到磁盘。
        Don't: 不要访问内网地址、localhost、file:// 等（会被 SSRF 防护拦截）；不要发送敏感凭证而不确认；不要对超过 max_size_bytes 的响应忽略 truncated 标记。
        Parallel: Yes，多个独立请求可并行。
        Cap: SSRF 防护默认开启；连接/读取超时会返回明确错误信息；响应超过 max_size_bytes 时 truncated=true 并提示用 output_file。
    """.trimIndent(),
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
            "timeout" to ToolProperty("integer", "超时时间（毫秒），默认 30000"),
            "max_size_bytes" to ToolProperty("integer", "内存中最大响应字节数，默认 1048576（1MB），传 0 表示不限制"),
            "output_file" to ToolProperty("string", "将完整响应流式保存到该文件路径，可选")
        ),
        required = listOf("url")
    )
)

// === Data Processing Tools ===

internal fun parseJsonTool() = Tool(
    name = "parse_json",
    description = """
        Summary: 解析 JSON 字符串，支持点号路径查询（如 user.address.city）。
        Args: json (string, required): JSON 字符串；query (string): 点号分隔路径，可选。
        Do: 提取 API 响应、配置文件中的特定字段；处理嵌套 JSON。
        Don't: 不要用于非 JSON 文本；query 路径不存在时返回 null。
        Parallel: Yes。
        Cap: 仅支持简单点号路径，不支持数组切片或复杂表达式。
    """.trimIndent(),
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
    description = """
        Summary: 将字符串编码为 Base64。
        Args: input (string, required): 要编码的字符串。
        Do: 构造 basic auth header、编码小段二进制数据时使用。
        Don't: 不要用于大文件（应使用文件工具或流）。
        Parallel: Yes。
        Cap: 纯文本输入，输出长度约为输入的 4/3。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要编码的字符串")
        ),
        required = listOf("input")
    )
)

internal fun decodeBase64Tool() = Tool(
    name = "decode_base64",
    description = """
        Summary: 将 Base64 字符串解码为普通字符串。
        Args: input (string, required): Base64 字符串。
        Do: 解析 basic auth、解码小数据时使用。
        Don't: 不要解码不可信来源的大段 Base64；非法输入会报错。
        Parallel: Yes。
        Cap: 仅支持 UTF-8 可解码内容。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要解码的 Base64 字符串")
        ),
        required = listOf("input")
    )
)

internal fun formatJsonTool() = Tool(
    name = "format_json",
    description = """
        Summary: JSON 美化或压缩格式化。
        Args: json (string, required): JSON 字符串；compact (boolean): 是否压缩，默认 false。
        Do: 整理 API 响应、生成可读配置时使用。
        Don't: 不要用于非 JSON 输入；非法 JSON 会报错。
        Parallel: Yes。
        Cap: 纯格式化，不验证 schema。
    """.trimIndent(),
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
    description = """
        Summary: 计算字符串的 MD5 哈希值。
        Args: input (string, required): 要计算哈希的字符串。
        Do: 生成校验值、快速比对内容时使用。
        Don't: 不要用于密码或敏感数据（MD5 不安全）。
        Parallel: Yes。
        Cap: 输出 32 位十六进制字符串。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要计算哈希的字符串")
        ),
        required = listOf("input")
    )
)

internal fun hashSha256Tool() = Tool(
    name = "hash_sha256",
    description = """
        Summary: 计算字符串的 SHA-256 哈希值。
        Args: input (string, required): 要计算哈希的字符串。
        Do: 生成校验值、内容签名时使用。
        Don't: 不要用于密码哈希（应使用专门 KDF）。
        Parallel: Yes。
        Cap: 输出 64 位十六进制字符串。
    """.trimIndent(),
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
    description = """
        Summary: 将文件添加到 Git 暂存区。
        Args: working_dir (string): 工作目录；files (array): 要添加的文件路径列表；all (boolean): 是否添加所有变更，默认 false。
        Do: 提交前暂存变更；明确指定 files 避免误加。
        Don't: 不要未确认就 all=true；不要添加包含敏感信息的文件。
        Parallel: No，会修改仓库状态。
        Cap: 仅对 Git 仓库有效。
    """.trimIndent(),
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
    description = """
        Summary: 创建 Git 提交或 amend 上一次提交。
        Args: working_dir (string): 工作目录；message (string): 提交信息；amend (boolean): 是否 amend；no_verify (boolean): 是否跳过钩子。
        Do: 完成变更后创建提交；message 清晰描述改动。
        Don't: 不要未确认就 amend 公共提交；不要提交未审查的代码。
        Parallel: No。
        Cap: 仅对 Git 仓库有效。
    """.trimIndent(),
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
    description = """
        Summary: Git stash 操作：保存、弹出、查看或清空。
        Args: working_dir (string): 工作目录；action (enum): save/pop/list/clear/drop；message (string): stash 消息（仅 save）。
        Do: 临时保存未完成变更、切换分支前使用。
        Don't: 不要随意 clear/drop 以免丢失工作；drop 前确认 stash 内容。
        Parallel: No。
        Cap: 仅对 Git 仓库有效。
    """.trimIndent(),
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
    description = """
        Summary: 查看指定文件每行的最后修改者和提交信息。
        Args: working_dir (string): 工作目录；file (string, required): 文件路径；line_start/line_end (int): 行范围。
        Do: 追踪代码历史、定位回归引入提交时使用。
        Don't: 不要用于非 Git 跟踪文件；范围过大时先缩小。
        Parallel: Yes。
        Cap: 只读工具。
    """.trimIndent(),
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

internal fun gitPushTool() = Tool(
    name = "git_push",
    description = """
        Summary: 将当前分支推送到远程仓库；若分支尚无上游跟踪分支，则自动使用 `git push -u`。
        Args: working_dir (string): 工作目录；remote (string): 远程名，默认 origin；branch (string): 要推送的分支，默认当前分支。
        Do: 在 git_commit 后推送代码，为 create_pull_request 做准备。
        Don't: 不要推送到错误的远程；推送前先确认分支和提交内容。
        Parallel: No，会修改远程仓库状态。
        Cap: 仅对 Git 仓库有效；无上游分支时自动设置上游。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录"),
            "remote" to ToolProperty("string", "远程名，默认 origin"),
            "branch" to ToolProperty("string", "要推送的分支，默认当前分支")
        ),
        required = listOf()
    )
)

// 文件操作增强
internal fun createDirectoryTool() = Tool(
    name = "create_directory",
    description = """
        Summary: 创建目录（含不存在的父目录）。
        Args: path (string, required): 目录路径。
        Do: 创建新模块、测试目录时使用；确认 path 在项目内。
        Don't: 不要创建与任务无关的目录；不要创建系统目录。
        Parallel: No，目标路径冲突会导致错误。
        Cap: 幂等，目录已存在时返回成功。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty("string", "要创建的目录路径")
        ),
        required = listOf("path")
    )
)

internal fun zipDirectoryTool() = Tool(
    name = "zip_directory",
    description = """
        Summary: 将目录压缩为 zip 文件。
        Args: source (string, required): 要压缩的目录；destination (string, required): 输出 zip 路径。
        Do: 打包备份、生成归档时使用。
        Don't: 不要压缩项目外目录；不要覆盖重要文件而不确认。
        Parallel: No。
        Cap: 标准 zip 格式，不含加密。
    """.trimIndent(),
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
    description = """
        Summary: 解压 zip 文件到指定目录。
        Args: source (string, required): zip 文件路径；destination (string, required): 解压目标目录。
        Do: 解压依赖包、模板或备份时使用；确认来源可信。
        Don't: 不要解压来源不明的 zip（路径遍历风险）；不要解压到项目外。
        Parallel: No。
        Cap: 标准 zip 格式。
    """.trimIndent(),
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
    description = """
        Summary: 执行 Maven 命令（mvn）。
        Args: goals (string, required): Maven 目标；working_dir (string): 工作目录；profiles (string): 激活的 profile；properties (object): 额外系统属性。
        Do: 构建、测试、查看依赖树时使用；优先用 verify/test 等安全目标。
        Don't: 不要执行会修改系统或删除项目的命令；注意沙箱禁止网络可能影响依赖下载。
        Parallel: No，构建通常有状态。
        Cap: 依赖本地 mvn 和项目 pom.xml。
    """.trimIndent(),
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
    description = """
        Summary: 执行 Gradle 命令（./gradlew 或 gradle）。
        Args: tasks (string, required): Gradle 任务；working_dir (string): 工作目录；args (string): 额外参数如 --info。
        Do: 构建、测试、查看依赖时使用；优先用 check/test。
        Don't: 不要执行危险任务；注意沙箱禁止网络可能影响依赖下载。
        Parallel: No。
        Cap: 依赖本地 gradlew/gradle 和 build 文件。
    """.trimIndent(),
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
    description = """
        Summary: 运行项目测试（JUnit/TestNG），返回 stdout/stderr 摘要以及 tests[] 结构化结果；支持流式输出。
        Args: test_class (string): 测试类全限定名；test_method (string): 测试方法名（需同时指定 test_class）；package_path (string): 测试包路径；working_dir (string): 工作目录；stream_output (boolean): 是否实时流式输出 stdout/stderr，默认 false。
        Do: 修改代码后运行相关测试验证；先跑最小相关集，再扩大。长时间测试建议开启 stream_output。
        Don't: 不要未修改就全量跑所有测试；注意测试可能依赖外部服务。
        Parallel: No，测试运行通常有状态。
        Cap: 解析 Gradle build/test-results/test/*.xml 或 Maven target/surefire-reports/*.xml；失败用例会补充 file_path/line 与 snippet；未找到 XML 时退回 stdout 摘要。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "test_class" to ToolProperty("string", "测试类全限定名（可选）"),
            "test_method" to ToolProperty("string", "测试方法名（可选，需同时指定 test_class）"),
            "package_path" to ToolProperty("string", "测试包路径（可选）"),
            "working_dir" to ToolProperty("string", "工作目录，默认为项目根目录"),
            "stream_output" to ToolProperty(
                "boolean",
                "是否实时流式输出 stdout/stderr。为 true 时执行期间会实时发送 command_output_delta 事件，默认 false。"
            )
        ),
        required = listOf()
    )
)

// 正则工具
internal fun regexTestTool() = Tool(
    name = "regex_test",
    description = """
        Summary: 测试正则表达式是否匹配给定文本，返回匹配结果和捕获组。
        Args: pattern (string, required): 正则表达式；text (string, required): 要测试的文本；flags (string): 标志如 i/m。
        Do: 验证正则、提取捕获组时使用；先用小文本测试。
        Don't: 不要使用过于复杂或回溯严重的正则；不要处理超大文本。
        Parallel: Yes。
        Cap: 基于 Kotlin Regex 实现。
    """.trimIndent(),
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
    description = """
        Summary: 使用正则表达式从文本中提取所有匹配项，支持命名捕获组。
        Args: pattern (string, required): 正则表达式；text (string, required): 要提取的文本；flags (string): 标志如 i/m。
        Do: 从日志、API 响应中提取结构化数据时使用。
        Don't: 不要用复杂正则解析 HTML/JSON（应用专用工具）；不要处理超大文本。
        Parallel: Yes。
        Cap: 基于 Kotlin Regex 实现。
    """.trimIndent(),
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
    description = """
        Summary: 比较两个文件或两段文本的差异，返回统一 diff 格式。
        Args: source (string, required): 原始路径或文本；target (string, required): 目标路径或文本；is_paths (boolean): 是否为文件路径，默认 true；context_lines (int): 上下文行数，默认 3。
        Do: 审查变更、生成补丁描述时使用；is_paths=true 时直接比较文件。
        Don't: 不要用于二进制文件；超大文件可能产生巨大 diff。
        Parallel: Yes。
        Cap: 文本 diff，context_lines 最大受实现限制。
    """.trimIndent(),
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
    description = """
        Summary: 为代码生成文档注释（Javadoc/KDoc/JSDoc）。
        Args: file_path (string, required): 文件路径；line_start/line_end (int, required): 1-based 行范围；style (enum): javadoc/kdoc/jsdoc。
        Do: 为公共 API、复杂函数生成文档时使用；生成后人工检查准确性。
        Don't: 不要覆盖用户手写的详细文档；不要对私有简单 getter 滥用。
        Parallel: No，会修改文件。
        Cap: 基于文件内容与行范围生成。
    """.trimIndent(),
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
    description = """
        Summary: 执行 SQL 查询或命令（需项目已配置 IntelliJ Database Tools 数据源）。
        Args: data_source_name (string, required): 数据源名称；sql (string, required): SQL 语句；limit (int): 最大返回行数，默认 100。
        Do: 排查数据问题、验证查询结果时使用；优先 SELECT，避免 DML 除非用户要求。
        Don't: 不要执行 DROP/DELETE/UPDATE 等危险命令而不确认；不要查询无关表。
        Parallel: No，数据库连接有状态。
        Cap: 受 limit 限制；仅支持已配置数据源。
    """.trimIndent(),
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
    description = """
        Summary: 执行 Docker 命令（ps/images/logs/run/exec/stop/rmi/inspect）。
        Args: command (enum, required): Docker 子命令；target (string): 容器/镜像名或 ID；options (string): 额外选项。
        Do: 查看容器状态、读取日志、检查镜像时使用；优先只读命令。
        Don't: 不要未确认就 stop/rmi/run 影响运行中服务；不要在生产环境随意操作。
        Parallel: No，Docker daemon 操作有状态。
        Cap: 依赖本地 Docker daemon 和权限。
    """.trimIndent(),
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
    description = """
        Summary: 分析项目依赖（tree/outdated/conflicts）。
        Args: action (enum, required): tree/outdated/conflicts；working_dir (string): 工作目录。
        Do: 排查依赖冲突、查找过时依赖、生成依赖树时使用。
        Don't: 不要频繁调用 outdated（耗时长）；注意沙箱禁网络可能影响版本检查。
        Parallel: No。
        Cap: 支持 Maven/Gradle 项目，结果依赖构建工具输出。
    """.trimIndent(),
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
    description = """
        Summary: 抓取网页内容并提取纯文本，支持 CSS 选择器。
        Args: url (string, required): 目标 URL；selector (string): CSS 选择器；timeout (int): 超时毫秒，默认 15000。
        Do: 获取文档、教程、API 说明时使用；selector 可精确定位内容。
        Don't: 不要抓取需要登录或受保护的页面；不要频繁请求同一站点。
        Parallel: Yes，多个独立 URL 可并行。
        Cap: 返回纯文本，复杂动态页面可能无法渲染。
    """.trimIndent(),
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
    description = """
        Summary: 操作系统剪贴板：get 获取内容 / set 设置内容。
        Args: action (enum, required): get/set；content (string): set 时必填。
        Do: 将生成的代码片段写入剪贴板供用户粘贴；读取用户已复制的文本。
        Don't: 不要写入敏感信息；get 时注意隐私内容。
        Parallel: No，剪贴板是全局状态。
        Cap: 受操作系统剪贴板大小限制。
    """.trimIndent(),
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
    description = """
        Summary: 时间戳与日期字符串互转，支持 ISO/RFC/relative 等格式。
        Args: value (string, required): 时间戳或日期字符串；input_format (enum, required): timestamp/iso/rfc；output_format (enum, required): timestamp/iso/rfc/relative。
        Do: 日志分析、时间格式化、计算相对时间时使用。
        Don't: 不要输入与 input_format 不匹配的格式。
        Parallel: Yes。
        Cap: 基于标准日期格式解析。
    """.trimIndent(),
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
    description = """
        Summary: 生成 UUID v4 或验证 UUID 格式。
        Args: action (enum, required): generate/validate；uuid (string): validate 时必填。
        Do: 生成唯一标识、验证用户输入的 UUID 时使用。
        Don't: 不要用于加密安全场景（v4 是随机，非排序）。
        Parallel: Yes。
        Cap: 标准 UUID v4 格式。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = mapOf(
            "action" to ToolProperty("string", "操作: generate/validate", enum = listOf("generate", "validate")),
            "uuid" to ToolProperty("string", "要验证的 UUID（validate 时必填）")
        ),
        required = listOf("action")
    )
)
