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

export class ToolCall {
  constructor(opts) {
    this.toolCallId = opts.toolCallId;
    this.turnId = opts.turnId;
    this.name = opts.name || "tool";
    this.serverName = opts.serverName || null;
    this.summary = opts.summary || "";
    this.arguments = opts.arguments || null;
    this.kind = opts.kind || null;
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
  }

  _renderHeader() {
    const meta = STATUS_META[this.status];
    const isMcp = this.name.startsWith("mcp__");
    const displayName = isMcp
      ? `<span style="color:var(--accent);font-size:11px;background:var(--accent-soft);padding:1px 6px;border-radius:4px;margin-right:6px;">MCP: ${escapeHtml(this.serverName || "server")}</span>${escapeHtml(this.name.replace(/^mcp__/, ""))}`
      : escapeHtml(this.name);

    const headerHtml = `
            <div class="tool-card-header" data-cs-role="header">
                <div class="tool-status-icon ${meta.cls}"><i class="${meta.icon}"></i></div>
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
      parts.push(
        `<div class="inline-alert error" style="margin:var(--space-2) var(--space-3);"><i class="fas fa-times-circle alert-icon"></i><div class="alert-body"><div class="alert-title">执行失败</div><div class="alert-message">${escapeHtml(this.error || "未知错误")}</div></div></div>`,
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
    } else if (kind === "code" || kind === "text") {
      body = this._renderCodeLike(r.content || r.text || "", kind, r.language);
    } else if (kind === "json") {
      body = `<pre style="background:var(--bg-code);color:var(--code-fg);padding:var(--space-2);border-radius:var(--radius-sm);font-size:11px;font-family:var(--font-mono);max-height:240px;overflow:auto;margin:0;">${escapeHtml(typeof r.content === "string" ? r.content : JSON.stringify(r.content, null, 2))}</pre>`;
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
    const exitCode = r.exitCode != null ? r.exitCode : 0;
    const exitCls = exitCode === 0 ? "var(--success)" : "var(--error)";
    return `
            <div style="background:var(--bg-code);border-radius:var(--radius-sm);overflow:hidden;">
                <div style="padding:var(--space-2) var(--space-3);background:rgba(255,255,255,0.04);color:#a0a0b0;font-size:11px;font-family:var(--font-mono);border-bottom:1px solid rgba(255,255,255,0.06);">
                    <span style="color:var(--accent);">$</span>&nbsp;${escapeHtml(r.command || "")}
                    ${exitCode !== 0 ? `<span style="color:${exitCls};margin-left:var(--space-2);">exit ${exitCode}</span>` : ""}
                </div>
                <pre style="margin:0;padding:var(--space-3);color:var(--code-fg);font-family:var(--font-mono);font-size:11px;max-height:240px;overflow:auto;white-space:pre-wrap;line-height:1.5;">${escapeHtml(r.output || r.stdout || "")}</pre>
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
  complete(result) {
    this.status = result.success ? "completed" : "failed";
    this.endTime = Date.now();
    this.result = result.result || null;
    this.error = result.result?.error || (result.success ? null : "执行失败");
    this._renderHeader();
    this._renderBody();
    // 默认折叠(成功时)
    if (this.status === "completed") {
      this.el.querySelector(".tool-content")?.classList.remove("open");
      this.el
        .querySelector('[data-cs-role="chevron"]')
        ?.classList.remove("open");
    } else {
      // 失败时默认展开
      this.el.querySelector(".tool-content")?.classList.add("open");
    }
    // P5.5: sub-agent 展开/收起 按钮
    this._bindSubagentActions();
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
    this.status = "failed";
    this.endTime = Date.now();
    this.error = error;
    this._renderHeader();
    this._renderBody();
    this.el.querySelector(".tool-content")?.classList.add("open");
  }
}

// 工具函数 - 暴露给 utils 复用
function formatDuration(ms) {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}
