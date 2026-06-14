/**
 * cs-session-popover v1.0 — 历史会话弹出框
 * =========================================
 *
 * 取代 v2.0 时代的常驻侧边栏(O5.2 重构):用户点击 header 的"历史"按钮
 * 后,从按钮下方弹出 280-320px 的窄浮层,展示会话列表。
 *
 * 关键设计:
 *  - 不占用布局空间(absolute 定位,不影响 grid)
 *  - 点击外部或 Esc 关闭
 *  - 复用 Sidebar 的渲染逻辑(时间分组 / 搜索 / 重命名 / 删除)
 *  - 暴露与 Sidebar 兼容的 setSessions / setCurrent 接口
 *
 * API:
 *   const popover = new SessionPopover({ anchor, onSelect, onNew, onRename, onDelete });
 *   popover.open();       // 打开并定位到 anchor 下方
 *   popover.close();      // 关闭并移除 DOM
 *   popover.setSessions(sessions);
 *   popover.setCurrent(id);
 *   popover.isOpen();     // 当前是否打开
 */

import { escapeHtml, formatRelativeTime } from "../utils.js";

const TIME_BUCKETS = [
    { id: "today", label: "今天", maxAge: 24 * 60 * 60 * 1000 },
    { id: "yesterday", label: "昨天", maxAge: 2 * 24 * 60 * 60 * 1000 },
    { id: "week", label: "近 7 天", maxAge: 7 * 24 * 60 * 60 * 1000 },
    { id: "older", label: "更早" },
];

function bucketFor(ts) {
    if (!ts) return TIME_BUCKETS[3];
    const age = Date.now() - ts;
    for (const b of TIME_BUCKETS) {
        if (b.maxAge && age < b.maxAge) return b;
    }
    return TIME_BUCKETS[3];
}

export class SessionPopover {
    constructor(opts = {}) {
        this.anchor = opts.anchor || null;
        this.onSelect = opts.onSelect || (() => {});
        this.onNew = opts.onNew || (() => {});
        this.onRename = opts.onRename || (() => {});
        this.onDelete = opts.onDelete || (() => {});
        this.sessions = [];
        this.currentSessionId = null;
        this.searchQuery = "";
        this._open = false;
        this._outsideHandler = null;
        this._escHandler = null;
        this._renderShell();
    }

    _renderShell() {
        this.el = document.createElement("div");
        this.el.className = "cs-session-popover";
        this.el.setAttribute("role", "dialog");
        this.el.setAttribute("aria-label", "历史会话");
        this.el.hidden = true;
        this.el.innerHTML = `
            <div class="cs-session-popover-header">
                <div class="cs-session-popover-search">
                    <i class="fas fa-magnifying-glass" aria-hidden="true"></i>
                    <input
                        type="text"
                        class="cs-session-popover-search-input"
                        placeholder="搜索会话…"
                        aria-label="搜索会话"
                    />
                </div>
                <button
                    type="button"
                    class="cs-session-popover-new"
                    title="新会话 (Cmd+N)"
                    aria-label="新会话"
                >
                    <i class="fas fa-plus"></i>
                </button>
            </div>
            <div class="cs-session-popover-list" data-cs-role="list"></div>
            <div class="cs-session-popover-footer">
                <span>共 <span data-cs-role="count">0</span> 个会话</span>
            </div>
        `;
        this.listEl = this.el.querySelector('[data-cs-role="list"]');
        this.countEl = this.el.querySelector('[data-cs-role="count"]');
        this.searchInput = this.el.querySelector(".cs-session-popover-search-input");
        this.newBtn = this.el.querySelector(".cs-session-popover-new");

        this.newBtn.addEventListener("click", (e) => {
            e.stopPropagation();
            this.onNew();
        });
        this.searchInput.addEventListener("input", (e) => {
            this.searchQuery = e.target.value || "";
            this._renderList();
        });
        this.searchInput.addEventListener("click", (e) => e.stopPropagation());

        // 点击 popover 自身不关闭
        this.el.addEventListener("click", (e) => e.stopPropagation());
    }

    isOpen() {
        return this._open;
    }

    open() {
        if (this._open) return;
        if (!this.el.parentNode) {
            document.body.appendChild(this.el);
        }
        this.el.hidden = false;
        // 强制回流再设 position,确保 transition 生效
        // (避免 hidden→display 切换瞬间的样式闪烁)
        // eslint-disable-next-line no-unused-expressions
        this.el.offsetHeight;
        this._position();
        this._open = true;

        // 点击外部关闭
        this._outsideHandler = (e) => {
            if (!this._open) return;
            if (this.el.contains(e.target)) return;
            if (this.anchor && this.anchor.contains(e.target)) return;
            this.close();
        };
        // 用 capture: true 确保比按钮 click 早,但我们已在按钮里 stopPropagation
        setTimeout(() => {
            document.addEventListener("click", this._outsideHandler, true);
        }, 0);

        this._escHandler = (e) => {
            if (e.key === "Escape") this.close();
        };
        document.addEventListener("keydown", this._escHandler);

        // 自动 focus 搜索框
        requestAnimationFrame(() => this.searchInput.focus());
    }

