/**
 * cs-inline-alert 组件 — 行内提示
 *
 * 替代原来零散的 success/error/warning 文本
 */

import { escapeHtml } from "../utils.js";

const VARIANT_ICONS = {
    info: "fa-info-circle",
    success: "fa-check-circle",
    warning: "fa-exclamation-triangle",
    error: "fa-times-circle",
};

export class InlineAlert {
    /**
     * @param {object} opts
     * @param {"info"|"success"|"warning"|"error"} [opts.variant]
     * @param {string} [opts.title]
     * @param {string} opts.message
     * @param {Array<{label,action,variant?}>} [opts.actions]
     */
    constructor(opts = {}) {
        this.variant = opts.variant || "info";
        this.title = opts.title || "";
        this.message = opts.message || "";
        this.actions = opts.actions || [];
        this.dismissible = !!opts.dismissible;

        this.el = document.createElement("div");
        this.el.className = `inline-alert ${this.variant}`;
        this.el.setAttribute("role", this.variant === "error" ? "alert" : "status");
        this._render();
    }

    _render() {
        const icon = VARIANT_ICONS[this.variant] || VARIANT_ICONS.info;
        this.el.innerHTML = `
            <i class="fas ${icon} alert-icon"></i>
            <div class="alert-body">
                ${this.title ? `<div class="alert-title">${escapeHtml(this.title)}</div>` : ""}
                <div class="alert-message">${escapeHtml(this.message)}</div>
                ${this.actions.length > 0 ? `<div class="alert-actions"></div>` : ""}
            </div>
            ${this.dismissible ? '<button class="icon-btn" aria-label="关闭" data-cs-role="dismiss"><i class="fas fa-times"></i></button>' : ""}
        `;
        const actionsEl = this.el.querySelector(".alert-actions");
        if (actionsEl) {
            for (const a of this.actions) {
                const btn = document.createElement("button");
                btn.className = `cs-button size-sm variant-${a.variant || "secondary"}`;
                btn.textContent = a.label;
                btn.addEventListener("click", () => a.action?.());
                actionsEl.appendChild(btn);
            }
        }
        if (this.dismissible) {
            this.el.querySelector('[data-cs-role="dismiss"]')
                .addEventListener("click", () => this.destroy());
        }
    }

    destroy() {
        this.el.remove();
    }
}
