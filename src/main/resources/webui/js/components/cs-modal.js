/**
 * cs-modal 组件 — 通用模态框基础
 *
 * 设计:
 *   - 由遮罩 + 容器组成
 *   - 容器可自定义 size / title / content
 *   - 支持 Esc 关闭、点击遮罩关闭
 *   - focus trap(简单版)
 */

const ACTIVE_MODALS = [];

export class Modal {
  /**
   * @param {object} opts
   * @param {string} [opts.title]
   * @param {"sm"|"md"|"lg"|"xl"} [opts.size]
   * @param {boolean} [opts.dismissible] - 点击遮罩是否关闭
   * @param {Element|string} opts.content - HTML 元素或字符串
   */
  constructor(opts = {}) {
    this.title = opts.title || "";
    this.size = opts.size || "md";
    this.dismissible = opts.dismissible !== false;
    this.onClose = opts.onClose;

    this.el = document.createElement("div");
    this.el.className = "cs-modal-backdrop";
    this.el.setAttribute("role", "dialog");
    this.el.setAttribute("aria-modal", "true");
    if (this.title) this.el.setAttribute("aria-label", this.title);

    this.el.innerHTML = `
      <div class="cs-modal cs-modal-${this.size}">
        ${this.title ? `<div class="cs-modal-header"><span class="cs-modal-title">${escape(this.title)}</span><button class="icon-btn" data-cs-role="close" aria-label="关闭"><i class="fas fa-times"></i></button></div>` : ""}
        <div class="cs-modal-body" data-cs-role="body"></div>
      </div>
    `;
    const body = this.el.querySelector('[data-cs-role="body"]');
    if (opts.content instanceof Element) {
      body.appendChild(opts.content);
    } else if (typeof opts.content === "string") {
      body.innerHTML = opts.content;
    }

    this.el.querySelector('[data-cs-role="close"]')
      ?.addEventListener("click", () => this.close());
    if (this.dismissible) {
      this.el.addEventListener("click", (e) => {
        if (e.target === this.el) this.close();
      });
    }
  }

  open() {
    document.body.appendChild(this.el);
    requestAnimationFrame(() => this.el.classList.add("open"));
    ACTIVE_MODALS.push(this);
    // Focus first focusable
    setTimeout(() => {
      const focusable = this.el.querySelector("input, button, textarea, [tabindex]");
      if (focusable) focusable.focus();
    }, 0);
    return this;
  }

  close() {
    this.el.classList.remove("open");
    setTimeout(() => {
      this.el.remove();
      const idx = ACTIVE_MODALS.indexOf(this);
      if (idx >= 0) ACTIVE_MODALS.splice(idx, 1);
      if (this.onClose) this.onClose();
    }, 180);
  }

  static closeAll() {
    [...ACTIVE_MODALS].forEach((m) => m.close());
  }
}

function escape(s) {
  if (s == null) return "";
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** 全局 Esc 监听(关闭顶层 modal) */
if (typeof window !== "undefined") {
  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && ACTIVE_MODALS.length > 0) {
      // 让 command palette 优先处理
      const top = ACTIVE_MODALS[ACTIVE_MODALS.length - 1];
      if (top?.handleEscape) {
        if (top.handleEscape()) return;
      }
      top.close();
    }
  });
}
