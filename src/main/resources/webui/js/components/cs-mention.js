/**
 * cs-mention.js — 输入区 @/# 自动补全
 * ===================================
 *
 * 目标：在 textarea 中输入 `@` 或 `#` 时弹出自动补全面板，
 * 选中后插入文件引用或上下文标记。
 *
 * 当前实现：
 *   - 触发规则：输入 `@` 或 `#` 后至少 1 个字符。
 *   - 本地候选 + 可选后端 file_search（通过 opts.onSearch 注入）。
 *   - 键盘导航：↑/↓/Enter/Esc。
 *   - 选中后替换触发文本为 `@path ` 或 `#selection `。
 */

import { escapeHtml } from "../utils.js";

const DEFAULT_CONTEXT_ITEMS = [
  {
    type: "context",
    value: "selection",
    label: "#selection",
    hint: "当前选中的代码",
  },
  {
    type: "context",
    value: "clipboard",
    label: "#clipboard",
    hint: "剪贴板内容",
  },
  {
    type: "context",
    value: "terminal",
    label: "#terminal",
    hint: "最近终端输出",
  },
];

const DEFAULT_FILE_ITEMS = [
  {
    type: "file",
    path: "src/main/kotlin/Agent.kt",
    label: "Agent.kt",
    hint: "最近打开",
  },
  {
    type: "file",
    path: "src/main/kotlin/Main.kt",
    label: "Main.kt",
    hint: "最近打开",
  },
];

export class MentionAutocomplete {
  constructor(opts = {}) {
    this.textarea = opts.textarea;
    this.onSearch = opts.onSearch || null; // async (query) => [{ path, label, hint }]
    this.onSelect = opts.onSelect || null; // (item) => void
    this.insertAsChip = opts.insertAsChip || false; // true = 不修改 textarea,交给 onSelect 渲染 chip
    this._popup = null;
    this._items = [];
    this._selectedIndex = 0;
    this._triggerInfo = null; // { start, end, char }
    this._debounceTimer = null;

    if (this.textarea) this._attach();
  }

  _attach() {
    this.textarea.addEventListener("input", (e) => this._onInput(e));
    this.textarea.addEventListener("keydown", (e) => this._onKeydown(e));
    document.addEventListener("click", (e) => {
      if (!this._popup) return;
      if (!this._popup.contains(e.target) && e.target !== this.textarea) {
        this._close();
      }
    });
  }

  _onInput(_e) {
    this._checkTrigger();
  }

  _onKeydown(e) {
    if (!this._popup) return;
    switch (e.key) {
      case "ArrowDown":
        e.preventDefault();
        this._moveSelection(1);
        break;
      case "ArrowUp":
        e.preventDefault();
        this._moveSelection(-1);
        break;
      case "Enter":
        e.preventDefault();
        this._selectItem(this._selectedIndex);
        break;
      case "Escape":
        e.preventDefault();
        this._close();
        break;
    }
  }

  _checkTrigger() {
    const value = this.textarea.value;
    const cursor = this.textarea.selectionStart;

    // 从光标向前找最近的 @ 或 #
    let start = -1;
    let triggerChar = "";
    for (let i = cursor - 1; i >= 0; i--) {
      const ch = value[i];
      if (ch === "@" || ch === "#") {
        start = i;
        triggerChar = ch;
        break;
      }
      if (/\s/.test(ch)) break;
    }

    if (start < 0) {
      this._close();
      return;
    }

    const query = value.slice(start + 1, cursor);
    // 触发文本中不能包含空白
    if (/\s/.test(query)) {
      this._close();
      return;
    }

    this._triggerInfo = { start, end: cursor, char: triggerChar };
    this._updateItems(query, triggerChar);
  }

