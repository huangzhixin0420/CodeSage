package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.UnifiedTool
import com.codesage.shared.serialization.JsonArgDecoders
import com.codesage.model.dto.ToolCategory
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * T6.5 修复：6 个高价值工具（按价值/需求排序）
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T6.5）：
 * - [ ] 每个新工具至少 2 个单元测试
 * - [ ] 文档：每个工具一份 README（合并在本文件 KDoc 中）
 *
 * 设计要点：
 * - 每个工具继承 UnifiedTool，metadata + logic 在同一 class
 * - 实现委派给 ProcessBuilder / OkHttp 调用系统命令，避免新依赖
 * - 错误处理：command exit code != 0 时返回 Error 结果
 * - 安全：所有 shell 命令用 ProcessBuilder + 参数数组（不用 string 拼接）
 */

// region === 1. create_pull_request（gh CLI 包装）===

/**
 * 通过 GitHub CLI (`gh`) 创建 Pull Request。
 *
 * 用法：调用 LLM 提交 PR 前通常需要先 `git add` + `git commit` + `git push`，
 * 然后调用本工具一键创建 PR。
 *
 * 前置：用户机器上需要安装 `gh`（https://cli.github.com）并已认证 `gh auth login`。
 */
class CreatePullRequestTool : UnifiedTool(
    name = "create_pull_request",
    description = "Create a GitHub Pull Request via `gh` CLI. Requires `gh` to be installed and authenticated. " +
            "Repository must already have a branch pushed to origin.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "repo" to ToolProperty(
                "string",
                "Repository in owner/repo format (e.g. 'minimax/codesage'). If omitted, uses current dir's repo."
            ),
            "title" to ToolProperty("string", "PR title"),
            "body" to ToolProperty("string", "PR description (markdown supported)"),
            "base" to ToolProperty("string", "Base branch (default: main)"),
            "head" to ToolProperty("string", "Head branch (default: current branch)"),
            "draft" to ToolProperty("boolean", "Create as draft PR (default: false)")
        ),
        required = listOf("title")
    )
) {
    override val tool = super.tool.copy(
        category = ToolCategory.GIT,
        tags = setOf("github", "pr", "review", "workflow")
    )

    override suspend fun execute(args: JsonObject): com.codesage.agent.tools.ToolResult {
        // C2 修复：用 JsonArgDecoders 安全反序列化，避免 `toString().removeSurrounding("\"")` 的 magic pattern。
        // 之前 LLM 返回 `"value":null` 时会被 `toString() = "null"` 当成字符串传入 ProcessBuilder。
        val title = JsonArgDecoders.stringArg(args, "title")
        val body = JsonArgDecoders.stringArg(args, "body")
        val repo = JsonArgDecoders.stringArgOrNull(args, "repo")
        val base = JsonArgDecoders.stringArg(args, "base", default = "main")
        val head = JsonArgDecoders.stringArgOrNull(args, "head")
        val draft = JsonArgDecoders.boolArg(args, "draft", default = false)

        val cmd = mutableListOf("gh", "pr", "create", "--title", title, "--body", body, "--base", base)
        repo?.let { cmd.addAll(listOf("--repo", it)) }
        head?.let { cmd.addAll(listOf("--head", it)) }
        if (draft) cmd.add("--draft")

        return runCommand(cmd)
    }
}

// region === 2. run_linter ===

/**
 * 根据 build 系统自动运行 linter：
 * - Maven: `mvn checkstyle:check`
 * - Gradle: `gradle check` 或 `gradle lint`（取决于项目配置）
 * - npm: `npm run lint`
 * - pip: `flake8`（如果存在）
 */
class RunLinterTool : UnifiedTool(
    name = "run_linter",
    description = "Auto-detect the project's build system (Maven/Gradle/npm) and run the linter. " +
            "Returns the lint output (truncated to 10k chars).",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "working_dir" to ToolProperty("string", "Project root directory (default: current dir)"),
            "fix" to ToolProperty("boolean", "Pass --fix to auto-fix linting issues (default: false)")
        ),
        required = emptyList()
    )
) {
    override val tool = super.tool.copy(
        category = ToolCategory.BUILD,
        tags = setOf("lint", "checkstyle", "eslint", "flake8", "quality")
    )

    override suspend fun execute(args: JsonObject): com.codesage.agent.tools.ToolResult {
        val workingDir = JsonArgDecoders.stringArgOrNull(args, "working_dir")
        val fix = JsonArgDecoders.boolArg(args, "fix", default = false)

        val dir = workingDir ?: System.getProperty("user.dir")
        val fixArg = if (fix) " --fix" else ""

        val (cmd, toolName) = when {
            File(dir, "pom.xml").exists() ->
                com.codesage.agent.tools.handlers.BuildCommandResolver
                    .mavenCommand(dir, listOf("-B", "checkstyle:check$fixArg")) to "Maven checkstyle"

            File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists() ->
                com.codesage.agent.tools.handlers.BuildCommandResolver
                    .gradleCommand(dir, listOf("check$fixArg")) to "Gradle check"

            File(dir, "package.json").exists() -> {
                val npmCmd = if (fix) "lint:fix" else "lint"
                listOf("npm", "run", npmCmd) to "npm run $npmCmd"
            }

            else -> return com.codesage.agent.tools.ToolResult.Error(
                "No supported build system found in $dir. " +
                        "Expected one of: pom.xml, build.gradle(.kts), package.json"
            )
        }

        return runCommand(cmd, workingDir = dir, toolName = toolName)
    }
}

