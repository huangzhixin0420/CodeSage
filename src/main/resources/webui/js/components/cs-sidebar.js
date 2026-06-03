/**
 * cs-sidebar 组件 — 会话侧边栏(对齐 SESSION_SIDEBAR_REDESIGN.md)
 *
 * 设计:
 *  - 内嵌在 app-container 左侧,可折叠(默认折叠,260px 展开)
 *  - 顶栏:Logo + 新建会话按钮 + 折叠 toggle
 *  - 搜索框(可选)
 *  - 会话列表按时间分组:Today / Yesterday / Previous 7 Days / Older
 *  - 会话项:左侧 3px accent 指示 + 名称 + 副标题(相对时间)+ hover 显示操作菜单
 *  - inline 重命名 / inline 删除确认(无弹窗)
 *  - 底栏:设置入口
 */

import { escapeHtml, escapeJs, formatRelativeTime } from "../utils.js";

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
  /**
   * @param {object} opts
   * @param {HTMLElement} opts.container - .app-container
   * @param {boolean} [opts.initialCollapsed]
   */
  constructor(opts = {}) {
    this.container = opts.container;
    this.collapsed = opts.initialCollapsed !== false;
    this.sessions = [];
    this.currentSessionId = null;
    this.searchQuery = "";
    this.pendingAction = null; // 用于 inline 删除确认

    // === DOM ===
    this.el = document.createElement("aside");
    this.el.className = "cs-sidebar" + (this.collapsed ? " collapsed" : "");
    this.el.setAttribute("role", "navigation");
    this.el.setAttribute("aria-label", "会话侧边栏");
    this.el.innerHTML = `
      <div class="cs-sidebar-header">
        <button class="cs-sidebar-toggle" data-cs-role="toggle" aria-label="折叠/展开侧边栏">
          <i class="fas fa-bars"></i>
        </button>
        <div class="cs-sidebar-brand">
          <i class="fas fa-wand-magic-sparkles"></i>
          <span>CodeSage</span>
        </div>
        <button class="cs-sidebar-new" data-cs-role="new" data-cs-tooltip="新会话 (Cmd+N)">
          <i class="fas fa-plus"></i>
        </button>
      </div>
      <div class="cs-sidebar-search">
        <i class="fas fa-search"></i>
        <input type="text" data-cs-role="search" placeholder="搜索会话..." />
      </div>
      <div class="cs-sidebar-content" data-cs-role="content">
        ${this._renderEmpty()}
      </div>
      <div class="cs-sidebar-footer">
        <button class="cs-sidebar-footer-btn" data-cs-role="settings">
          <i class="fas fa-sliders"></i>
          <span>设置</span>
        </button>
        <button class="cs-sidebar-footer-btn" data-cs-role="command-palette" data-cs-tooltip="命令面板 (Cmd+K)">
          <i class="fas fa-terminal"></i>
          <span>命令</span>
        </button>
      </div>
    `;
    this.container.insertBefore(this.el, this.container.firstChild);

    // === Bind ===
    this.el.querySelector('[data-cs-role="toggle"]').addEventListener("click", () => this.toggle());
    this.el.querySelector('[data-cs-role="new"]').addEventListener("click", () => this.onNewSession?.());
    this.el.querySelector('[data-cs-role="search"]').addEventListener("input", (e) => {
      this.searchQuery = e.target.value.trim().toLowerCase();
      this._renderContent();
    });
    this.el.querySelector('[data-cs-role="settings"]').addEventListener("click", () => this.onSettings?.());
    this.el.querySelector('[data-cs-role="command-palette"]').addEventListener("click", () => {
      // 触发全局 Cmd+K(派发到 document)
      document.dispatchEvent(new KeyboardEvent("keydown", { key: "k", metaKey: true, ctrlKey: true, bubbles: true }));
    });
  }

  // ===== Public API =====
  setSessions(sessions, currentId) {
    this.sessions = sessions || [];
    this.currentSessionId = currentId;
    this._renderContent();
  }

  addSession(session) {
    if (!session) return;
    const existing = this.sessions.findIndex((s) => s.id === session.id);
    if (existing >= 0) this.sessions[existing] = session;
    else this.sessions.unshift(session);
    this._renderContent();
  }

  removeSession(sessionId) {
    this.sessions = this.sessions.filter((s) => s.id !== sessionId);
    if (this.currentSessionId === sessionId) this.currentSessionId = null;
    this._renderContent();
  }

  updateSession(sessionId, patch) {
    const s = this.sessions.find((x) => x.id === sessionId);
    if (!s) return;
    Object.assign(s, patch);
    this._renderContent();
  }

  setCurrentSession(sessionId) {
    this.currentSessionId = sessionId;
    this._renderContent();
  }

  toggle() {
    this.collapsed = !this.collapsed;
    this._applyCollapsed();
  }

  setCollapsed(collapsed) {
    if (this.collapsed === collapsed) return;
    this.collapsed = collapsed;
    this._applyCollapsed();
  }

  // ===== Private =====
  _applyCollapsed() {
    this.el.classList.toggle("collapsed", this.collapsed);
    this.container.classList.toggle("sidebar-collapsed", this.collapsed);
  }

  _renderEmpty() {
    return `<div class="cs-sidebar-empty">
        <i class="fas fa-comments" style="font-size:24px;color:var(--fg-3);"></i>
        <p>还没有会话</p>
        <p class="cs-sidebar-empty-hint">点击 + 开始新对话</p>
      </div>`;
  }

  _renderContent() {
    const content = this.el.querySelector('[data-cs-role="content"]');
    let sessions = this.sessions;
    if (this.searchQuery) {
      sessions = sessions.filter(
        (s) =>
          (s.name || "").toLowerCase().includes(this.searchQuery) ||
          (s.preview || "").toLowerCase().includes(this.searchQuery),
      );
    }
    if (!sessions.length) {
      content.innerHTML = this.searchQuery
        ? `<div class="cs-sidebar-empty"><i class="fas fa-search" style="font-size:24px;color:var(--fg-3);"></i><p>无匹配结果</p></div>`
        : this._renderEmpty();
      this._wireSessionEvents(content);
      return;
    }

    // 分组
    const groups = new Map();
    for (const s of sessions) {
      const bucket = bucketFor(s.lastActivityAt || s.createdAt);
      if (!groups.has(bucket.id)) groups.set(bucket.id, { label: bucket.label, items: [] });
      groups.get(bucket.id).items.push(s);
    }
    const sortedGroupIds = ["today", "yesterday", "week", "older"].filter((id) => groups.has(id));

    content.innerHTML = sortedGroupIds
      .map((gid) => {
        const g = groups.get(gid);
        g.items.sort((a, b) => (b.lastActivityAt || 0) - (a.lastActivityAt || 0));
        return `
          <div class="cs-sidebar-group">
            <div class="cs-sidebar-group-label">${escapeHtml(g.label)}</div>
            ${g.items.map((s) => this._renderSessionItem(s)).join("")}
          </div>
        `;
      })
      .join("");
    this._wireSessionEvents(content);
  }

  _renderSessionItem(s) {
    const isCurrent = s.id === this.currentSessionId;
    const inEdit = this.pendingAction?.sessionId === s.id && this.pendingAction?.type === "rename";
    const inDelete = this.pendingAction?.sessionId === s.id && this.pendingAction?.type === "delete-confirm";
    return `
      <div class="cs-sidebar-item ${isCurrent ? "current" : ""} ${inEdit ? "editing" : ""} ${inDelete ? "deleting" : ""}" data-session-id="${escapeJs(s.id)}" data-session-name="${escapeJs(s.name || "")}">
        <div class="cs-sidebar-item-indicator"></div>
        ${inEdit
          ? `<input type="text" class="cs-sidebar-item-input" data-cs-role="rename-input" value="${escapeHtml(s.name || "")}" />`
          : `<div class="cs-sidebar-item-name">${escapeHtml(s.name || "新会话")}</div>
             <div class="cs-sidebar-item-meta">${escapeHtml(formatRelativeTime(s.lastActivityAt || s.createdAt))}${s.messageCount ? ` · ${s.messageCount} 条` : ""}</div>`}
        ${inEdit
          ? `<div class="cs-sidebar-item-actions">
               <button class="cs-sidebar-item-action" data-cs-action="rename-confirm" data-cs-tooltip="确认 (Enter)"><i class="fas fa-check"></i></button>
               <button class="cs-sidebar-item-action" data-cs-action="rename-cancel" data-cs-tooltip="取消 (Esc)"><i class="fas fa-times"></i></button>
             </div>`
          : inDelete
            ? `<div class="cs-sidebar-item-actions">
                 <button class="cs-sidebar-item-action danger" data-cs-action="delete-confirm" data-cs-tooltip="确认删除"><i class="fas fa-trash"></i>&nbsp;确认</button>
                 <button class="cs-sidebar-item-action" data-cs-action="delete-cancel" data-cs-tooltip="取消"><i class="fas fa-times"></i></button>
               </div>`
            : `<div class="cs-sidebar-item-actions">
                 <button class="cs-sidebar-item-action" data-cs-action="rename" data-cs-tooltip="重命名"><i class="fas fa-pen"></i></button>
                 <button class="cs-sidebar-item-action danger" data-cs-action="delete" data-cs-tooltip="删除"><i class="fas fa-trash"></i></button>
               </div>`}
      </div>
    `;
  }

  _wireSessionEvents(content) {
    content.querySelectorAll(".cs-sidebar-item").forEach((el) => {
      const sid = el.dataset.sessionId;
      const sname = el.dataset.sessionName;
      el.addEventListener("click", (e) => {
        const action = e.target.closest("[data-cs-action]")?.dataset.csAction;
        if (action) {
          e.stopPropagation();
          this._handleAction(sid, action, el);
          return;
        }
        // 切换会话
        if (!el.classList.contains("editing") && !el.classList.contains("deleting")) {
          this.onSwitchSession?.(sid);
        }
      });

      // 键盘
      const input = el.querySelector('[data-cs-role="rename-input"]');
      if (input) {
        setTimeout(() => {
          input.focus();
          input.select();
        }, 0);
        input.addEventListener("keydown", (e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            this._handleAction(sid, "rename-confirm", el, input.value.trim());
          } else if (e.key === "Escape") {
            e.preventDefault();
            this._handleAction(sid, "rename-cancel", el);
          }
        });
      }
    });
  }

  _handleAction(sessionId, action, el, value) {
    switch (action) {
      case "rename":
        this.pendingAction = { sessionId, type: "rename" };
        this._renderContent();
        break;
      case "rename-confirm": {
        const newName = (value && value.trim()) || el.querySelector('[data-cs-role="rename-input"]')?.value?.trim();
        this.pendingAction = null;
        if (newName) {
          this.onRenameSession?.(sessionId, newName);
        }
        this._renderContent();
        break;
      }
      case "rename-cancel":
        this.pendingAction = null;
        this._renderContent();
        break;
      case "delete":
        this.pendingAction = { sessionId, type: "delete-confirm" };
        this._renderContent();
        break;
      case "delete-confirm":
        this.pendingAction = null;
        this.onDeleteSession?.(sessionId);
        this._renderContent();
        break;
      case "delete-cancel":
        this.pendingAction = null;
        this._renderContent();
        break;
    }
  }
}
