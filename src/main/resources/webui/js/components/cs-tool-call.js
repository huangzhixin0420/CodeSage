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

/**
 * O6: 富渲染辅助函数
 *  - renderCode: 代码块带语言标签 + 复制按钮
 *  - renderTerminal: 命令输出,行号 + stderr 红边
 *  - renderJsonTree: 可折叠 JSON 树(简化版)
 *  - renderError: 错误卡片 + 堆栈折叠
 */
function renderCode(content, language) {
  const lang = language || "text";
  const id = "code-" + Math.random().toString(36).slice(2, 10);
  return `
    <div class="tool-result-code-block" data-lang="${escapeHtml(lang)}">
      <div class="tool-result-code-header">
        <span class="tool-result-code-lang">${escapeHtml(lang)}</span>
        <button type="button" class="tool-result-code-copy" data-cs-copy-target="${id}" title="复制代码">
          <i class="fas fa-copy"></i> 复制
        </button>
      </div>
      <pre class="tool-result-code" id="${id}"><code>${escapeHtml(content || "")}</code></pre>
    </div>
  `;
}

function renderTerminal(stdout, stderr, exitCode) {
  const lines = (s) => (s || "").split("\n");
  const stdoutLines = lines(stdout);
  const stderrLines = lines(stderr);
  const renderLines = (arr, kind) => arr
    .map((line, i) => `<div class="tool-result-term-line ${kind}"><span class="tool-result-term-gutter">${i + 1}</span><span class="tool-result-term-text">${escapeHtml(line)}</span></div>`)
    .join("");
  const failed = exitCode !== undefined && exitCode !== 0;
  return `
    <div class="tool-result-terminal ${failed ? "failed" : ""}">
      ${stdout ? `<div class="tool-result-term-stdout">${renderLines(stdoutLines, "stdout")}</div>` : ""}
      ${stderr ? `<div class="tool-result-term-stderr"><div class="tool-result-term-error-icon"><i class="fas fa-circle-exclamation"></i> stderr</div>${renderLines(stderrLines, "stderr")}</div>` : ""}
      ${exitCode !== undefined ? `<div class="tool-result-term-exit ${failed ? "failed" : ""}">exit ${exitCode}${failed ? " · 异常" : " · 成功"}</div>` : ""}
    </div>
  `;
}

function renderJsonTree(value, key) {
  if (value === null) return `<span class="json-null">null</span>`;
  if (typeof value === "string") return `<span class="json-string">"${escapeHtml(value)}"</span>`;
  if (typeof value === "number") return `<span class="json-number">${value}</span>`;
  if (typeof value === "boolean") return `<span class="json-boolean">${value}</span>`;
  if (Array.isArray(value)) {
    if (value.length === 0) return `<span class="json-bracket">[]</span>`;
    const items = value.map((v, i) => `<li>${renderJsonTree(v, i)}</li>`).join("");
    return `<details class="json-array" open><summary><span class="json-bracket">[</span> ${value.length} 项</summary><ul>${items}</ul><span class="json-bracket">]</span></details>`;
  }
  if (typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return `<span class="json-bracket">{}</span>`;
    const items = entries.map(([k, v]) => `<li><span class="json-key">"${escapeHtml(k)}"</span>: ${renderJsonTree(v, k)}</li>`).join("");
    return `<details class="json-object" open><summary><span class="json-bracket">{</span> ${entries.length} 键</summary><ul>${items}</ul><span class="json-bracket">}</span></details>`;
  }
  return `<span>${escapeHtml(String(value))}</span>`;
}