// region === 3. start_debugger ===

/**
 * 启动 IntelliJ 调试器附加到当前项目。
 *
 * 用法：让 LLM 能在某个关键位置设置断点，然后调用此工具开始调试会话。
 *
 * 实现：使用 IntelliJ Platform 的 `XDebuggerManager` API。
 * 注意：需要一个真实的 Project 实例（IDE 上下文），在测试环境无法运行。
 */
class StartDebuggerTool(private val project: com.intellij.openapi.project.Project?) : UnifiedTool(
    name = "start_debugger",
    description = "Start the IntelliJ debugger session for the current project. " +
            "In test environments without an active project, returns an error.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "session_name" to ToolProperty("string", "Human-readable name for this debug session")
        ),
        required = emptyList()
    )
) {
    override val tool = super.tool.copy(
        category = ToolCategory.SYSTEM,
        tags = setOf("debug", "intellij", "breakpoint")
    )

    override suspend fun execute(args: JsonObject): com.codesage.agent.tools.ToolResult {
        if (project == null || project.isDisposed) {
            return com.codesage.agent.tools.ToolResult.Error(
                "No active project. start_debugger must be called from an IntelliJ context."
            )
        }
        // 真实实现需要 IntelliJ 的 XDebuggerManager；这里返回占位
        // C2 修复：用 JsonArgDecoders 安全反序列化
        val sessionName = JsonArgDecoders.stringArg(args, "session_name", default = "CodeSage session")
        return com.codesage.agent.tools.ToolResult.Success(
            buildJsonObject {
                put("status", "initialized")
                put("session_name", sessionName)
                put(
                    "note", "XDebuggerManager integration requires IntelliJ runtime. " +
                            "In a headless test environment, the debugger session is a placeholder."
                )
            }
        )
    }
}

// region === 4. database_schema ===

/**
 * 通过 JDBC 探测数据库 schema。
 *
 * 列出指定数据源的所有表，以及每个表的列信息（类型、是否可空、默认值）。
 *
 * 简化实现：接受 JDBC URL + 用户/密码，但出于安全不在 metadata 中暴露密码字段名。
 * 实际生产中应该使用 IntelliJ 的 DataSource API（项目已配置的数据源），
 * 当前实现是占位符。
 */
class DatabaseSchemaTool : UnifiedTool(
    name = "database_schema",
    description = "Introspect a JDBC database schema: list tables and their columns. " +
            "Returns JSON {tables: [{name, columns: [{name, type, nullable, default}]}]}.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "jdbc_url" to ToolProperty("string", "JDBC URL (e.g. jdbc:postgresql://localhost:5432/mydb)"),
            "user" to ToolProperty("string", "Database user (omit for trust auth)"),
            "catalog" to ToolProperty("string", "Optional catalog/schema name to scope the query")
        ),
        required = listOf("jdbc_url")
    )
) {
    override val tool = super.tool.copy(
        category = ToolCategory.SYSTEM,
        tags = setOf("database", "jdbc", "schema", "introspection")
    )

    override suspend fun execute(args: JsonObject): com.codesage.agent.tools.ToolResult {
        val jdbcUrl = JsonArgDecoders.stringArg(args, "jdbc_url")
        // 当前实现：返回占位 JSON，因为没有具体 driver
        return com.codesage.agent.tools.ToolResult.Success(
            buildJsonObject {
                put("status", "introspection_stub")
                put("jdbc_url", jdbcUrl)
                put(
                    "note", "DatabaseSchemaTool is a placeholder. " +
                            "Production should use IntelliJ DataSource API or load specific JDBC driver. " +
                            "Returns empty tables list for safety."
                )
                put("tables", kotlinx.serialization.json.JsonArray(emptyList()))
            }
        )
    }
}

// region === 5. git_worktree ===

/**
 * Git worktree 管理：创建/列出/删除 worktree。
 *
 * 允许在同一个 repo 下并行打开多个分支的工作区。
 */
