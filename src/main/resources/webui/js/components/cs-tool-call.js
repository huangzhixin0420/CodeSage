/**
 * cs-tool-call 组件 — 工具调用卡片
 *
 * 设计文档 §7:
 *   - 入参 / 出参 / 状态机 / diff
 *   - 7 种 kind: text / code / diff / command / json / list / error / subagent
 *
 * 数据契约(来自设计文档 §7.5):
 *   {
 *     type: "tool_call_start",
 *     turnId, toolCallId,
 *     toolCall: { name, serverName, arguments: {...} },
 *     summary: "Edit src/main.kt"
 *   }
 *   {
 *     type: "tool_call_result",
 *     turnId, toolCallId, success, result: { kind, ... }
 *   }
 */

import { escapeHtml, truncate, formatDuration } from "../utils.js";

const STATUS_META = {
  queued: { icon: "fas fa-circle", cls: "tool-status-queued" },
  running: { icon: "fas fa-spinner spin", cls: "tool-status-running" },
  completed: { icon: "fas fa-check", cls: "tool-status-completed" },
  failed: { icon: "fas fa-times", cls: "tool-status-failed" },
  confirm: { icon: "fas fa-exclamation", cls: "tool-status-confirm" },
  stopped: { icon: "fas fa-stop", cls: "tool-status-stopped" },
};

const KIND_LABEL = {
  text: "输出",
  code: "代码",
  diff: "变更",
  command: "命令",
  json: "JSON",
  list: "列表",
  error: "错误",
  subagent: "子 Agent",
};

/**
 * 工具 watchdog 超时表(毫秒)
 *
 * 2026-06 设计:
 *  - 默认 30s — 适配"read_file/edit_file/grep_code"等本地工具
 *  - subagent: 10 分钟 — sub-agent 任务可能跑很久(多步工具调用 + LLM 推理),
 *    30s 会误伤,5min 也偏紧
 *  - mcp__* (MCP 工具): 5 分钟 — 跨网络,可能慢
 *
 * 注: 优先按 toolName 精确匹配,再按前缀匹配(mcp__),最后 fallback 默认值。
 * 以后想加新规则: 在 TOOL_TIMEOUTS_MS 加一行,或扩展 getToolTimeoutMs。
 */
const TOOL_TIMEOUTS_MS = {
  default: 30_000,
  // delegate_task 工具自身执行时间 = sub-agent 整体运行时间,可能很慢
  delegate_task: 10 * 60_000,
  // subagent kind 卡片: SubAgentStart → SubAgentComplete 之间的时间窗
  subagent: 10 * 60_000,
};
const MCP_TOOL_TIMEOUT_MS = 5 * 60_000;

function getToolTimeoutMs(toolName) {
  if (TOOL_TIMEOUTS_MS[toolName] !== undefined) return TOOL_TIMEOUTS_MS[toolName];
  if (toolName && toolName.startsWith("mcp__")) return MCP_TOOL_TIMEOUT_MS;
  return TOOL_TIMEOUTS_MS.default;
}

/**
 * 尝试把字符串按 JSON 解析并 pretty-print;失败/不是 JSON 就原样返回。
 * 后端 ToolResult.Success(JsonObject) 序列化成字符串后,前端拿到的是
 * 一坨没缩进的 JSON,直接渲染到 <pre> 里就是一整行,浏览体验差。
 */
function tryPrettyJson(str) {
  if (typeof str !== "string") return str;
  const t = str.trim();
  if (!t.startsWith("{") && !t.startsWith("[")) return str;
  try {
    return JSON.stringify(JSON.parse(t), null, 2);
  } catch {
    return str;
  }
}

/**
 * 命令类工具的 tool name 集合 —— 这些工具的 result.data 有
 * {stdout, stderr, exit_code, ...} 结构,值得单独渲染。
 */
const COMMAND_TOOL_NAMES = new Set(["run_command", "exec_shell", "run_tests"]);

/**
 * 解析后端 ToolResult 序列化的字符串,按 tool 名称 / data 形态路由成
 * _renderResult 期望的 {kind, ...} 结构化对象。失败 -> null(caller 兜底)。
 *
 *   run_command : {success:true, data:{stdout,stderr,exit_code}}   -> command
 *   read_file   : {success:true, data:{content,truncated}}         -> json
 *   list_dir    : {success:true, data:["a","b"]}                   -> list
 *   错误        : {success:false, error:"..."}                    -> null (caller 走 error)
 */