    close() {
        if (!this._open) return;
        this.el.hidden = true;
        this._open = false;
        if (this._outsideHandler) {
            document.removeEventListener("click", this._outsideHandler, true);
            this._outsideHandler = null;
        }
        if (this._escHandler) {
            document.removeEventListener("keydown", this._escHandler);
            this._escHandler = null;
        }
    }

    toggle() {
        this._open ? this.close() : this.open();
    }

    _position() {
        if (!this.anchor) return;
        const rect = this.anchor.getBoundingClientRect();
        const popRect = this.el.getBoundingClientRect();
        const margin = 8;
        // 默认 anchor 正下方,左对齐
        let top = rect.bottom + margin;
        let left = rect.left;
        // 防止右侧溢出
        const maxLeft = window.innerWidth - popRect.width - 8;
        if (left > maxLeft) left = Math.max(8, maxLeft);
        // 防止底部溢出(若下方空间不够,改为向上弹)
        if (top + popRect.height > window.innerHeight - 8) {
            top = Math.max(8, rect.top - popRect.height - margin);
        }
        this.el.style.top = `${top}px`;
        this.el.style.left = `${left}px`;
    }

    setSessions(sessions) {
        this.sessions = sessions || [];
        this._renderList();
    }

    setCurrent(id) {
        this.currentSessionId = id;
        this._renderList();
    }

    _renderList() {
        if (!this.listEl) return;
        const q = this.searchQuery.trim().toLowerCase();
        const filtered = q
            ? this.sessions.filter((s) => {
                  const name = (s.name || "").toLowerCase();
                  const preview = (s.previewText || "").toLowerCase();
                  return name.includes(q) || preview.includes(q);
              })
            : this.sessions;

        if (this.countEl) this.countEl.textContent = String(filtered.length);

        if (filtered.length === 0) {
            // O11: 改进空状态 — 加图标 + 更明确的引导文案
            this.listEl.innerHTML = `
                <div class="cs-session-popover-empty">
                    <i class="fas ${q ? "fa-magnifying-glass" : "fa-comments"}"></i>
                    <div>${q ? "没有匹配的会话" : "还没有会话"}</div>
                    ${q ? "" : '<div class="hint">点击 + 开始新会话</div>'}
                </div>
            `;
            return;
        }

        const groups = new Map();
        for (const s of filtered) {
            const b = bucketFor(s.updatedAt || s.createdAt);
            if (!groups.has(b.id)) groups.set(b.id, { meta: b, items: [] });
            groups.get(b.id).items.push(s);
        }

        const html = [];
        for (const { meta, items } of groups.values()) {
            html.push(`<div class="cs-session-popover-group">`);
            html.push(`<div class="cs-session-popover-group-label">${escapeHtml(meta.label)}</div>`);
            for (const s of items) {
                const isActive = s.id === this.currentSessionId;
                const preview = s.previewText
                    ? `<span class="cs-session-popover-item-preview">${escapeHtml(s.previewText)}</span>`
                    : "";
                html.push(`
                    <div class="cs-session-popover-item${isActive ? " active" : ""}"
                         data-cs-session-id="${escapeHtml(s.id)}"
                         role="link" tabindex="0">
                        <div class="cs-session-popover-item-main">
                            <span class="cs-session-popover-item-name">${escapeHtml(s.name || "未命名会话")}</span>
                            ${preview}
                        </div>
                        <span class="cs-session-popover-item-time">${escapeHtml(formatRelativeTime(s.updatedAt || s.createdAt))}</span>
                        <span class="cs-session-popover-item-actions">
                            <button class="cs-session-popover-item-action" data-cs-action="rename" title="重命名" aria-label="重命名">
                                <i class="fas fa-pen"></i>
                            </button>
                            <button class="cs-session-popover-item-action danger" data-cs-action="delete" title="删除" aria-label="删除">
                                <i class="fas fa-trash"></i>
                            </button>
                        </span>
                    </div>
                `);
            }
            html.push(`</div>`);
        }
        this.listEl.innerHTML = html.join("");

        this.listEl.querySelectorAll(".cs-session-popover-item").forEach((item) => {
            const id = item.dataset.csSessionId;
            item.addEventListener("click", (e) => {
                if (e.target.closest(".cs-session-popover-item-action")) return;
                this.onSelect(id);
            });
            item.addEventListener("keydown", (e) => {
                if (e.key === "Enter") this.onSelect(id);
            });
            item.querySelectorAll(".cs-session-popover-item-action").forEach((btn) => {
                btn.addEventListener("click", (e) => {
                    e.stopPropagation();
                    const a = btn.dataset.csAction;
                    if (a === "rename") this.onRename(id);
                    else if (a === "delete") this.onDelete(id);
                });
            });
        });
    }

    destroy() {
        this.close();
        if (this.el.parentNode) this.el.parentNode.removeChild(this.el);
    }
}
