/**
 * cs-thinking 组件 — 三态:running / completed / collapsed
 *
 * 设计文档 §6:
 *   - 思考中:三点呼吸 + 「思考中 · 0.8s」+ 默认展开
 *   - 思考完成:绿勾 + 「思考完成 · 3.4s」+ 1.5s 后折叠
 *   - 折叠:一行 chip
 */

import { escapeHtml } from "../utils.js";

const STATES = {
  running: {
    cls: "running",
    iconHtml: '<span class="thinking-dot thinking-dot-visual"></span>'.repeat(
      3,
    ),
    label: (ms) => `思考中 · ${(ms / 1000).toFixed(1)}s`,
  },
  completed: {
    cls: "completed",
    iconHtml:
      '<i class="fas fa-check" style="color:var(--success);font-size:11px;"></i>',
    label: (ms) => `已思考 ${(ms / 1000).toFixed(1)}s`,
  },
  collapsed: {
    cls: "completed",
    iconHtml:
      '<i class="fas fa-check" style="color:var(--fg-3);font-size:11px;"></i>',
    label: (ms) => `已思考 ${(ms / 1000).toFixed(1)}s`,
  },
};

export class Thinking {
  /**
   * @param {object} opts
   * @param {string} [opts.id] - 唯一 id,用于事件定位
   */
  constructor(opts = {}) {
    this.id =
      opts.id ||
      "thinking-" + Date.now() + "-" + Math.random().toString(36).slice(2, 7);
    this.state = "running";
    this.startTime = Date.now();
    this.content = "";

    this.el = document.createElement("div");
    this.el.className = "thinking-card running";
    this.el.setAttribute("data-cs-thinking", this.id);

    this._render();
  }

  _render() {
    const cfg = STATES[this.state];
    this.el.className = `thinking-card ${cfg.cls}`;
    this.el.innerHTML = `
            <div class="thinking-header" data-cs-role="header">
                <div class="thinking-dots">${cfg.iconHtml}</div>
                <span class="thinking-label" data-cs-role="label">${escapeHtml(cfg.label(Date.now() - this.startTime))}</span>
                <i class="fas fa-chevron-down thinking-chevron" data-cs-role="chevron"></i>
            </div>
            <div class="thinking-body" data-cs-role="body"></div>
        `;
    this._bodyEl = this.el.querySelector('[data-cs-role="body"]');
    this._labelEl = this.el.querySelector('[data-cs-role="label"]');
    this._headerEl = this.el.querySelector('[data-cs-role="header"]');
    this._headerEl.addEventListener("click", () => this.toggle());
    if (this.content) this._bodyEl.textContent = this.content;
  }

  /** 思考中追加内容 */
  appendContent(text) {
    this.content += text;
    if (this._bodyEl) this._bodyEl.textContent = this.content;
  }

  setContent(text) {
    this.content = text;
    if (this._bodyEl) this._bodyEl.textContent = this.content;
  }

  /** 标记完成 */
  complete(durationMs) {
    this.state = "completed";
    this.durationMs = durationMs ?? Date.now() - this.startTime;
    this._render();
    this._bodyEl.textContent = this.content;
    // 修复:用户反馈「思考默认折叠」— 之前是 1.5s 后自动折叠
    // 现在直接默认折叠(状态变 "completed" + chevron 默认收起),
    // 用户点 header 才展开看完整内容
    this._bodyEl.classList.remove("open");
    this.el.querySelector('[data-cs-role="chevron"]').classList.remove("open");
    // 更新为 "collapsed" 视觉态(更柔和的图标)
    this.el.classList.remove("running");
    this.el.classList.add("completed", "collapsed");
  }

  toggle() {
    const isOpen = this._bodyEl.classList.toggle("open");
    this.el
      .querySelector('[data-cs-role="chevron"]')
      .classList.toggle("open", isOpen);
  }

  collapse() {
    this._bodyEl?.classList.remove("open");
    this.el
      ?.querySelector('[data-cs-role="chevron"]')
      ?.classList.remove("open");
  }

  destroy() {
    this.el.remove();
  }
}