/**
 * 解析后端 ToolResult.Failure 序列化的 JSON 错误字符串,
 * 拆出主消息 (error) + 结构化 context (reason/tool/...)。
 *
 * 典型输入 (guardrails 拦截):
 *   '{"success":false,"error":"...","reason":"CONFIRMATION_DENIED","tool":"x"}'
 *   -> { message: "...", context: { reason:"CONFIRMATION_DENIED", tool:"x" } }
 *
 * 不匹配 (plain text / 非 JSON) -> null
 */
function parseToolError(str) {
  if (typeof str !== "string") return null;
  const trimmed = str.trim();
  if (!trimmed.startsWith("{")) return null;
  let parsed;
  try { parsed = JSON.parse(trimmed); } catch { return null; }
  if (!parsed || typeof parsed !== "object") return null;
  if (parsed.success !== false) return null;
  const message = parsed.error || parsed.message;
  if (!message || typeof message !== "string") return null;
  const context = {};
  for (const k of Object.keys(parsed)) {
    if (k === "success" || k === "error" || k === "message") continue;
    const v = parsed[k];
    if (v == null) continue;
    // 数组/对象 pretty 一下,primitive 直接 toString
    context[k] = typeof v === "object" ? JSON.stringify(v, null, 2) : String(v);
  }
  return { message, context };
}

function parseToolResultWrapper(str, toolName, args) {
  if (typeof str !== "string") return null;
  if (typeof str !== "string") return null;
  const trimmed = str.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null;
  let parsed;
  try { parsed = JSON.parse(trimmed); } catch { return null; }
  if (!parsed || typeof parsed !== "object") return null;

  // 剥 {success, data} 包装: 允许 success/error/reason/tool/warning 这些
  // 框架 key 共存;但只要出现业务字段(如 {users:[...]} )就当作非包装。
  // 注意: {success:false, error:"..."} 没有 data 字段,也算包装(失败包装),
  // 这样 caller 看到 data==null 就走 error 兜底,不会渲染一个 success:false
  // 的奇怪 json 块。
  const WRAPPER_KEYS = new Set(["success", "data", "error", "reason", "tool", "warning"]);
  const hasWrapper =
    "success" in parsed &&
    (
      "data" in parsed ||
      (parsed.success === false && "error" in parsed)
    ) &&
    Object.keys(parsed).every((k) => WRAPPER_KEYS.has(k));
  const data = hasWrapper ? parsed.data : parsed;

  if (data == null) return null;

  // 1) 命令类工具 -> command kind
  if (
    toolName &&
    COMMAND_TOOL_NAMES.has(toolName) &&
    typeof data === "object" &&
    !Array.isArray(data) &&
    "stdout" in data
  ) {
    const argCmd =
      (args && (args.command || args.cmd || args.shell_command)) || "";
    const exitCode = data.exit_code ?? data.exitCode ?? 0;
    const stdout = data.stdout != null ? String(data.stdout) : "";
    const stderr = data.stderr != null ? String(data.stderr) : "";
    const durationMs = data.duration_ms ?? data.durationMs ?? null;
    const truncated = !!data.truncated;
    const parts = [];
    if (exitCode !== 0) parts.push(`exit ${exitCode}`);
    if (truncated) parts.push("输出被截断");
    if (durationMs != null) parts.push(`${durationMs}ms`);
    return {
      kind: "command",
      command: argCmd,
      stdout,
      stderr,
      exitCode,
      summary: parts.join(" · "),
    };
  }

  // 2) 普通 object -> json kind
  if (typeof data === "object" && !Array.isArray(data)) {
    return { kind: "json", content: data };
  }

  // 3) 数组 -> list kind
  if (Array.isArray(data)) {
    return { kind: "list", items: data.map((i) => String(i)) };
  }

  // 4) string primitive -> text kind
  if (typeof data === "string") {
    return { kind: "text", content: data };
  }
  return null;
}

export class ToolCall {
  constructor(opts) {
    this.toolCallId = opts.toolCallId;
    this.turnId = opts.turnId;
    this.name = opts.name || "tool";
    this.serverName = opts.serverName || null;
    this.summary = opts.summary || "";
    this.arguments = opts.arguments || null;
    this.kind = opts.kind || null;
    this.icon = opts.icon || null; // 工具专属图标 (来自 Kotlin Tool schema)
    this.errorContext = null; // P5.7: 失败时的结构化 context(reason/tool/...)
    this.startTime = Date.now();
    this.endTime = null;
    this.status = "running";
    this.result = null;
    this.error = null;

    this.el = document.createElement("div");
    this.el.className = "tool-card";
    this.el.setAttribute("data-cs-tool", this.toolCallId);
    this._renderHeader();
    this._renderBody();
    console.log(`[cs-tool-call] created: toolId=${this.toolCallId}, name=${this.name}, turnId=${this.turnId}, timeoutMs=${getToolTimeoutMs(this.name)}`);
    // 启动超时看门狗(30s 还没 complete/fail/stop 就 auto-stop;2026-06 从 5min 降到 30s,
    // 因为后端 EventConsumer 已修复 Terminal 事件必送达,5min 太宽松掩盖问题)
    this._startWatchdog();
  }

