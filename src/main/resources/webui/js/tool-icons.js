/**
 * tool-icons.js — 工具语义图标/颜色映射表
 * =========================================
 *
 * 映射规则来自 FRONTEND_OPTIMIZATION_PROPOSAL_2026_06.md 5.3 节。
 */

export const TOOL_ICON_MAP = [
  {
    patterns: ["read_file", "view_file", "read", "view"],
    icon: "fa-file",
    svg: "fileRead",
    emoji: "📄",
    color: "blue",
    label: "读取",
  },
  {
    patterns: [
      "edit_file",
      "write_file",
      "apply_diff",
      "apply_patch",
      "multi_edit",
      "create_file",
      "edit",
      "write",
    ],
    icon: "fa-pen",
    svg: "fileWrite",
    emoji: "✏️",
    color: "green",
    label: "写入",
  },
  {
    patterns: [
      "run_command",
      "exec_shell",
      "run_shell",
      "bash",
      "execute",
      "command",
      "shell",
    ],
    icon: "fa-bolt",
    svg: "command",
    emoji: "⚡",
    color: "yellow",
    label: "执行",
  },
  {
    patterns: ["search_code", "grep", "find_files", "search", "find"],
    icon: "fa-magnifying-glass",
    svg: "search",
    emoji: "🔍",
    color: "purple",
    label: "搜索",
  },
  {
    patterns: ["mcp__"],
    icon: "fa-plug",
    svg: "mcp",
    emoji: "🔌",
    color: "orange",
    label: "MCP",
    prefixMatch: true,
  },
  {
    patterns: ["delegate_task", "subagent", "sub_agent"],
    icon: "fa-robot",
    svg: "subagent",
    emoji: "🤖",
    color: "teal",
    label: "子任务",
  },
];

export function getToolIconMeta(toolName) {
  if (!toolName) return TOOL_ICON_MAP[0];
  const lower = String(toolName).toLowerCase();
  for (const meta of TOOL_ICON_MAP) {
    if (meta.prefixMatch) {
      if (meta.patterns.some((p) => lower.startsWith(p.toLowerCase()))) {
        return meta;
      }
    } else if (
      meta.patterns.some(
        (p) => lower === p.toLowerCase() || lower.includes(p.toLowerCase()),
      )
    ) {
      return meta;
    }
  }
  return {
    icon: "fa-gear",
    svg: "fileRead",
    emoji: "⚙️",
    color: "gray",
    label: "工具",
  };
}

export function getToolIconClass(toolName) {
  return getToolIconMeta(toolName).icon;
}

export function getToolSvgName(toolName) {
  return getToolIconMeta(toolName).svg || "fileRead";
}

export function getToolEmoji(toolName) {
  return getToolIconMeta(toolName).emoji;
}

export function getToolColorVar(toolName) {
  const color = getToolIconMeta(toolName).color;
  const map = {
    blue: "var(--info)",
    green: "var(--success)",
    yellow: "var(--warning)",
    purple: "var(--accent)",
    orange: "var(--warning)",
    teal: "var(--info)",
    gray: "var(--fg-tertiary)",
  };
  return map[color] || "var(--fg-tertiary)";
}
