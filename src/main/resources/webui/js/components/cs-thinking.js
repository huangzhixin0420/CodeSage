/**
 * cs-thinking v2.0 — 思考卡片
 * ============================
 *
 * 设计核心 (对比 v1 修复):
 *   - 默认折叠为单行 chip:"已思考 3.4s · 1247 tokens"
 *   - 推理中(running)默认展开,带 3 点呼吸 + 实时计时
 *   - 完成后立刻折叠 (0ms,无 1.5s 延迟)
 *   - 折叠态:左边一条 accent 边作为视觉提示
 *   - 展开后:浅色背景 + monospace 文本,可滚动
 *   - 隐藏:有 showThinking 全局开关
 */

import { escapeHtml } from "../utils.js";

const RUNNING_DOT_HTML =
    '<span class="thinking-dot"></span>'.repeat(3);

export class Thinking {
    /**
     * @param {object} opts
     * @param {string} [opts.id]
     * @param {boolean} [opts.collapsed] - 初始折叠 (默认 false,展开)
     */
    constructor(opts = {}) {
        this.id =
            opts.id ||
            "thinking-" +
                Date.now() +
                "-" +
                Math.random().toString(36).slice(2, 7);
        this.state = "running";
        this.startTime = Date.now();
        this.elapsedMs = 0;
        this.tokenCount = 0;
        this.content = "";

        this.el = document.createElement("div");
        this.el.className = "thinking-card running";
        this.el.setAttribute("data-cs-thinking", this.id);
        this._render();
    }

    _render() {
        const isRunning = this.state === "running";
        const iconHtml = isRunning
            ? RUNNING_DOT_HTML
            : '<i class="fas fa-check"></i>';
        const labelText = isRunning
            ? "思考中"
            : `已思考 ${(this.elapsedMs / 1000).toFixed(1)}s`;
        const metaHtml =
            this.tokenCount > 0
                ? `<span class="thinking-meta">${this.tokenCount} tokens</span>`
                : "";
        const openClass = this.state === "running" ? " open" : "";
        const chevronClass = this.state === "running" ? " open" : "";

        this.el.classList.toggle("running", isRunning);
        this.el.classList.toggle("completed", !isRunning);

        this.el.innerHTML = `
            <div class="thinking-header" data-cs-role="header" role="button" tabindex="0">
                <div class="thinking-icon">${iconHtml}</div>
                <div class="thinking-label">
                    <span class="thinking-label-text">${escapeHtml(labelText)}</span>
                    ${metaHtml}
                </div>
                <i class="fas fa-chevron-down thinking-chevron${chevronClass}" data-cs-role="chevron"></i>
            </div>
            <div class="thinking-body${openClass}" data-cs-role="body">
                <div class="thinking-body-content" data-cs-role="content"></div>
            </div>
        `;

        this._bodyEl = this.el.querySelector('[data-cs-role="body"]');
        this._contentEl = this.el.querySelector('[data-cs-role="content"]');
        this._labelEl = this.el.querySelector(".thinking-label-text");
        this._headerEl = this.el.querySelector('[data-cs-role="header"]');

        if (this._contentEl && this.content) {
            this._contentEl.textContent = this.content;
        }

        this._headerEl.addEventListener("click", () => this.toggle());
        this._headerEl.addEventListener("keydown", (e) => {
            if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                this.toggle();
            }
        });
    }

    appendContent(text) {
        this.content += text;
        if (this._contentEl) this._contentEl.textContent = this.content;
        // 重新计算 token 估算(粗略:1 token ≈ 4 chars 英文 / 1.5 chars 中文)
        this.tokenCount = Math.ceil(this.content.length / 3);
        // 实时更新 header meta
        if (this.state === "running") {
            this._updateRunningMeta();
        }
    }

    setContent(text) {
        this.content = text;
        this.tokenCount = Math.ceil(text.length / 3);
        if (this._contentEl) this._contentEl.textContent = this.content;
    }

    _updateRunningMeta() {
        if (!this._labelEl) return;
        const elapsed = (Date.now() - this.startTime) / 1000;
        this._labelEl.textContent = `思考中 · ${elapsed.toFixed(1)}s`;
    }

    /** 标记完成 — 默认立即折叠 (修复 v1 的 1.5s 延迟) */
    complete(elapsedMs) {
        this.state = "completed";
        this.elapsedMs = elapsedMs ?? Date.now() - this.startTime;
        this._render();
        this._bodyEl.textContent = this.content;
        // 立即折叠
        this._bodyEl.classList.remove("open");
        this.el
            .querySelector('[data-cs-role="chevron"]')
            ?.classList.remove("open");
    }

    toggle() {
        if (!this._bodyEl) return;
        const isOpen = this._bodyEl.classList.toggle("open");
        this.el
            .querySelector('[data-cs-role="chevron"]')
            ?.classList.toggle("open", isOpen);
    }

    collapse() {
        this._bodyEl?.classList.remove("open");
        this.el
            ?.querySelector('[data-cs-role="chevron"]')
            ?.classList.remove("open");
    }

    expand() {
        this._bodyEl?.classList.add("open");
        this.el
            ?.querySelector('[data-cs-role="chevron"]')
            ?.classList.add("open");
    }

    destroy() {
        this.el.remove();
    }
}