  _renderHeader() {
    const meta = STATUS_META[this.status];
    const isMcp = this.name.startsWith("mcp__");
    // 工具专属图标: 优先用后端传来的 icon, MCP 用 mcp 图标
    let iconHtml = "";
    if (this.icon) {
      iconHtml = `<i class="${escapeHtml(this.icon)}" style="margin-right:6px;font-size:13px;color:var(--fg-2);width:14px;text-align:center;"></i>`;
    } else if (isMcp) {
      iconHtml = `<i class="fas fa-plug" style="margin-right:6px;font-size:12px;color:var(--accent);width:14px;text-align:center;"></i>`;
    } else {
      // fallback: 用 tool kind 推断 (read_file → fa-file, edit_file → fa-pen 等)
      iconHtml = `<i class="${escapeHtml(this._defaultIconFor(this.name))}" style="margin-right:6px;font-size:12px;color:var(--fg-2);width:14px;text-align:center;"></i>`;
    }

    const displayName = isMcp
      ? `<span style="color:var(--accent);font-size:11px;background:var(--accent-soft);padding:1px 6px;border-radius:4px;margin-right:6px;">MCP: ${escapeHtml(this.serverName || "server")}</span>${escapeHtml(this.name.replace(/^mcp__/, ""))}`
      : escapeHtml(this.name);

    const headerHtml = `
            <div class="tool-card-header" data-cs-role="header">
                <div class="tool-status-icon ${meta.cls}"><i class="${meta.icon}"></i></div>
                ${iconHtml}
                <span class="tool-name">${displayName}</span>
                <span class="tool-summary" data-cs-role="summary">${escapeHtml(truncate(this.summary, 60))}</span>
                <span class="tool-time" data-cs-role="time" style="font-size:11px;color:var(--fg-3);font-variant-numeric:tabular-nums;"></span>
                <i class="fas fa-chevron-down" style="font-size:10px;color:var(--fg-3);margin-left:auto;transition:transform 200ms;" data-cs-role="chevron"></i>
            </div>
        `;
    // Reuse or create header element
    let headerEl = this.el.querySelector('[data-cs-role="header"]');
    if (headerEl) {
      headerEl.outerHTML = headerHtml;
    } else {
      this.el.insertAdjacentHTML("afterbegin", headerHtml);
    }
    this.el
      .querySelector('[data-cs-role="header"]')
      .addEventListener("click", () => this.toggle());
  }

  _defaultIconFor(name) {
    if (!name) return "fas fa-cog";
    if (/read|view|cat|get/i.test(name)) return "fas fa-eye";
    if (/write|create|save|put/i.test(name)) return "fas fa-file-circle-plus";
    if (/edit|patch|modify|update/i.test(name)) return "fas fa-pen";
    if (/delete|remove|rm/i.test(name)) return "fas fa-trash";
    if (/search|find|grep|query/i.test(name)) return "fas fa-magnifying-glass";
    if (/run|exec|shell|bash|command/i.test(name)) return "fas fa-terminal";
    if (/git/i.test(name)) return "fab fa-git-alt";
    if (/http|fetch|api|request/i.test(name)) return "fas fa-globe";
    return "fas fa-cog";
  }

  _renderBody() {
    let body = this.el.querySelector(".tool-content");
    if (!body) {
      body = document.createElement("div");
      body.className = "tool-content";
      this.el.appendChild(body);
    }
    body.innerHTML = this._buildBodyHtml();
  }

  _buildBodyHtml() {
    const parts = [];

    if (this.arguments && Object.keys(this.arguments).length > 0) {
      parts.push(this._renderArguments());
    }

    if (this.status === "running") {
      parts.push(
        `<div style="padding:var(--space-3) var(--space-3) 0;color:var(--fg-2);font-size:12px;"><i class="fas fa-spinner spin"></i>&nbsp;执行中...</div>`,
      );
    } else if (this.status === "failed") {
      parts.push(this._renderErrorBlock("执行失败", "fa-times-circle", "error"));
    } else if (this.status === "stopped") {
      // 跟 failed 区分:stopped 是“被中断/超时”,failed 是“后端报错误”
      parts.push(
        `<div class="inline-alert warning" style="margin:var(--space-2) var(--space-3);"><i class="fas fa-stop-circle alert-icon"></i><div class="alert-body"><div class="alert-title">工具未完成</div><div class="alert-message">${escapeHtml(this.error || "本轮未收到 complete/error 事件,工具被中断")}</div></div></div>`,
      );
    } else if (this.status === "completed" && this.result) {
      parts.push(this._renderResult());
    }

    // Actions
    parts.push(this._renderActions());

    return parts.join("");
  }

