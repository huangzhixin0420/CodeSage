package com.codesage.mcp.server

import com.codesage.mcp.client.McpTool
import com.codesage.mcp.transport.MCPServerConfig

/**
 * 6.11.1 / 6.11.2 MCP 工具治理过滤器
 *
 * 在工具进入 SkillRegistry / LLM 视野之前完成：
 * 1. 按 per-server 白名单 / 黑名单过滤（支持 `*`、`?` 通配符）。
 * 2. 按 maxTools 数量上限截断，仅暴露优先级靠前的工具。
 *
 * 被过滤掉的工具不会出现在 LLM 的 tools 列表中，但可通过 `mcp_tool_search`
 * 动态查询，从而在不污染上下文的前提下保留发现能力。
 */
object McpToolFilter {

    /**
     * 对工具列表应用权限规则与数量上限。
     */
    fun apply(tools: List<McpTool>, config: MCPServerConfig): List<McpTool> {
        val afterRules = tools.filter { isAllowed(it.name, config.allowedTools, config.deniedTools) }
        val limit = config.maxTools.coerceAtLeast(0)
        return if (limit == 0) afterRules else afterRules.take(limit)
    }

    /**
     * 判断单个工具名是否通过 allow/deny 规则。
     *
     * 规则优先级：deny > allow。
     * - 若命中任意 deny 模式，直接拒绝。
     * - 若 allowed 为空，则允许通过。
     * - 若 allowed 非空，则必须命中至少一个 allow 模式才允许通过。
     */
    fun isAllowed(toolName: String, allowed: List<String>, denied: List<String>): Boolean {
        if (denied.any { matches(toolName, it) }) return false
        if (allowed.isEmpty()) return true
        return allowed.any { matches(toolName, it) }
    }

    /**
     * 将通配符模式转换为忽略大小写的正则并匹配。
     * 支持：`*` 匹配任意字符序列，`?` 匹配单个字符。
     */
    fun matches(toolName: String, pattern: String): Boolean {
        val regex = pattern
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.matches(toolName)
    }
}
