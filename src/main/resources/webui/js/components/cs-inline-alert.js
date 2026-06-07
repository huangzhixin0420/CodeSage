/**
 * Inline Alert v2.0
 * =================
 * 用于 turn 内错误/警告提示
 */

import { escapeHtml } from "../utils.js";

const VARIANT_META = {
    error: { icon: "fa-circle-exclamation", title: "错误" },
    warning: { icon: "fa-triangle-exclamation", title: "警告" },
    info: { icon: "fa-circle-info", title: "提示" },
    success: { icon: "fa-circle-check", title: "成功" },
};

export class InlineAlert {
    constructor(opts = {}) {
        const meta = VARIANT_META[opts.variant] || VARIANT_META.info;
        this.variant = opts.variant || "info";
        this.title = opts.title || meta.title;
        this.message = opts.message || "";

        this.el = document.createElement("div");
        this.el.className = `inline-alert ${this.variant}`;
        this.el.setAttribute("role", this.variant === "error" ? "alert" : "status");
        this.el.innerHTML = `
            <div class="inline-alert-icon">
                <i class="fas ${meta.icon}"></i>
            </div>
            <div class="inline-alert-body">
                <div class="inline-alert-title">${escapeHtml(this.title)}</div>
                <div class="inline-alert-message">${escapeHtml(this.message)}</div>
            </div>
        `;
    }

    destroy() {
        this.el.remove();
    }
}