  _renderArguments() {
    const args = this.arguments;
    const json = JSON.stringify(args, null, 2);
    return `
            <div style="padding:var(--space-3) var(--space-3) 0;">
                <div style="font-size:11px;font-weight:600;color:var(--fg-2);text-transform:uppercase;letter-spacing:0.4px;margin-bottom:var(--space-1);">入参</div>
                <pre style="background:var(--bg-code);color:var(--code-fg);padding:var(--space-2);border-radius:var(--radius-sm);font-size:11px;font-family:var(--font-mono);max-height:140px;overflow:auto;margin:0;">${escapeHtml(json)}</pre>
            </div>
        `;
  }

  /**
   * 渲染工具失败/停止的统一错误块。
   * - title: "执行失败" / "工具未完成" 等
   * - iconClass: fas fa-* icon class
   * - variant: "error" (红) / "warning" (黄)
   *
   * 支持两类错误内容:
   *   1) this.error 是纯文本 (e.g. "工具执行超时(...)" / "Connection refused")
   *   2) this.error 是主消息 + this.errorContext 是结构化字段
   *      (e.g. { reason: "CONFIRMATION_DENIED", tool: "get_project_stats" })
   *      context 渲染成 key/value 列表,放在主消息下方,避免一坨 JSON
   *      渲染成一整行没法看。
   */
  _renderErrorBlock(title, iconClass, variant) {
    const msg = escapeHtml(this.error || "未知错误");
    let ctxHtml = "";
    if (
      this.errorContext &&
      typeof this.errorContext === "object" &&
      Object.keys(this.errorContext).length > 0
    ) {
      const rows = Object.entries(this.errorContext)
        .map(
          ([k, v]) =>
            `<div class="cs-error-context-row"><span class="cs-error-key">${escapeHtml(
              k,
            )}</span><span class="cs-error-value">${escapeHtml(
              String(v),
            )}</span></div>`,
        )
        .join("");
      ctxHtml = `<div class="cs-error-context">${rows}</div>`;
    }
    return `
            <div class="inline-alert ${variant}" style="margin:var(--space-2) var(--space-3);">
                <i class="${iconClass} alert-icon"></i>
                <div class="alert-body">
                    <div class="alert-title">${escapeHtml(title)}</div>
                    <div class="alert-message">${msg}</div>
                    ${ctxHtml}
                </div>
            </div>
        `;
  }

  _renderResult() {
    const r = this.result;
    if (!r) return "";
    const kind = r.kind || "text";
    const label = KIND_LABEL[kind] || kind;

    let body = "";
    if (kind === "diff" && Array.isArray(r.diff)) {
      body = this._renderDiff(r.diff, r.summary);
    } else if (kind === "command") {
      body = this._renderCommand(r);
    } else if (kind === "code") {
      body = this._renderCodeLike(r.content || r.text || "", "code", r.language);
    } else if (kind === "text") {
      // P5.6: text kind 也尝试 pretty JSON(后端可能丢个 JSON 字符串过来)
      const text = r.content || r.text || "";
      const pretty = tryPrettyJson(text);
      body = this._renderCodeLike(pretty, "text", r.language);
    } else if (kind === "json") {
      const raw =
        typeof r.content === "string" ? r.content : JSON.stringify(r.content);
      const display = tryPrettyJson(raw);
      body = `<pre style="background:var(--bg-code);color:var(--code-fg);padding:var(--space-2);border-radius:var(--radius-sm);font-size:11px;font-family:var(--font-mono);max-height:240px;overflow:auto;margin:0;">${escapeHtml(display)}</pre>`;
    } else if (kind === "list" && Array.isArray(r.items)) {
      body = `<ul style="margin:0;padding-left:var(--space-4);font-size:12px;">${r.items.map((i) => `<li>${escapeHtml(String(i))}</li>`).join("")}</ul>`;
    } else if (kind === "error") {
      body = `<div class="inline-alert error"><i class="fas fa-times-circle alert-icon"></i><div class="alert-body"><div class="alert-message">${escapeHtml(r.message || "未知错误")}</div></div></div>`;
    } else if (kind === "subagent") {
      body = this._renderSubagent(r);
    } else {
      body = `<pre style="background:var(--bg-code);color:var(--code-fg);padding:var(--space-2);border-radius:var(--radius-sm);font-size:11px;font-family:var(--font-mono);max-height:240px;overflow:auto;margin:0;">${escapeHtml(JSON.stringify(r, null, 2))}</pre>`;
    }

    return `
            <div style="padding:var(--space-3) var(--space-3) 0;">
                <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:var(--space-1);">
                    <div style="font-size:11px;font-weight:600;color:var(--fg-2);text-transform:uppercase;letter-spacing:0.4px;">${label}</div>
                    ${r.summary ? `<div style="font-size:11px;color:var(--fg-2);">${escapeHtml(r.summary)}</div>` : ""}
                </div>
                ${body}
            </div>
        `;
  }

