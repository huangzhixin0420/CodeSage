/**
 * cs-tool-call v3.0 — 工具调用卡片 / inline badge 混合组件
 * ===========================================================
 *
 * 设计核心 (v3.0 新增):
 *   - running 状态渲染为 inline badge，降低视觉噪音。
 *   - completed/failed/stopped 后渲染为可折叠卡片。
 *   - 支持同一轮次工具链分组（ToolGroup）。
 *   - 工具语义图标映射（read/write/command/search/mcp/subagent）。
 *
 * 数据契约 (与后端 EventRouter 一致):
 *   tool_call_start   { turnId, toolId, toolName, summary, arguments, icon }
 *   tool_call_delta   { turnId, toolId, delta }
 *   tool_call_complete{ turnId, toolId, success, result }
 *   tool_call_error   { turnId, toolId, error }
 */

import { escapeHtml, formatDuration, truncate, genId } from "../utils.js";
import {
  getToolEmoji,
  getToolSvgName,
  getToolIconClass,
  getToolColorVar,
} from "../tool-icons.js";
import { icon } from "../icons.js";

const STATUS_META = {
  queued: {
    icon: "fas fa-circle",
    cls: "queued",
    label: "排队",
  },
  running: {
    icon: "fas fa-spinner spin",
    cls: "running",
    label: "执行中",
  },
  completed: {
    icon: "fas fa-check",
    cls: "completed",
    label: "完成",
  },
  failed: {
    icon: "fas fa-xmark",
    cls: "failed",
    label: "失败",
  },
  stopped: {
    icon: "fas fa-stop",
    cls: "stopped",
    label: "已停止",
  },
  confirm: {
    icon: "fas fa-exclamation",
    cls: "confirm",
    label: "待确认",
  },
};

const KIND_LABEL = {
  text: "输出",
  code: "代码",
  diff: "变更",
  command: "命令",
  json: "JSON",
  list: "列表",
  error: "错误",
  subagent: "子任务",
};

const TOOL_TIMEOUTS_MS = {
  default: 30_000,
  delegate_task: 10 * 60_000,
  subagent: 10 * 60_000,
};
const MCP_TOOL_TIMEOUT_MS = 5 * 60_000;

function getToolTimeoutMs(toolName) {
  if (TOOL_TIMEOUTS_MS[toolName] !== undefined)
    return TOOL_TIMEOUTS_MS[toolName];
  if (toolName && toolName.startsWith("mcp__")) return MCP_TOOL_TIMEOUT_MS;
  return TOOL_TIMEOUTS_MS.default;
}

const COMMAND_TOOL_NAMES = new Set([
  "run_command",
  "exec_shell",
  "run_tests",
  "run_shell",
  "bash",
]);

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

function parseToolError(str) {
  if (typeof str !== "string") return null;
  const trimmed = str.trim();
  if (!trimmed.startsWith("{")) return null;
  let parsed;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object") return null;
  if (parsed.success !== false) return null;
  const message = parsed.error || parsed.message;
  if (!message || typeof message !== "string") return null;
  const context = {};
  for (const k of Object.keys(parsed)) {
    if (k === "success" || k === "error" || k === "message") continue;
    const v = parsed[k];
    if (v == null) continue;
    context[k] = typeof v === "object" ? JSON.stringify(v, null, 2) : String(v);
  }
  return { message, context };
}

function parseToolResultWrapper(str, toolName, args) {
  if (typeof str !== "string") return null;
  const trimmed = str.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null;
  let parsed;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object") return null;

  const WRAPPER_KEYS = new Set([
    "success",
    "data",
    "error",
    "reason",
    "tool",
    "warning",
  ]);
  const hasWrapper =
    "success" in parsed &&
    ("data" in parsed || (parsed.success === false && "error" in parsed)) &&
    Object.keys(parsed).every((k) => WRAPPER_KEYS.has(k));
  const data = hasWrapper ? parsed.data : parsed;
  if (data == null) return null;

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

  if (typeof data === "object" && !Array.isArray(data)) {
    return { kind: "json", content: data };
  }
  if (Array.isArray(data)) {
    return { kind: "list", items: data.map((i) => String(i)) };
  }
  if (typeof data === "string") {
    return { kind: "text", content: data };
  }
  return { kind: "text", content: String(data) };
}

