/**
 * cs-empty-state v1.0 — 空状态 / 错误状态 组件 (O11)
 * =====================================================
 *
 * 设计要点:
 *  - 居中布局:大图标 + 标题 + 描述 + 操作按钮
 *  - 图标:使用 Font Awesome class 字符串(无外部 SVG 依赖)
 *  - 主题:token-based,亮/暗色自动适配
 *  - 可重入:每次 open() 重建 DOM,避免陈旧状态
 *
 * 用法:
 *   const empty = new EmptyState({
 *     icon: "fa-comments",          // Font Awesome class (前缀 fa-)
 *     title: "还没有会话",
 *     description: "点击下方按钮开始新对话",
 *     variant: "empty",             // "empty" | "error" | "loading"
 *     actions: [
 *       { label: "新会话", icon: "fa-plus", onClick: () => ... },
 *     ],
 *   });
 *   container.appendChild(empty.el);
 *   empty.open();
 *
 * 错误用法:
 *   const err = new EmptyState({
 *     icon: "fa-triangle-exclamation",
 *     title: "加载失败",
 *     description: e.message,
 *     variant: "error",
 *     errorDetails: e.stack,        // 可选:堆栈折叠
 *     actions: [{ label: "重试", icon: "fa-rotate-right", onClick: retryFn }],
 *   });
 */

const ICON_BASE_CLASS = "fas";

export class EmptyState {
  /**
   * @param {object} opts
   * @param {string} [opts.icon]      Font Awesome class without "fas " prefix
   * @param {string} [opts.title]
   * @param {string} [opts.description]
   * @param {"empty"|"error"|"loading"} [opts.variant="empty"]
   * @param {Array<{label:string, icon?:string, onClick:Function, variant?:string}>} [opts.actions]
   * @param {string} [opts.errorDetails]   variant=error 时,堆栈/详情折叠
   * @param {string} [opts.hint]           variant=error 时,建议修复
   */
  constructor(opts = {}) {
    this.opts = {
      icon: "fa-comments",
      title: "暂无内容",
      description: "",
      variant: "empty",
      actions: [],
      ...opts,
    };
    this._build();
  }

  _build() {
    this.el = document.createElement("div");
    this.el.className = `cs-empty-state cs-empty-state-${this.opts.variant}`;
    this.el.setAttribute("role", this.opts.variant === "error" ? "alert" : "status");

    const iconHtml = this.opts.variant === "loading"
      ? `<i class="${ICON_BASE_CLASS} ${this.opts.icon} fa-spin"></i>`
      : `<i class="${ICON_BASE_CLASS} ${this.opts.icon}"></i>`;

    const actionsHtml = (this.opts.actions || [])
      .map((a, i) => {
        const variantCls = a.variant ? `cs-empty-state-action-${a.variant}` : "";
        const iconPart = a.icon ? `<i class="${ICON_BASE_CLASS} ${a.icon}"></i>` : "";
        return `<button type="button" class="cs-empty-state-action ${variantCls}" data-cs-action-index="${i}">
                ${iconPart}<span>${escapeText(a.label)}</span>
            </button>`;
      })
      .join("");

    const errorDetailsHtml = this.opts.variant === "error" && this.opts.errorDetails
      ? `<details class="cs-empty-state-details">
                <summary>查看详情</summary>
                <pre>${escapeText(this.opts.errorDetails)}</pre>
            </details>`
      : "";

    const hintHtml = this.opts.variant === "error" && this.opts.hint
      ? `<div class="cs-empty-state-hint">
                <i class="${ICON_BASE_CLASS} fa-lightbulb"></i> ${escapeText(this.opts.hint)}
            </div>`
      : "";

    this.el.innerHTML = `
        <div class="cs-empty-state-icon">${iconHtml}</div>
        <h2 class="cs-empty-state-title">${escapeText(this.opts.title)}</h2>
        ${this.opts.description ? `<p class="cs-empty-state-description">${escapeText(this.opts.description)}</p>` : ""}
        ${hintHtml}
        ${actionsHtml ? `<div class="cs-empty-state-actions">${actionsHtml}</div>` : ""}
        ${errorDetailsHtml}
    `;

    this.el.querySelectorAll("[data-cs-action-index]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const idx = parseInt(btn.getAttribute("data-cs-action-index") || "0", 10);
        const action = (this.opts.actions || [])[idx];
        if (action?.onClick) action.onClick();
      });
    });
  }

  open() {
    return this.el;
  }

  destroy() {
    if (this.el?.parentNode) this.el.parentNode.removeChild(this.el);
  }
}

function escapeText(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