  _renderDiff(diffLines, summary) {
    const lines = diffLines
      .map((d) => {
        const cls =
          d.type === "add" ? "add" : d.type === "remove" ? "remove" : "";
        const marker = d.type === "add" ? "+" : d.type === "remove" ? "-" : " ";
        const gutterOld = d.oldLine != null ? d.oldLine : "";
        const gutterNew = d.newLine != null ? d.newLine : "";
        return `<div class="diff-line ${cls}">
                <span class="diff-gutter">${gutterOld}</span>
                <span class="diff-marker">${marker}</span>
                <span class="diff-text">${escapeHtml(d.text || "")}</span>
            </div>`;
      })
      .join("");
    return `
            <div class="diff-view">
                ${summary ? `<div class="diff-header"><span class="diff-summary">${escapeHtml(summary)}</span></div>` : ""}
                <div style="font-family:var(--font-mono);font-size:11px;">${lines}</div>
            </div>
        `;
  }

  _renderCommand(r) {
    // r 结构(由 parseToolResultWrapper 路由产出):
    //   { kind:"command", command, stdout, stderr, exitCode, summary }
    const exitCode = r.exitCode != null ? r.exitCode : 0;
    const stdout = r.stdout != null ? r.stdout : "";
    const stderr = r.stderr != null ? r.stderr : "";
    const command = r.command || "";
    const ok = exitCode === 0;
    const exitColor = ok ? "var(--success)" : "var(--error)";

    // stdout/stderr 本身若是 JSON 字符串,尝试 pretty 一下
    const stdoutPretty = tryPrettyJson(stdout);
    const stderrPretty = tryPrettyJson(stderr);

    const stdoutLines = stdoutPretty ? stdoutPretty.split("\n").length : 0;
    const stdoutBytes = stdoutPretty ? stdoutPretty.length : 0;
    const stderrLines = stderrPretty ? stderrPretty.split("\n").length : 0;
    const stderrBytes = stderrPretty ? stderrPretty.length : 0;
    const hasStderr = stderr.length > 0;

    return `
            <div class="cs-command-result" style="background:var(--bg-code);border-radius:var(--radius-sm);overflow:hidden;border:1px solid rgba(255,255,255,0.06);">
                <div class="cs-command-header" style="display:flex;align-items:center;gap:var(--space-2);padding:var(--space-2) var(--space-3);background:rgba(255,255,255,0.04);border-bottom:1px solid rgba(255,255,255,0.06);font-family:var(--font-mono);font-size:11px;">
                    <span style="color:var(--accent);">$</span>
                    <span style="flex:1;color:#a0a0b0;word-break:break-all;white-space:pre-wrap;">${escapeHtml(command)}</span>
                    <span style="color:${exitColor};font-weight:600;white-space:nowrap;">${ok ? "exit 0" : `exit ${exitCode}`}</span>
                </div>
                <div class="cs-command-stdout" style="padding:var(--space-2) var(--space-3);">
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:var(--space-1);">
                        <span style="font-size:10px;font-weight:600;color:var(--fg-2);text-transform:uppercase;letter-spacing:0.4px;">stdout · ${stdoutLines} 行 · ${stdoutBytes} 字符</span>
                        <button class="turn-action-btn" data-cs-action="copy-command-stdout" style="font-size:10px;padding:2px 6px;">
                            <i class="fas fa-copy"></i>&nbsp;复制
                        </button>
                    </div>
                    <pre class="cs-command-stdout-body" data-cs-role="command-stdout" style="margin:0;padding:var(--space-2);color:var(--code-fg);font-family:var(--font-mono);font-size:11px;max-height:240px;overflow:auto;white-space:pre-wrap;line-height:1.5;background:rgba(0,0,0,0.2);border-radius:var(--radius-sm);">${escapeHtml(stdoutPretty) || "<span style=\"color:var(--fg-3);\">(空)</span>"}</pre>
                </div>
                ${
                  hasStderr
                    ? `<div class="cs-command-stderr" style="padding:var(--space-2) var(--space-3);background:rgba(255,80,80,0.05);border-top:1px solid rgba(255,255,255,0.06);">
                            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:var(--space-1);">
                                <span style="font-size:10px;font-weight:600;color:var(--error);text-transform:uppercase;letter-spacing:0.4px;">stderr · ${stderrLines} 行 · ${stderrBytes} 字符</span>
                                <button class="turn-action-btn" data-cs-action="copy-command-stderr" style="font-size:10px;padding:2px 6px;">
                                    <i class="fas fa-copy"></i>&nbsp;复制
                                </button>
                            </div>
                            <pre class="cs-command-stderr-body" data-cs-role="command-stderr" style="margin:0;padding:var(--space-2);color:var(--error);font-family:var(--font-mono);font-size:11px;max-height:240px;overflow:auto;white-space:pre-wrap;line-height:1.5;background:rgba(0,0,0,0.2);border-radius:var(--radius-sm);">${escapeHtml(stderrPretty)}</pre>
                        </div>`
                    : ""
                }
            </div>
        `;
  }