/** 把任意 result 标准化为 { kind, ... } */
function normalizeResult(raw, toolName, args) {
  if (raw == null) return { kind: "text", content: "" };
  if (typeof raw === "object" && "kind" in raw) return raw;
  if (typeof raw === "string") {
    const errParsed = parseToolError(raw);
    if (errParsed) return { kind: "error", ...errParsed };
    const wrapped = parseToolResultWrapper(raw, toolName, args);
    if (wrapped) return wrapped;
    return { kind: "text", content: raw };
  }
  if (typeof raw === "object") {
    if ("stdout" in raw || "stderr" in raw) {
      return {
        kind: "command",
        command: args?.command || args?.cmd || "",
        stdout: String(raw.stdout ?? ""),
        stderr: String(raw.stderr ?? ""),
        exitCode: raw.exit_code ?? raw.exitCode ?? 0,
      };
    }
    return { kind: "json", content: raw };
  }
  return { kind: "text", content: String(raw) };
}

function formatArgValue(value) {
  if (value === null) return { html: "null", cls: "null" };
  if (value === undefined) return { html: "undefined", cls: "null" };
  if (typeof value === "string") {
    const pretty = tryPrettyJson(value);
    if (pretty !== value) {
      return { html: escapeHtml(pretty), cls: "string", block: true };
    }
    const display = value.length > 200 ? truncate(value, 200) : value;
    return { html: `"${escapeHtml(display)}"`, cls: "string" };
  }
  if (typeof value === "number") return { html: String(value), cls: "number" };
  if (typeof value === "boolean")
    return { html: String(value), cls: "boolean" };
  if (Array.isArray(value) || typeof value === "object") {
    return {
      html: escapeHtml(JSON.stringify(value, null, 2)),
      cls: "string",
      block: true,
    };
  }
  return { html: String(value), cls: "string" };
}

function renderArgsTable(args) {
  if (!args || typeof args !== "object") return "";
  const keys = Object.keys(args);
  if (keys.length === 0) return "";
  return `
        <div class="tool-args">
            ${keys
              .map((k) => {
                const f = formatArgValue(args[k]);
                return `
                    <div class="tool-arg-row">
                        <span class="tool-arg-key">${escapeHtml(k)}</span>
                        <span class="tool-arg-value ${f.cls}">${f.block ? `<pre>${f.html}</pre>` : f.html}</span>
                    </div>
                `;
              })
              .join("")}
        </div>
    `;
}

