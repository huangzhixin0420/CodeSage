/**
 * cs-thinking-v2.js — 推理思考卡片
 * ==================================
 *
 * 目标:把大模型流式返回的 reasoning / thinking 内容原样展示给用户。
 *
 * 设计原则(2026-06 简化):
 *   - 不做模式切换(简/详/原) — 用户只需要看到原文
 *   - 不做阶段解析 / 关键词高亮 / 内部搜索 — 这些都是过度设计
 *   - 不做 think 标签剥离 — 后端 ModelReasoning 已经是纯推理文本
 *   - 保留:可折叠卡片(默认折叠避免视觉噪音)、chevron、elapsedMs 元信息
 *
 * API(向后兼容):
 *   const card = new StructuredThinking({});
 *   card.appendContent("推理片段");
 *   card.complete(elapsedMs);   // 标记完成
 *   card.toggle();              // 展开/折叠
 *   card.collapse(); / expand();
 *   card.destroy();
 *
 * 仍暴露 `setMode` / `setContent` 以避免破坏其他模块可能用到的接口;
 * 但模式 UI 已移除,setMode 静默 no-op,setContent 等价于 appendContent 后再清空。
 */

import { escapeHtml } from "../utils.js";
import { icon } from "../icons.js";

const RUNNING_DOT_HTML = '<span class="thinking-dot"></span>'.repeat(3);

export class StructuredThinking {
  /**
   * @param {object} [opts]
   * @param {string} [opts.id]
   */
  constructor(opts = {}) {
    this.id =
      opts.id ||
      "thinking-" + Date.now() + "-" + Math.random().toString(36).slice(2, 7);
    this.state = "running"; // "running" | "completed"
    this.startTime = Date.now();
    this.elapsedMs = 0;
    this.content = "";

    this.el = document.createElement("div");
    this.el.className = "thinking-card running";
    this.el.setAttribute("data-cs-thinking", this.id);
    this._render();
  }

  _render() {
    const isRunning = this.state === "running";
    const labelText = isRunning
      ? "思考中…"
      : `已思考 ${(this.elapsedMs / 1000).toFixed(1)}s`;
    const iconHtml = isRunning
      ? RUNNING_DOT_HTML
      : icon("check", "thinking-status-icon");

    this.el.className = `thinking-card ${isRunning ? "running" : "completed"}`;

    // 渲染纯文本:escape + 把换行变 <br>。不解析 markdown / 不提取 <think> 标签 —
    // 后端 ModelReasoning 已是纯推理片段,前端只负责"原样展示"。
    const bodyHtml = this.content
      ? `<div class="thinking-raw"><pre>${escapeHtml(this.content)}</pre></div>`
      : `<div class="thinking-raw thinking-raw-empty"><pre>（暂无内容）</pre></div>`;

    this.el.innerHTML = `
      <div class="thinking-header" data-cs-role="header" role="button" tabindex="0" aria-expanded="false">
        <div class="thinking-icon">${iconHtml}</div>
        <div class="thinking-label">
          <span class="thinking-label-text">${escapeHtml(labelText)}</span>
        </div>
        <i class="fas fa-chevron-down thinking-chevron" data-cs-role="chevron"></i>
      </div>
      <div class="thinking-body" data-cs-role="body">
        ${bodyHtml}
      </div>
    `;

    this._bodyEl = this.el.querySelector('[data-cs-role="body"]');
    this._headerEl = this.el.querySelector('[data-cs-role="header"]');

    this._headerEl.addEventListener("click", () => this.toggle());
    this._headerEl.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        this.toggle();
      }
    });
  }

  appendContent(text) {
    if (!text) return;
    this.content += text;
    // 流式期间,直接更新 body 内容(避免每 token 重建 header 等)
    if (this.state === "running" && this._bodyEl) {
      this._bodyEl.innerHTML =
        `<div class="thinking-raw"><pre>${escapeHtml(this.content)}</pre></div>`;
    } else {
      this._render();
    }
  }

  setContent(text) {
    this.content = text || "";
    this._render();
  }

  /**
   * @deprecated 模式 UI 已移除。保留方法签名以免破坏潜在调用方,静默 no-op。
   */
  setMode(_mode) {
    /* no-op: 2026-06 简化为单一展示模式 */
  }

  complete(elapsedMs) {
    this.state = "completed";
    this.elapsedMs = elapsedMs ?? Date.now() - this.startTime;
    this._render();
    this.collapse();
  }

  toggle() {
    if (!this._bodyEl) return;
    const isOpen = this._bodyEl.classList.toggle("open");
    this._headerEl?.setAttribute("aria-expanded", String(isOpen));
    this.el
      .querySelector('[data-cs-role="chevron"]')
      ?.classList.toggle("open", isOpen);
  }

  collapse() {
    this._bodyEl?.classList.remove("open");
    this._headerEl?.setAttribute("aria-expanded", "false");
    this.el
      .querySelector('[data-cs-role="chevron"]')
      ?.classList.remove("open");
  }

  expand() {
    this._bodyEl?.classList.add("open");
    this._headerEl?.setAttribute("aria-expanded", "true");
    this.el
      .querySelector('[data-cs-role="chevron"]')
      ?.classList.add("open");
  }

  destroy() {
    this.el.remove();
  }
}