  _renderCodeLike(text, kind, language) {
    const lang = language || (kind === "code" ? "text" : "");
    return `
            <div class="code-block">
                ${lang ? `<div class="code-block-header"><span class="code-lang">${escapeHtml(lang)}</span></div>` : ""}
                <pre style="margin:0;"><code class="language-${escapeHtml(lang)}">${escapeHtml(text)}</code></pre>
            </div>
        `;
  }

  /**
   * 渲染子 Agent 结果(Plan A 增强版)
   * - 顶部元信息:toolset / task / 耗时 / 状态
   * - 中部输出预览(默认折叠,点击展开)
   */
  _renderSubagent(r) {
    const sa = r.subagent || {};
    const toolset = sa.toolset || "—";
    const task = sa.task || r.sessionId || "—";
    const elapsed =
      sa.elapsedMs != null ? this._formatDuration(sa.elapsedMs) : "—";
    const success = sa.success !== false;
    const output = sa.output || "";
    const preview = output.length > 240 ? output.slice(0, 240) + "…" : output;
    const statusIcon = success
      ? '<i class="fas fa-check-circle" style="color:var(--success);"></i>'
      : '<i class="fas fa-times-circle" style="color:var(--error);"></i>';
    return `
            <div class="cs-subagent-result">
                <div class="cs-subagent-meta">
                    <div class="cs-subagent-meta-row">
                        <span class="cs-subagent-label">Toolset</span>
                        <span class="cs-subagent-value"><code>${escapeHtml(toolset)}</code></span>
                    </div>
                    <div class="cs-subagent-meta-row">
                        <span class="cs-subagent-label">Task</span>
                        <span class="cs-subagent-value">${escapeHtml(task)}</span>
                    </div>
                    <div class="cs-subagent-meta-row">
                        <span class="cs-subagent-label">状态</span>
                        <span class="cs-subagent-value">${statusIcon} ${success ? "已完成" : "失败"}</span>
                    </div>
                    <div class="cs-subagent-meta-row">
                        <span class="cs-subagent-label">耗时</span>
                        <span class="cs-subagent-value">${escapeHtml(elapsed)}</span>
                    </div>
                </div>
                ${
                  output
                    ? `<div class="cs-subagent-output">
                            <div class="cs-subagent-output-preview">${escapeHtml(preview)}</div>
                            ${
                              output.length > 240
                                ? `<button class="cs-button size-sm variant-ghost" data-cs-action="expand-subagent" style="margin-top:6px;">
                                        <i class="fas fa-expand"></i>&nbsp;查看完整输出
                                    </button>
                                    <div class="cs-subagent-output-full" style="display:none;margin-top:8px;padding:var(--space-2);background:var(--bg-code);border-radius:var(--radius-sm);font-family:var(--font-mono);font-size:11px;white-space:pre-wrap;max-height:300px;overflow:auto;">${escapeHtml(output)}</div>
                                    <button class="cs-button size-sm variant-ghost" data-cs-action="collapse-subagent" style="display:none;margin-top:6px;">
                                        <i class="fas fa-compress"></i>&nbsp;收起
                                    </button>`
                                : ""
                            }
                        </div>`
                    : ""
                }
            </div>
        `;
  }