  async _updateItems(query, triggerChar) {
    let fileItems = [];
    if (this.onSearch && triggerChar === "@") {
      try {
        fileItems = (await this.onSearch(query)) || [];
      } catch (e) {
        console.warn("[MentionAutocomplete] search failed:", e);
      }
    }
    if (!fileItems.length && triggerChar === "@") {
      fileItems = DEFAULT_FILE_ITEMS.filter((it) =>
        it.label.toLowerCase().includes(query.toLowerCase()),
      );
    }

    const contextItems =
      triggerChar === "#"
        ? DEFAULT_CONTEXT_ITEMS.filter((it) =>
            it.label.toLowerCase().includes(query.toLowerCase()),
          )
        : [];

    this._items = [
      ...fileItems.map((it) => ({
        type: "file",
        label: it.label,
        value: it.path,
        hint: it.hint || "文件",
        icon: "fa-file-code",
      })),
      ...contextItems.map((it) => ({
        type: "context",
        label: it.label,
        value: it.value,
        hint: it.hint,
        icon: "fa-i-cursor",
      })),
    ];

    this._selectedIndex = 0;
    if (this._items.length) {
      this._renderPopup();
    } else {
      this._close();
    }
  }

  _renderPopup() {
    if (!this._popup) {
      this._popup = document.createElement("div");
      this._popup.className = "mention-popup";
      document.body.appendChild(this._popup);
    }

    const rect = this.textarea.getBoundingClientRect();
    this._popup.style.position = "fixed";
    this._popup.style.left = `${rect.left}px`;
    this._popup.style.top = `${rect.top - 8}px`;
    this._popup.style.transform = "translateY(-100%)";
    this._popup.style.zIndex = "1000";

    const html = this._items
      .map(
        (it, i) => `
                    <div class="mention-item${i === this._selectedIndex ? " selected" : ""}" data-index="${i}">
                        <i class="fas ${it.icon}"></i>
                        <span class="mention-label">${escapeHtml(it.label)}</span>
                        <span class="mention-hint">${escapeHtml(it.hint)}</span>
                    </div>
                `,
      )
      .join("");

    this._popup.innerHTML = html;

    this._popup.querySelectorAll(".mention-item").forEach((el) => {
      el.addEventListener("click", () => {
        this._selectItem(parseInt(el.dataset.index, 10));
      });
    });
  }

  _moveSelection(delta) {
    if (!this._items.length) return;
    this._selectedIndex =
      (this._selectedIndex + delta + this._items.length) % this._items.length;
    this._renderPopup();
  }

  _selectItem(index) {
    const item = this._items[index];
    if (!item || !this._triggerInfo) return;

    if (this.insertAsChip) {
      // chip 模式:不修改 textarea,由外部渲染 chip
      if (this.onSelect) this.onSelect(item);
      this._close();
      // 触发 input 事件让外部更新
      const win = this.textarea?.ownerDocument?.defaultView || window;
      this.textarea.dispatchEvent(new win.Event("input", { bubbles: true }));
      return;
    }

    const { start, end } = this._triggerInfo;
    const value = this.textarea.value;
    let insertion;
    if (item.type === "file") {
      insertion = `@${item.value} `;
    } else {
      insertion = `${item.label} `;
    }

    const before = value.slice(0, start);
    const after = value.slice(end);
    this.textarea.value = before + insertion + after;

    const cursor = before.length + insertion.length;
    this.textarea.setSelectionRange(cursor, cursor);
    this.textarea.focus();

    if (this.onSelect) this.onSelect(item);
    this._close();

    // 触发 input 事件让外部（如 autoResize）更新
    const win = this.textarea?.ownerDocument?.defaultView || window;
    this.textarea.dispatchEvent(new win.Event("input", { bubbles: true }));
  }

  _close() {
    if (this._popup) {
      this._popup.remove();
      this._popup = null;
    }
    this._items = [];
    this._triggerInfo = null;
    if (this._debounceTimer) {
      clearTimeout(this._debounceTimer);
      this._debounceTimer = null;
    }
  }

  destroy() {
    this._close();
  }
}
