/**
 * cs-tool-call v2.0 — 工具调用卡片
 * =================================
 *
 * 设计核心 (对比 v1 修复):
 *   - 完成后默认折叠为一行:"✓ Read file.ts · 0.2s"
 *   - 状态机: queued → running → completed/failed/stopped
 *   - 展开:参数 (key-value 表格) + 结果 (按 kind 渲染)
 *   - 视觉清晰:每个状态有独立颜色徽标
 *   - 时间锚点:不再堆在文本后,而是 inline 到产生它的那句话之后
 *
 * 数据契约 (与后端 EventRouter 一致):
 *   tool_call_start   { turnId, toolId, toolName, summary, arguments, icon }
 *   tool_call_delta   { turnId, toolId, delta }
 *   tool_call_complete{ turnId, toolId, success, result }
 *   tool_call_error   { turnId, toolId, error }
 */

import { escapeHtml, formatDuration, truncate } from "../utils.js";

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
    if (TOOL_TIMEOUTS_MS[toolName] !== undefined) return TOOL_TIMEOUTS_MS[toolName];
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
        context[k] = typeof v === "object" ? JSON.stringify(v, null, 2) : String(v);
    }
    return { message, context };
}

function parseToolResultWrapper(str, toolName, args) {
    if (typeof str !== "string") return null;
    const trimmed = str.trim();
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null;
    let parsed;
    try { parsed = JSON.parse(trimmed); } catch { return null; }
    if (!parsed || typeof parsed !== "object") return null;

    const WRAPPER_KEYS = new Set(["success", "data", "error", "reason", "tool", "warning"]);
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
    if (typeof value === "boolean") return { html: String(value), cls: "boolean" };
    if (Array.isArray(value) || typeof value === "object") {
        return { html: escapeHtml(JSON.stringify(value, null, 2)), cls: "string", block: true };
    }
    return { html: String(value), cls: "string" };
}

function renderArgsTable(args) {
    if (!args || typeof args !== "object") return "";
    const keys = Object.keys(args);
    if (keys.length === 0) return "";
    return `
        <div class="tool-args">
            ${keys.map((k) => {
                const f = formatArgValue(args[k]);
                return `
                    <div class="tool-arg-row">
                        <span class="tool-arg-key">${escapeHtml(k)}</span>
                        <span class="tool-arg-value ${f.cls}">${f.block ? `<pre>${f.html}</pre>` : f.html}</span>
                    </div>
                `;
            }).join("")}
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

        this.el = document.createElement("div");
        this.el.className = "tool-card running";
        this.el.setAttribute("data-cs-tool-call", this.toolCallId);
        this.el.setAttribute("data-cs-tool-name", this.name);
        this._render();
    }

    _render() {
        const meta = STATUS_META[this.status] || STATUS_META.running;
        const isRunning = this.status === "running";
        const durationLabel = isRunning
            ? `<span class="pulse-dot"></span> 执行中…`
            : this.elapsedMs > 0
                ? `<i class="fas fa-clock" style="font-size:9px;"></i> ${(this.elapsedMs / 1000).toFixed(2)}s`
                : "";
        const summaryHtml = this.summary
            ? `<span class="tool-summary">${escapeHtml(this.summary)}</span>`
            : `<span class="tool-summary tool-summary-empty">${escapeHtml(this._defaultSummary())}</span>`;

        this.el.className = `tool-card ${meta.cls}`;
        this.el.innerHTML = `
            <div class="tool-header" data-cs-role="header" role="button" tabindex="0" aria-expanded="false">
                <div class="tool-status-icon ${meta.cls}" aria-hidden="true">
                    <i class="${meta.icon}"></i>
                </div>
                <span class="tool-name">${escapeHtml(this._displayName())}</span>
                ${summaryHtml}
                <span class="tool-duration">${durationLabel}</span>
                <i class="fas fa-chevron-down tool-chevron" data-cs-role="chevron"></i>
            </div>
            <div class="tool-body" data-cs-role="body">
                ${this._renderBody()}
            </div>
        `;

        this._bodyEl = this.el.querySelector('[data-cs-role="body"]');
        this._headerEl = this.el.querySelector('[data-cs-role="header"]');
        this._durationEl = this.el.querySelector(".tool-duration");

        this._headerEl.addEventListener("click", () => this.toggle());
        this._headerEl.addEventListener("keydown", (e) => {
            if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                this.toggle();
            }
        });
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
        return "执行中…";
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
        if (this._bodyEl && !this._bodyEl.classList.contains("open")) {
            // 流式时不强制展开,只更新数据
        } else if (this._bodyEl) {
            this._bodyEl.innerHTML = this._renderBody();
        }
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
        if (this.timerInterval) {
            clearInterval(this.timerInterval);
            this.timerInterval = null;
        }
        this._render();
        // 修复 v1: 完成后**默认折叠**(v1 默认展开导致阅读负担)
        this.collapse();
    }

    fail(error) {
        this.success = false;
        this.status = "failed";
        this.elapsedMs = Date.now() - this.startTime;
        this.result = {
            kind: "error",
            message: typeof error === "string" ? error : error?.message || String(error),
        };
        if (this.timerInterval) {
            clearInterval(this.timerInterval);
            this.timerInterval = null;
        }
        this._render();
        this.collapse();
    }

    stop() {
        this.status = "stopped";
        this.elapsedMs = Date.now() - this.startTime;
        if (this.timerInterval) {
            clearInterval(this.timerInterval);
            this.timerInterval = null;
        }
        this._render();
        this.collapse();
    }

    toggle() {
        if (!this._bodyEl) return;
        const isOpen = this._bodyEl.classList.toggle("open");
        this.el.querySelector('[data-cs-role="chevron"]')?.classList.toggle("open", isOpen);
        this._headerEl.setAttribute("aria-expanded", String(isOpen));
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
        this.el?.querySelector('[data-cs-role="chevron"]')?.classList.remove("open");
        this._headerEl?.setAttribute("aria-expanded", "false");
        this.collapsed = true;
    }

    destroy() {
        if (this.timerInterval) clearInterval(this.timerInterval);
        this.el.remove();
    }
}