  _formatDuration(ms) {
    if (ms < 1000) return `${ms}ms`;
    const s = ms / 1000;
    if (s < 60) return `${s.toFixed(1)}s`;
    const m = Math.floor(s / 60);
    const rs = Math.floor(s % 60);
    return `${m}m${rs}s`;
  }

  _renderActions() {
    const isEdit = [
      "edit_file",
      "write_file",
      "apply_patch",
      "create_file",
    ].includes(this.name);
    return `
            <div class="turn-actions visible" style="padding:var(--space-2) var(--space-3);">
                <button class="turn-action-btn" data-cs-action="copy"><i class="fas fa-copy"></i>&nbsp;复制</button>
                ${isEdit ? `<button class="turn-action-btn" data-cs-action="apply"><i class="fas fa-file-import"></i>&nbsp;应用到编辑器</button>` : ""}
                ${this.kind === "diff" || isEdit ? `<button class="turn-action-btn" data-cs-action="reject"><i class="fas fa-undo"></i>&nbsp;回退</button>` : ""}
            </div>
        `;
  }

  toggle() {
    const body = this.el.querySelector(".tool-content");
    const chevron = this.el.querySelector('[data-cs-role="chevron"]');
    if (!body) return;
    const isOpen = body.classList.toggle("open");
    chevron?.classList.toggle("open", isOpen);
    chevron.style.transform = isOpen ? "rotate(180deg)" : "";
  }

  /** 流式追加内容(delta) */
  appendDelta(delta) {
    if (this.status !== "running") return;
    // For text-deltas we keep an accumulator and re-render
    if (!this._deltaBuffer) this._deltaBuffer = "";
    this._deltaBuffer += delta;
    this._renderBody();
  }

  /** 标记完成,接收 result */
  complete(success, result) {
    console.log(`[cs-tool-call] complete: toolId=${this.toolCallId}, success=${success}, ageMs=${Date.now() - this.startTime}`);
    this._clearWatchdog();
    this.status = success ? "completed" : "failed";
    this.endTime = Date.now();
    // 兼容后端 AgentStreamEvent.ToolCallResult.result: String —
    // Kotlin 端发的是 raw 字符串(可能是工具输出内容,也可能是错误信息),
    // 但 _renderResult() 期望的是 { kind, content, text, ... } 结构化对象,
    // 否则 r.content/r.text 都会是 undefined,渲染出空 code block
    if (typeof result === "string") {
      if (success) {
        // P5.6: 优先按 tool name + data 形态路由成结构化 result,
        // 这样 _renderResult 能走 command / json / list / text 分支;
        // 解析不出来的再走老 text kind 兜底。
        const structured = parseToolResultWrapper(result, this.name, this.arguments);
        if (structured) {
          this.result = structured;
          this.error = null;
          console.log(
            `[cs-tool-call] complete: routed string result -> kind=${structured.kind}, ` +
              `toolId=${this.toolCallId}, name=${this.name}`
          );
        } else {
          this.result = { kind: "text", content: result };
          this.error = null;
        }
      } else {
        // P5.7: 如果 result 是 {success:false, error, reason, tool} 这种 JSON,
        // 拆出 error 作主消息,reason/tool 等作 context 渲染成 key-value 列表,
        // 而不是把整坨 JSON 一行显示出来。
        const parsedErr = parseToolError(result);
        if (parsedErr) {
          this.error = parsedErr.message;
          this.errorContext = parsedErr.context;
          console.log(
            `[cs-tool-call] complete: parsed error string -> ` +
              `messageLen=${parsedErr.message.length}, ` +
              `contextKeys=${Object.keys(parsedErr.context).join(",")}, ` +
              `toolId=${this.toolCallId}, name=${this.name}`,
          );
        } else {
          this.error = result || "执行失败";
          this.errorContext = null;
        }
        this.result = null;
      }
    } else if (result && typeof result === "object") {
      this.result = result;
      this.error = success
        ? null
        : result.error || result.message || "执行失败";
    } else {
      this.result = null;
      this.error = success ? null : "执行失败";
    }
    this._renderHeader();
    this._renderBody();
    // 默认折叠(成功时)— 减少视觉噪音
    if (this.status === "completed") {
      this.el.querySelector(".tool-content")?.classList.remove("open");
      this.el
        .querySelector('[data-cs-role="chevron"]')
        ?.classList.remove("open");
    } else {
      // 失败时默认展开,让用户看到错误
      this.el.querySelector(".tool-content")?.classList.add("open");
    }
    // P5.5: sub-agent 展开/收起 按钮
    this._bindSubagentActions();
    // P5.6: command kind 的 stdout/stderr 复制按钮
    this._bindCommandActions();
  }