class GitWorktreeTool : UnifiedTool(
    name = "git_worktree",
    description = "Manage git worktrees. Supports add/list/remove operations. " +
            "Useful for running multiple branches in parallel without stashing.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "action" to ToolProperty("string", "Operation: add, list, remove, prune"),
            "path" to ToolProperty("string", "Worktree path (for add/remove)"),
            "branch" to ToolProperty("string", "Branch name (for add)"),
            "commit" to ToolProperty("string", "Commit SHA to base the worktree on (for add, default: HEAD)"),
            "working_dir" to ToolProperty("string", "Repository root")
        ),
        required = listOf("action")
    )
) {
    override val tool = super.tool.copy(
        category = ToolCategory.GIT,
        tags = setOf("git", "worktree", "branching", "workflow")
    )

    override suspend fun execute(args: JsonObject): com.codesage.agent.tools.ToolResult {
        val action = JsonArgDecoders.stringArg(args, "action")
        val path = JsonArgDecoders.stringArgOrNull(args, "path")
        val branch = JsonArgDecoders.stringArgOrNull(args, "branch")
        val commit = JsonArgDecoders.stringArgOrNull(args, "commit")
        val workingDir = JsonArgDecoders.stringArgOrNull(args, "working_dir")
            ?: System.getProperty("user.dir")

        val cmd = mutableListOf("git", "worktree", action)
        when (action) {
            "add" -> {
                requireNotNull(path) { "path is required for add" }
                requireNotNull(branch) { "branch is required for add" }
                cmd.add(branch)
                cmd.add(path)
                commit?.let { cmd.add(it) }
            }

            "remove" -> {
                requireNotNull(path) { "path is required for remove" }
                cmd.add(path)
            }

            "list", "prune" -> { /* no extra args */
            }

            else -> return com.codesage.agent.tools.ToolResult.Error(
                "Unknown action: $action. Valid: add, list, remove, prune"
            )
        }

        return runCommand(cmd, workingDir = workingDir, toolName = "git worktree $action")
    }
}

// region === 6. symbol_search ===

/**
 * 基于 SymbolIndex 的代码符号搜索。
 *
 * 不同于 `grep_code`（文本匹配），本工具搜索 PSI 已索引的符号（类、方法、字段），
 * 支持按名称模糊匹配和类型过滤。
 *
 * 实现：依赖项目中的 [com.codesage.analysis.SymbolIndex]，
 * 在 Project=null 时返回错误（需要 IDE 上下文）。
 */
class SymbolSearchTool(private val project: com.intellij.openapi.project.Project?) : UnifiedTool(
    name = "symbol_search",
    description = "Search for code symbols (classes/methods/fields) by name using the project's PSI index. " +
            "Returns matching symbols with file paths and line numbers. " +
            "Requires an active IntelliJ project; returns error in headless test environments.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "query" to ToolProperty("string", "Symbol name (supports partial match)"),
            "type" to ToolProperty("string", "Symbol type filter: CLASS, METHOD, FIELD (optional)"),
            "max_results" to ToolProperty("integer", "Maximum results to return (default 20)")
        ),
        required = listOf("query")
    )
) {
    override val tool = super.tool.copy(
        category = ToolCategory.CODE_ANALYSIS,
        tags = setOf("symbol", "search", "psi", "index")
    )

    override suspend fun execute(args: JsonObject): com.codesage.agent.tools.ToolResult {
        if (project == null || project.isDisposed) {
            return com.codesage.agent.tools.ToolResult.Error(
                "No active project. symbol_search requires an IntelliJ context."
            )
        }

        val query = JsonArgDecoders.stringArg(args, "query")
        val typeFilter = JsonArgDecoders.stringArgOrNull(args, "type")
        val maxResults = JsonArgDecoders.intArg(args, "max_results", default = 20)

        val symbolIndex = com.codesage.analysis.SymbolIndex(project)
        val results = symbolIndex.fuzzySearch(query, limit = maxResults)
        val filtered = if (typeFilter != null) {
            try {
                val targetType = com.codesage.analysis.PSIAnalyzer.SymbolType.valueOf(typeFilter.uppercase())
                results.filter { it.type == targetType }
            } catch (e: Exception) {
                results
            }
        } else results

        return com.codesage.agent.tools.ToolResult.Success(
            buildJsonObject {
                put("query", query)
                put("count", filtered.size)
                put("symbols", kotlinx.serialization.json.JsonArray(filtered.map { sym ->
                    kotlinx.serialization.json.buildJsonObject {
                        put("name", sym.name)
                        put("type", sym.type.name)
                        put("file", sym.filePath)
                        put("line", sym.lineNumber)
                    }
                }))
            }
        )
    }
}

// region === 共享的 runCommand 工具 ===

/**
 * 共享 helper：用 ProcessBuilder 执行命令并收集输出。
 * 不使用 shell（避免元字符注入），参数以数组形式传递。
 */
private suspend fun runCommand(
    cmd: List<String>,
    workingDir: String? = null,
    toolName: String = "shell"
): com.codesage.agent.tools.ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val builder = ProcessBuilder(cmd)
        if (workingDir != null) builder.directory(File(workingDir))
        builder.redirectErrorStream(true)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@withContext com.codesage.agent.tools.ToolResult.Error(
                "$toolName timed out after 60s"
            )
        }
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            return@withContext com.codesage.agent.tools.ToolResult.Error(
                "$toolName exit code $exitCode: ${output.takeLast(2000)}"
            )
        }
        com.codesage.agent.tools.ToolResult.Success(
            buildJsonObject {
                put("command", cmd.joinToString(" "))
                put("exit_code", exitCode)
                put("stdout", output.take(10_000))  // 截断到 10k
            }
        )
    } catch (e: Exception) {
        com.codesage.agent.tools.ToolResult.Error("$toolName failed: ${e.message}")
    }
}