function renderResult(r) {
  if (!r) return "";
  const label = KIND_LABEL[r.kind] || r.kind;
  let body = "";
  switch (r.kind) {
    case "text": {
      const txt = r.content || "";
      const pretty = tryPrettyJson(txt);
      const isJson = pretty !== txt;
      body = isJson
        ? `<pre class="tool-result-json">${escapeHtml(pretty)}</pre>`
        : `<div class="tool-result-code">${escapeHtml(txt)}</div>`;
      break;
    }
    case "code": {
      body = `<pre class="tool-result-code"><code>${escapeHtml(r.content || "")}</code></pre>`;
      break;
    }
    case "command": {
      const meta = [];
      if (r.exitCode !== undefined && r.exitCode !== 0) {
        meta.push(`<span class="meta-failed">exit ${r.exitCode}</span>`);
      }
      if (r.exitCode === 0) meta.push(`<span>exit 0</span>`);
      if (r.summary) meta.push(`<span>${escapeHtml(r.summary)}</span>`);
      body = `
                ${r.command ? `<div class="tool-result-meta"><span>$ ${escapeHtml(truncate(r.command, 120))}</span></div>` : ""}
                ${r.stdout ? `<pre class="tool-result-stdout">${escapeHtml(r.stdout)}</pre>` : ""}
                ${r.stderr ? `<pre class="tool-result-stderr">${escapeHtml(r.stderr)}</pre>` : ""}
                ${meta.length ? `<div class="tool-result-meta">${meta.join("")}</div>` : ""}
            `;
      break;
    }
    case "list": {
      const items = r.items || [];
      body = `
                <div class="tool-result-list">
                    ${items.map((it) => `<div class="tool-result-list-item">${escapeHtml(String(it))}</div>`).join("")}
                </div>
            `;
      break;
    }
    case "json": {
      body = `<pre class="tool-result-json">${escapeHtml(JSON.stringify(r.content, null, 2))}</pre>`;
      break;
    }
    case "diff": {
      body = `<div class="tool-result-code">${escapeHtml(r.content || "")}</div>`;
      break;
    }
    case "subagent": {
      const sa = r.subagent || r;
      body = `
                <div class="tool-subagent-stream">${escapeHtml(sa.output || sa.stream || "")}</div>
                ${sa.task ? `<div class="tool-result-meta"><span>${escapeHtml(truncate(sa.task, 200))}</span></div>` : ""}
            `;
      break;
    }
    case "error": {
      body = `
                <div class="tool-result-error">${escapeHtml(r.message || "")}</div>
                ${r.context ? `<div class="tool-result-meta"><span>${escapeHtml(r.context)}</span></div>` : ""}
            `;
      break;
    }
    default: {
      const text = typeof r === "string" ? r : JSON.stringify(r, null, 2);
      body = `<pre class="tool-result-json">${escapeHtml(text)}</pre>`;
    }
  }
  return `
        <div class="tool-section">
            <div class="tool-section-label">${escapeHtml(label)}</div>
            ${body}
        </div>
    `;
}

export class ToolCall {
  /**
   * @param {object} opts
   * @param {string} opts.toolCallId
   * @param {string} opts.turnId
   * @param {string} opts.name
   * @param {string} [opts.summary]
   * @param {object} [opts.arguments]
   * @param {string} [opts.icon]
   */
  constructor(opts = {}) {
    this.toolCallId = opts.toolCallId;
    this.turnId = opts.turnId;
    this.name = opts.name;
    this.summary = opts.summary || "";
    this.arguments = opts.arguments || {};
    this.icon = opts.icon;

    this.status = "running";
    this.startTime = Date.now();
    this.elapsedMs = 0;
    this.result = null;
    this.success = null;
    this.collapsed = false;
    this.timerInterval = null;
    this.stream = "";
    this.stepIds = Array.isArray(opts.stepIds) ? [...opts.stepIds] : [];

    this.el = document.createElement("span");
    // v3.0: running 状态作为 inline badge，完成后切换为 block card
    this.el.className = "tool-card tool-badge-mode running";
    this.el.setAttribute("data-cs-tool-call", this.toolCallId);
    this.el.setAttribute("data-cs-tool-name", this.name);
    if (this.stepIds.length) {
      this.el.setAttribute("data-cs-step-ids", this.stepIds.join(","));
    }

    this._render();
    this._startTimer();
  }

  _displayName() {
    if (this.name && this.name.startsWith("mcp__")) {
      const parts = this.name.split("__");
      return parts[parts.length - 1] || this.name;
    }
    return this.name || "tool";
  }

  _defaultSummary() {
    if (this.name === "subagent") return "子任务执行中…";
    const argPath =
      this.arguments?.path ||
      this.arguments?.file ||
      this.arguments?.command ||
      this.arguments?.sql ||
      "";
    return argPath ? truncate(argPath, 50) : "执行中…";
  }

  _startTimer() {
    if (this.timerInterval) return;
    this.timerInterval = setInterval(() => {
      this.elapsedMs = Date.now() - this.startTime;
      this._updateDuration();
    }, 100);
  }