function renderErrorCard(message, stack, hint) {
  const hasStack = stack && String(stack).trim().length > 0;
  return `
    <div class="tool-result-error-card">
      <div class="tool-result-error-icon">
        <i class="fas fa-triangle-exclamation"></i>
        <span>${escapeHtml(message || "执行失败")}</span>
      </div>
      ${hint ? `<div class="tool-result-error-hint"><i class="fas fa-lightbulb"></i> ${escapeHtml(hint)}</div>` : ""}
      ${hasStack ? `<details class="tool-result-error-stack"><summary>堆栈详情</summary><pre>${escapeHtml(stack)}</pre></details>` : ""}
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
      // O6: 先尝试解析为 JSON(若是 JSON,渲染为可折叠树)
      let parsed = null;
      try { parsed = JSON.parse(txt); } catch (_) { /* not json */ }
      if (parsed !== null) {
        body = `<div class="tool-result-json">${renderJsonTree(parsed, "root")}</div>`;
      } else {
        body = `<div class="tool-result-code">${escapeHtml(txt)}</div>`;
      }
      break;
    }
    case "code": {
      // O6: 代码块带语言标签 + 复制按钮
      body = renderCode(r.content, r.language);
      break;
    }
    case "command": {
      // O6: 终端风格渲染(stdout 行号 + stderr 红边)
      const hasOutput = r.stdout || r.stderr;
      const meta = [];
      body = `
                ${r.command ? `<div class="tool-result-meta"><span class="tool-result-meta-prompt">$ ${escapeHtml(truncate(r.command, 120))}</span></div>` : ""}
                ${hasOutput ? renderTerminal(r.stdout, r.stderr, r.exitCode) : ""}
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
      // O6: 可折叠 JSON 树
      body = `<div class="tool-result-json">${renderJsonTree(r.content, "root")}</div>`;
      break;
    }
    case "diff": {
      // O6: Diff 走专用 cs-diff-viewer(已存在),这里仍回退到 code 渲染
      body = renderCode(r.content, r.language || "diff");
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
      // O6: 错误卡片 + 堆栈折叠 + 建议修复
      body = renderErrorCard(r.message, r.stack, r.hint || r.context);
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
    this.stdout = "";
    this.stderr = "";
    this.exitCode = null;
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
                <div class="tool-body-inner">
                    ${this._renderBody()}
                </div>
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

    // O6: 委托处理代码块复制按钮([data-cs-copy-target])
    this.el.addEventListener("click", (e) => {
      const btn = e.target.closest?.("[data-cs-copy-target]");
      if (!btn) return;
      e.stopPropagation();
      const id = btn.getAttribute("data-cs-copy-target");
      const target = id ? this.el.querySelector("#" + CSS.escape(id)) : null;
      const text = target ? target.innerText : "";
      if (!text) return;
      const copy = async () => {
        try {
          if (navigator.clipboard?.writeText) {
            await navigator.clipboard.writeText(text);
          } else {
            // fallback
            const ta = document.createElement("textarea");
            ta.value = text;
            document.body.appendChild(ta);
            ta.select();
            document.execCommand("copy");
            document.body.removeChild(ta);
          }
          const original = btn.innerHTML;
          btn.innerHTML = '<i class="fas fa-check"></i> 已复制';
          setTimeout(() => { btn.innerHTML = original; }, 1200);
        } catch (err) {
          console.warn("[tool-call] copy failed", err);
        }
      };
      copy();
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
    let liveHtml = "";
    if (this.stdout || this.stderr) {
      const argCmd =
        (this.arguments &&
          (this.arguments.command ||
            this.arguments.cmd ||
            this.arguments.shell_command)) ||
        "";
      liveHtml = renderResult({
        kind: "command",
        command: argCmd,
        stdout: this.stdout,
        stderr: this.stderr,
        exitCode: this.exitCode ?? 0,
        summary:
          this.status === "running"
            ? "流式输出中…"
            : this.exitCode !== 0
              ? `exit ${this.exitCode}`
              : "exit 0",
      });
    } else if (this.stream) {
      liveHtml = renderResult({ kind: "text", content: this.stream });
    }
    const resultHtml = this.result ? renderResult(this.result) : liveHtml;
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

  appendCommandOutput(msg) {
    if (msg.stdout) this.stdout += msg.stdout;
    if (msg.stderr) this.stderr += msg.stderr;
    if (msg.exitCode !== undefined) this.exitCode = msg.exitCode;
    if (this.status === "running") {
      // running 状态是 inline badge，不实时重绘 body；完成后一次性渲染
      return;
    }
    this._renderBodyToDom();
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
    // 若已经有流式累积的 stdout/stderr，优先用其构造命令结果；否则回退到 result
    if (this.stdout || this.stderr) {
      const argCmd =
        (this.arguments &&
          (this.arguments.command ||
            this.arguments.cmd ||
            this.arguments.shell_command)) ||
        "";
      const normalized = normalizeResult(result, this.name, this.arguments);
      this.result = {
        kind: "command",
        command: argCmd,
        stdout: this.stdout,
        stderr: this.stderr,
        exitCode:
          this.exitCode ??
          (normalized && normalized.exit_code) ??
          (success ? 0 : 1),
      };
    } else {
      this.result = normalizeResult(result, this.name, this.arguments);
    }
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