  _bindCommandActions() {
    if (!this.el) return;
    const wire = (selector, role) => {
      const btn = this.el.querySelector(selector);
      if (!btn) return;
      btn.addEventListener("click", async () => {
        const body = this.el.querySelector(`[data-cs-role="${role}"]`);
        const text = body ? body.innerText || body.textContent || "" : "";
        if (!text) return;
        try {
          await navigator.clipboard.writeText(text);
          const old = btn.innerHTML;
          btn.innerHTML = '<i class="fas fa-check"></i>&nbsp;已复制';
          setTimeout(() => {
            btn.innerHTML = old;
          }, 1200);
        } catch (e) {
          console.warn(
            `[cs-tool-call] copy ${role} failed: toolId=${this.toolCallId}, error=${e.message}`,
          );
        }
      });
    };
    wire('[data-cs-action="copy-command-stdout"]', "command-stdout");
    wire('[data-cs-action="copy-command-stderr"]', "command-stderr");
  }

  _bindSubagentActions() {
    const expandBtn = this.el.querySelector(
      '[data-cs-action="expand-subagent"]',
    );
    const collapseBtn = this.el.querySelector(
      '[data-cs-action="collapse-subagent"]',
    );
    const fullEl = this.el.querySelector(".cs-subagent-output-full");
    if (expandBtn) {
      expandBtn.addEventListener("click", () => {
        if (fullEl) fullEl.style.display = "";
        expandBtn.style.display = "none";
        if (collapseBtn) collapseBtn.style.display = "";
      });
    }
    if (collapseBtn) {
      collapseBtn.addEventListener("click", () => {
        if (fullEl) fullEl.style.display = "none";
        collapseBtn.style.display = "none";
        if (expandBtn) expandBtn.style.display = "";
      });
    }
  }

  fail(error) {
    console.warn(`[cs-tool-call] fail: toolId=${this.toolCallId}, error=${error}, ageMs=${Date.now() - this.startTime}`);
    this._clearWatchdog();
    this.status = "failed";
    this.endTime = Date.now();
    this.error = error || "未知错误";
    this._renderHeader();
    this._renderBody();
    this.el.querySelector(".tool-content")?.classList.add("open");
  }

  /**
   * 标记为"已停止"(用户点停 / AI 提前结束 / 超时) — 跟 failed 区别:
   * - failed: 后端报"执行失败",有具体错误
   * - stopped: 本轮没人通知结果,可能是被中断或被遗忘
   */
  stop(reason) {
    console.warn(`[cs-tool-call] stop: toolId=${this.toolCallId}, reason=${reason}, ageMs=${Date.now() - this.startTime}`);
    this._clearWatchdog();
    this.status = "stopped";
    this.endTime = Date.now();
    this.error = reason || "工具未完成";
    this._renderHeader();
    this._renderBody();
    this.el.querySelector(".tool-content")?.classList.add("open");
  }

  /**
   * 看门狗: 如果 construct 之后 N 秒(per-tool 配置)还没收到 complete/fail/stop,
   * 自动调 stop() 避免卡片永远转圈。
   *
   * N 由 getToolTimeoutMs(this.name) 决定:
   *  - subagent: 10 分钟
   *  - mcp__*: 5 分钟
   *  - 其它(本地工具): 30 秒
   *
   * 注: 5min→30s 是 2026-06 重构的一部分(backend EventConsumer 已修复 Terminal
   * 必送达,5min 太宽松会掩盖问题)。但 sub-agent 任务天然跑得久,所以单独豁免。
   */
  _startWatchdog() {
    this._clearWatchdog();
    const timeoutMs = getToolTimeoutMs(this.name);
    this._watchdogTimeoutMs = timeoutMs;
    this._watchdogId = setTimeout(
      () => {
        if (this.status === "running") {
          const timeoutSec = Math.round(timeoutMs / 1000);
          console.warn(
            `[cs-tool-call] watchdog fired: toolId=${this.toolCallId}, name=${this.name}, ` +
            `turnId=${this.turnId}, ageMs=${Date.now() - this.startTime}, ` +
            `timeoutMs=${timeoutMs}, statusBefore=running`,
          );
          this.stop(`工具执行超时(>${timeoutSec} 秒未收到 complete/error 事件)`);
        }
      },
      timeoutMs,
    );
  }

  _clearWatchdog() {
    if (this._watchdogId) {
      clearTimeout(this._watchdogId);
      this._watchdogId = null;
    }
  }
}