  _stopTimer() {
    if (this.timerInterval) {
      clearInterval(this.timerInterval);
      this.timerInterval = null;
    }
  }

  _render() {
    if (this.status === "running") {
      this._renderBadge();
    } else {
      this._renderCard();
    }
  }

  /** running 状态：inline badge */
  _renderBadge() {
    const svg = icon(getToolSvgName(this.name), "tool-badge-icon");
    const color = getToolColorVar(this.name);
    const summary = this.summary || this._defaultSummary();
    const elapsed = (this.elapsedMs / 1000).toFixed(1);

    this.el.className = "tool-card tool-badge-mode running";
    this.el.style.display = "inline-flex";
    this.el.style.borderColor = color;
    this.el.innerHTML = `
            <span class="tool-badge-icon-wrap" aria-hidden="true">${svg}</span>
            <span class="tool-badge-name">${escapeHtml(this._displayName())}</span>
            <span class="tool-badge-summary">${escapeHtml(summary)}</span>
            <span class="tool-badge-time" data-cs-role="duration">${elapsed}s</span>
            <span class="tool-badge-spinner">${icon("spinner", "tool-badge-spinner-icon")}</span>
        `;
    this._durationEl = this.el.querySelector('[data-cs-role="duration"]');
  }

  /** 完成/失败/停止：可折叠卡片 */
  _renderCard() {
    const meta = STATUS_META[this.status] || STATUS_META.running;
    const durationLabel =
      this.elapsedMs > 0
        ? `<i class="fas fa-clock" style="font-size:9px;"></i> ${(this.elapsedMs / 1000).toFixed(2)}s`
        : "";
    const summaryHtml = this.summary
      ? `<span class="tool-summary">${escapeHtml(this.summary)}</span>`
      : `<span class="tool-summary tool-summary-empty">${escapeHtml(this._defaultSummary())}</span>`;
    const svg = icon(getToolSvgName(this.name), "tool-card-icon");

    this.el.className = `tool-card ${meta.cls}`;
    this.el.style.display = "";
    this.el.style.borderColor = "";
    this.el.innerHTML = `
            <div class="tool-header" data-cs-role="header" role="button" tabindex="0" aria-expanded="false">
                <div class="tool-status-icon ${meta.cls}" aria-hidden="true">
                    <i class="${meta.icon}"></i>
                </div>
                <span class="tool-card-icon-wrap" aria-hidden="true">${svg}</span>
                <span class="tool-name">${escapeHtml(this._displayName())}</span>
                ${summaryHtml}
                <span class="tool-duration" data-cs-role="duration">${durationLabel}</span>
                <i class="fas fa-chevron-down tool-chevron" data-cs-role="chevron"></i>
            </div>
            <div class="tool-body" data-cs-role="body">
                ${this._renderBody()}
            </div>
        `;

    this._bodyEl = this.el.querySelector('[data-cs-role="body"]');
    this._headerEl = this.el.querySelector('[data-cs-role="header"]');
    this._durationEl = this.el.querySelector('[data-cs-role="duration"]');

    this._headerEl.addEventListener("click", () => this.toggle());
    this._headerEl.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        this.toggle();
      }
    });
  }

  _updateDuration() {
    if (!this._durationEl) return;
    if (this.status === "running") {
      this._durationEl.textContent = (this.elapsedMs / 1000).toFixed(1) + "s";
    } else {
      this._durationEl.innerHTML = `<i class="fas fa-clock" style="font-size:9px;"></i> ${(this.elapsedMs / 1000).toFixed(2)}s`;
    }
  }

  _renderBody() {
    const argsHtml = renderArgsTable(this.arguments);
    const resultHtml = this.result
      ? renderResult(this.result)
      : this.stream
        ? renderResult({ kind: "text", content: this.stream })
        : "";
    if (!argsHtml && !resultHtml) {
      return `<div class="tool-section" style="color:var(--fg-tertiary);font-size:var(--text-xs);">无参数 / 等待结果…</div>`;
    }
    return argsHtml + resultHtml;
  }

  appendDelta(delta) {
    this.stream += delta;
    if (this.status !== "running") {
      this._renderBodyToDom();
    }
  }

  _renderBodyToDom() {
    if (this._bodyEl) this._bodyEl.innerHTML = this._renderBody();
  }

  setStatus(status) {
    this.status = status;
    this._render();
  }

  complete(success, result) {
    this.success = !!success;
    this.status = success ? "completed" : "failed";
    this.elapsedMs = Date.now() - this.startTime;
    this.result = normalizeResult(result, this.name, this.arguments);
    this._stopTimer();
    this._render();
    this.collapse();
  }

  fail(error) {
    this.success = false;
    this.status = "failed";
    this.elapsedMs = Date.now() - this.startTime;
    this.result = {
      kind: "error",
      message:
        typeof error === "string" ? error : error?.message || String(error),
    };
    this._stopTimer();
    this._render();
    this.collapse();
  }

  stop() {
    this.status = "stopped";
    this.elapsedMs = Date.now() - this.startTime;
    this._stopTimer();
    this._render();
    this.collapse();
  }

  toggle() {
    if (!this._bodyEl) return;
    const isOpen = this._bodyEl.classList.toggle("open");
    this.el
      .querySelector('[data-cs-role="chevron"]')
      ?.classList.toggle("open", isOpen);
    this._headerEl?.setAttribute("aria-expanded", String(isOpen));
    this.collapsed = !isOpen;
  }

  expand() {
    this._bodyEl?.classList.add("open");
    this.el?.querySelector('[data-cs-role="chevron"]')?.classList.add("open");
    this._headerEl?.setAttribute("aria-expanded", "true");
    this.collapsed = false;
  }

  collapse() {
    this._bodyEl?.classList.remove("open");
    this.el
      ?.querySelector('[data-cs-role="chevron"]')
      ?.classList.remove("open");
    this._headerEl?.setAttribute("aria-expanded", "false");
    this.collapsed = true;
  }

  destroy() {
    this._stopTimer();
    this.el.remove();
  }
}

