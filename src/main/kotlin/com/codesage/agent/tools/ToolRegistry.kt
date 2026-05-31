package com.codesage.agent.tools

import com.codesage.analysis.CodeInsightTools
import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import com.codesage.shared.utils.Logger

/**
 * 工具注册中心
 * 管理所有可向 AI 暴露的工具定义
 */
class ToolRegistry {
    private val logger = Logger.getLogger<ToolRegistry>()
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
        logger.info("Registered tool: ${tool.name}")
    }

    fun unregister(toolName: String) {
        tools.remove(toolName)
    }

    fun get(toolName: String): Tool? = tools[toolName]

    fun getAllTools(): List<Tool> = tools.values.toList()

    fun clear() = tools.clear()

    companion object {
        /**
         * 创建默认的工具注册表，包含所有 IDE 内置工具
         */
        fun createDefault(): ToolRegistry {
            return ToolRegistry().apply {
                register(readFileTool())
                register(writeFileTool())
                register(listDirectoryTool())
                register(searchCodeTool())
                register(runCommandTool())
                register(getProjectStructureTool())
                register(findFileTool())
                register(grepCodeTool())
                register(getFileInfoTool())
                register(readMultipleFilesTool())
                register(editFileTool())
                register(deleteFileTool())
                register(copyFileTool())
                register(moveFileTool())
                register(delegateTaskTool())
                // Git 工具
                register(gitStatusTool())
                register(gitDiffTool())
                register(gitLogTool())
                register(gitBranchTool())
                // Shell / HTTP / 数据处理工具
                register(execShellTool())
                register(httpRequestTool())
                register(parseJsonTool())
                register(encodeBase64Tool())
                register(decodeBase64Tool())
                register(formatJsonTool())
                register(hashMd5Tool())
                register(hashSha256Tool())
                // 注册代码洞察工具
                CodeInsightTools.getAllTools().forEach { register(it) }
            }
        }
    }
}

// === 工具定义工厂函数 ===

private fun readFileTool() = Tool(
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

private fun writeFileTool() = Tool(
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

private fun listDirectoryTool() = Tool(
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

private fun searchCodeTool() = Tool(
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

private fun runCommandTool() = Tool(
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

private fun getProjectStructureTool() = Tool(
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


private fun delegateTaskTool() = Tool(
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


private fun findFileTool() = Tool(
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

private fun grepCodeTool() = Tool(
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

private fun getFileInfoTool() = Tool(
    name = "get_file_info",
    description = "Get metadata about a file: size, type, extension, modification time.",
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty("string", "File path")
        ),
        required = listOf("path")
    )
)

private fun readMultipleFilesTool() = Tool(
    name = "read_multiple_files",
    description = "Read contents of multiple files at once. More efficient than multiple read_file calls.",
    parameters = ToolParameters(
        properties = mapOf(
            "paths" to ToolProperty("array", "List of file paths to read")
        ),
        required = listOf("paths")
    )
)

private fun editFileTool() = Tool(
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

private fun deleteFileTool() = Tool(
    name = "delete_file",
    description = "Delete a file or directory. Use with caution.",
    parameters = ToolParameters(
        properties = mapOf(
            "path" to ToolProperty("string", "File or directory path to delete")
        ),
        required = listOf("path")
    )
)

private fun copyFileTool() = Tool(
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

private fun moveFileTool() = Tool(
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

private fun gitStatusTool() = Tool(
    name = "git_status",
    description = "查看 Git 仓库状态，返回当前分支和变更文件列表。",
    parameters = ToolParameters(
        properties = mapOf(
            "working_dir" to ToolProperty("string", "工作目录路径，默认为项目根目录")
        ),
        required = listOf()
    )
)

private fun gitDiffTool() = Tool(
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

private fun gitLogTool() = Tool(
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

private fun gitBranchTool() = Tool(
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

private fun execShellTool() = Tool(
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

private fun httpRequestTool() = Tool(
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

private fun parseJsonTool() = Tool(
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

private fun encodeBase64Tool() = Tool(
    name = "encode_base64",
    description = "将字符串编码为 Base64。",
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要编码的字符串")
        ),
        required = listOf("input")
    )
)

private fun decodeBase64Tool() = Tool(
    name = "decode_base64",
    description = "将 Base64 字符串解码为普通字符串。",
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要解码的 Base64 字符串")
        ),
        required = listOf("input")
    )
)

private fun formatJsonTool() = Tool(
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

private fun hashMd5Tool() = Tool(
    name = "hash_md5",
    description = "计算字符串的 MD5 哈希值。",
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要计算哈希的字符串")
        ),
        required = listOf("input")
    )
)

private fun hashSha256Tool() = Tool(
    name = "hash_sha256",
    description = "计算字符串的 SHA-256 哈希值。",
    parameters = ToolParameters(
        properties = mapOf(
            "input" to ToolProperty("string", "要计算哈希的字符串")
        ),
        required = listOf("input")
    )
)
