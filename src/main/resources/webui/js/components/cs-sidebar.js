/**
 * cs-sidebar v2.0 — 会话侧边栏
 * ==============================
 *
 * 三栏布局的左栏,260px,可折叠到 0。
 * 与 v1 区别:
 *   - 顶栏独立 (header-brand) 简化
 *   - 搜索框 always-visible
 *   - 会话项:左侧 2px accent 指示
 *   - hover 显示操作菜单 (rename/delete)
 *   - 时间分组:今天 / 昨天 / 近 7 天 / 更早
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

export class Sidebar {
    constructor(opts = {}) {
        this.container = opts.container;
        this.collapsed = false;
        this.sessions = [];
        this.currentSessionId = null;
        this.searchQuery = "";
        this.onSelect = opts.onSelect || (() => {});
        this.onNew = opts.onNew || (() => {});
        this.onRename = opts.onRename || (() => {});
        this.onDelete = opts.onDelete || (() => {});

        // v2.0 修复:容器里已经有 <aside class="cs-sidebar" id="cs-sidebar"></aside> 占位元素,
        // 旧实现新建一个 <aside> 但从来不 appendChild 进去 → 整个 sidebar 活在内存里、用户看不到。
        // 改为:如果 opts.container 里有 .cs-sidebar 占位,直接复用该元素,在里面渲染;
        // 否则才新建 + appendChild(单测 / 嵌入式场景用)。
        const existing = this.container?.querySelector?.(".cs-sidebar");
        if (existing) {
            this.el = existing;
            // 清理占位元素的 id(可选),保留 class,这样 v2.0 collapse 等 CSS 选择器继续命中
        } else {
            this.el = document.createElement("aside");
            this.el.className = "cs-sidebar";
            this.container?.appendChild?.(this.el);
        }
        this.el.classList.add("cs-sidebar");
        this.el.setAttribute("role", "navigation");
        this.el.setAttribute("aria-label", "会话列表");
        this._render();
    }

    setCollapsed(collapsed) {
        this.collapsed = collapsed;
        if (collapsed) {
            this.el.style.width = "0";
        } else {
            this.el.style.width = "";
        }
    }

    _render() {
        this.el.innerHTML = `
            <div class="sidebar-header">
                <div class="sidebar-brand">
                    <div class="sidebar-brand-mark" aria-hidden="true">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M12 2L4 7v10l8 5 8-5V7l-8-5z" />
                            <path d="M12 22V12" />
                            <path d="M4 7l8 5 8-5" />
                        </svg>
                    </div>
                    <span class="sidebar-brand-text">CodeSage</span>
                </div>
                <button class="sidebar-new-btn" data-cs-role="new" title="新会话 (Cmd+N)" aria-label="新会话">
                    <i class="fas fa-plus"></i>
                </button>
            </div>
            <div class="sidebar-search">
                <div class="sidebar-search-wrap">
                    <i class="fas fa-magnifying-glass"></i>
                    <input
                        class="sidebar-search-input"
                        type="text"
                        placeholder="搜索会话…"
                        data-cs-role="search"
                        aria-label="搜索会话"
                    />
                </div>
            </div>
            <div class="sidebar-list" data-cs-role="list"></div>
            <div class="sidebar-footer">
                <button class="sidebar-footer-btn" data-cs-role="settings" title="设置">
                    <i class="fas fa-gear"></i> 设置
                </button>
            </div>
        `;

        this.listEl = this.el.querySelector('[data-cs-role="list"]');

        this.el.querySelector('[data-cs-role="new"]').addEventListener("click", () => {
            this.onNew();
        });
        this.el.querySelector('[data-cs-role="search"]').addEventListener("input", (e) => {
            this.searchQuery = e.target.value || "";
            this._renderList();
        });
        this.el.querySelector('[data-cs-role="settings"]').addEventListener("click", () => {
            if (window.CodeSage?.openSettings) window.CodeSage.openSettings();
        });
        this._renderList();
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
            ? this.sessions.filter((s) =>
                  (s.name || "").toLowerCase().includes(q),
              )
            : this.sessions;

        if (filtered.length === 0) {
            this.listEl.innerHTML = `
                <div class="sidebar-empty">
                    ${q ? "没有匹配的会话" : "还没有会话<br><span style='font-size:11px;'>点击 + 开始新会话</span>"}
                </div>
            `;
            return;
        }

        // 分组
        const groups = new Map();
        for (const s of filtered) {
            const b = bucketFor(s.updatedAt || s.createdAt);
            if (!groups.has(b.id)) groups.set(b.id, { meta: b, items: [] });
            groups.get(b.id).items.push(s);
        }

        const html = [];
        for (const { meta, items } of groups.values()) {
            html.push(`<div class="sidebar-group">`);
            html.push(`<div class="sidebar-group-label">${escapeHtml(meta.label)}</div>`);
            for (const s of items) {
                const isActive = s.id === this.currentSessionId;
                html.push(`
                    <div class="sidebar-item${isActive ? " active" : ""}"
                         data-cs-session-id="${escapeHtml(s.id)}"
                         role="link" tabindex="0">
                        <span class="sidebar-item-icon"><i class="fas fa-message"></i></span>
                        <span class="sidebar-item-name">${escapeHtml(s.name || "未命名会话")}</span>
                        <span class="sidebar-item-time">${escapeHtml(formatRelativeTime(s.updatedAt || s.createdAt))}</span>
                        <span class="sidebar-item-actions">
                            <button class="sidebar-item-action" data-cs-action="rename" title="重命名" aria-label="重命名">
                                <i class="fas fa-pen"></i>
                            </button>
                            <button class="sidebar-item-action danger" data-cs-action="delete" title="删除" aria-label="删除">
                                <i class="fas fa-trash"></i>
                            </button>
                        </span>
                    </div>
                `);
            }
            html.push(`</div>`);
        }
        this.listEl.innerHTML = html.join("");

        // 绑定事件
        this.listEl.querySelectorAll(".sidebar-item").forEach((item) => {
            const id = item.dataset.csSessionId;
            item.addEventListener("click", (e) => {
                if (e.target.closest(".sidebar-item-action")) return;
                this.onSelect(id);
            });
            item.addEventListener("keydown", (e) => {
                if (e.key === "Enter") {
                    this.onSelect(id);
                }
            });
            item.querySelectorAll(".sidebar-item-action").forEach((btn) => {
                btn.addEventListener("click", (e) => {
                    e.stopPropagation();
                    const a = btn.dataset.csAction;
                    if (a === "rename") this.onRename(id);
                    else if (a === "delete") this.onDelete(id);
                });
            });
        });
    }
}