/**
 * ToolGroup — 同一轮次内连续工具调用的汇总条
 */
export class ToolGroup {
  constructor(opts = {}) {
    this.groupId = opts.groupId || genId("tool-group");
    this.turnId = opts.turnId || "";
    this.toolCalls = [];
    this.collapsed = true;

    this.el = document.createElement("div");
    this.el.className = "tool-group-summary";
    this.el.setAttribute("data-cs-tool-group", this.groupId);
    this._render();
  }

  add(toolCall) {
    this.toolCalls.push(toolCall);
    this._render();
  }

  get completedCount() {
    return this.toolCalls.filter((t) => t.status !== "running").length;
  }

  get totalElapsedMs() {
    return this.toolCalls.reduce((sum, t) => sum + (t.elapsedMs || 0), 0);
  }

  _render() {
    const completed = this.completedCount;
    const total = this.toolCalls.length;
    const allDone = completed === total && total > 0;
    const elapsed = (this.totalElapsedMs / 1000).toFixed(1);
    const icon = allDone ? "fa-check-double" : "fa-spinner spin";
    const label = allDone
      ? `已执行 ${total} 个工具 · ${elapsed}s`
      : `执行中 ${completed}/${total} · ${elapsed}s`;

    this.el.innerHTML = `
            <span class="tool-group-icon"><i class="fas ${icon}"></i></span>
            <span class="tool-group-text">${escapeHtml(label)}</span>
            <button class="tool-group-expand" data-cs-role="expand">${this.collapsed ? "展开" : "折叠"}</button>
        `;

    this.el
      .querySelector('[data-cs-role="expand"]')
      ?.addEventListener("click", (e) => {
        e.stopPropagation();
        this.toggle();
      });
  }

  toggle() {
    this.collapsed = !this.collapsed;
    this._render();
    // 通知外部显示/隐藏详细列表
    if (this.onToggle) this.onToggle(this.collapsed);
  }
}
