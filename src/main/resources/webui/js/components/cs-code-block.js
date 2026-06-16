/**
 * cs-code-block.js — 流式代码块卡片
 * ==================================
 *
 * 目标:接收后端 AgentStreamEvent.CodeBlock* 事件,实时渲染代码块
 * 并在完成时挂 5 按钮工具栏(应用/插入/创建/复制/拒绝/diff)。
 *
 * 设计原则(2026-06):
 *   - 替代 enhanceCodeBlocks(turn.content) 的 post-processing 路径
 *   - 同构 StructuredThinking:running / completed 两态,可折叠
 *   - 流式过程中(Delta)实时追加内容,完成(End)后挂工具栏
 *   - 自管:不被外层 markdown 增强二次处理(本组件就是终态)
 *
 * API:
 *   const card = new CodeBlockCard({ codeBlockId, language, filePath });
 *   card.appendContent("code chunk");   // 流式期间
 *   card.complete();                    // 标记完成,挂工具栏
 *   card.destroy();
 */

import { escapeHtml } from "../utils.js";
import { icon } from "../icons.js";

export class CodeBlockCard {
    /**
     * @param {object} opts
     * @param {string} opts.codeBlockId  - 后端分配的 ID
     * @param {string} [opts.language]   - lang 标识(kotlin/python/text 等)
     * @param {string} [opts.filePath]   - 可选,文件路径(供 diff/应用按钮)
     */
    constructor(opts) {
        this.codeBlockId = opts.codeBlockId;
        this.language = opts.language || "text";
        this.filePath = opts.filePath || null;
        this.state = "running"; // "running" | "completed"
        this.startTime = Date.now();
        this.content = "";

        this.el = document.createElement("div");
        this.el.className = "code-block-card running";
        this.el.setAttribute("data-cs-code-block", this.codeBlockId);
        this.el.setAttribute("data-cs-lang", this.language);
        this._render();
    }

    _render() {
        const isRunning = this.state === "running";
        const labelText = isRunning
            ? "生成代码中…"
            : `代码块 (${this.language})`;

        this.el.className = `code-block-card ${isRunning ? "running" : "completed"}`;

        this.el.innerHTML = `
            <div class="code-block-header" data-cs-role="header">
                <div class="code-block-lang" data-cs-role="lang">${escapeHtml(this.language)}</div>
                <div class="code-block-actions" data-cs-role="actions">
                    ${isRunning ? "" : this._renderActions()}
                </div>
            </div>
            <div class="code-block-body" data-cs-role="body">
                <pre><code class="language-${escapeHtml(this.language)}">${escapeHtml(this.content)}</code></pre>
            </div>
        `;

        this._bodyEl = this.el.querySelector('[data-cs-role="body"]');
        this._actionsEl = this.el.querySelector('[data-cs-role="actions"]');

        if (!isRunning) {
            this._bindActions();
        }
    }

    _renderActions() {
        // 5 按钮(移植自 enhanceCodeBlocks _buildNonDiffBlock):
        // 应用到编辑器 / 插入光标处 / 创建文件 / 复制 / 拒绝(删除)
        // + Diff(仅当有 filePath 时)
        return `
            <button class="code-block-action" data-cs-action="apply" title="应用到编辑器"><i class="fas fa-file-import"></i></button>
            <button class="code-block-action" data-cs-action="insert" title="插入光标处"><i class="fas fa-i-cursor"></i></button>
            <button class="code-block-action" data-cs-action="create" title="创建文件"><i class="fas fa-file-circle-plus"></i></button>
            <button class="code-block-action" data-cs-action="copy" title="复制"><i class="fas fa-copy"></i></button>
            ${this.filePath ? '<button class="code-block-action" data-cs-action="diff" title="Diff 对比"><i class="fas fa-code-compare"></i></button>' : ""}
            <button class="code-block-action code-block-action-reject" data-cs-action="reject" title="拒绝(删除)"><i class="fas fa-xmark"></i></button>
        `;
    }

    _bindActions() {
        if (!this._actionsEl) return;
        const sendAction = (type) => {
            if (window.bridge?.bridgeReady) {
                window.bridge.send({
                    type,
                    code: this.content,
                    language: this.language,
                    filePath: this.filePath,
                });
            } else {
                console.warn(`[CodeBlockCard] bridge not ready, cannot send ${type}`);
            }
        };
        this._actionsEl.querySelectorAll('[data-cs-action]').forEach((btn) => {
            const action = btn.dataset.csAction;
            btn.addEventListener("click", () => {
                if (action === "copy") {
                    navigator.clipboard?.writeText(this.content).then(() => {
                        const i = btn.querySelector("i");
                        if (i) {
                            i.className = "fas fa-check";
                            setTimeout(() => { i.className = "fas fa-copy"; }, 1500);
                        }
                    });
                } else if (action === "reject") {
                    // 拒绝:从 DOM 移除
                    if (this.el.parentNode) this.el.parentNode.removeChild(this.el);
                    sendAction("reject_code_block");
                } else {
                    sendAction(`${action}_code_block`);
                }
            });
        });
    }

    /**
     * 流式期间:追加代码内容(实时更新 body)
     */
    appendContent(text) {
        if (!text) return;
        this.content += text;
        if (this._bodyEl) {
            this._bodyEl.innerHTML =
                `<pre><code class="language-${escapeHtml(this.language)}">${escapeHtml(this.content)}</code></pre>`;
        }
    }

    /**
     * 标记完成,挂工具栏
     */
    complete() {
        this.state = "completed";
        this._render();
    }

    /**
     * 销毁卡片(从 DOM 移除)
     */
    destroy() {
        this.el.remove();
    }
}
