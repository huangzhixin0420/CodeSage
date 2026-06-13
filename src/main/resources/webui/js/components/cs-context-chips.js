/**
 * cs-context-chips.js — 输入区上下文 chip 可视化
 *
 * 选中 @file / #selection 等候选后,在 textarea 上方渲染可删除的 pill/chip。
 * chip 显示类型图标、名称、删除按钮;超出 token 限制时变红并提示。
 * 发送前将 chip 解析为对应文本/附件格式。
 */

import { escapeHtml } from "../utils.js";

const DEFAULT_TOKEN_LIMIT = 128000;

export class ContextChips {
  constructor(options = {}) {
    this.container = options.container;
    this.onChange = options.onChange || null;
    this.tokenLimit = options.tokenLimit || DEFAULT_TOKEN_LIMIT;
    this.items = [];
    this.el = document.createElement("div");
    this.el.className = "context-chips";
    if (this.container) {
      this.container.appendChild(this.el);
    }
  }

  mount(parent) {
    if (this.el.parentNode) return;
    parent.appendChild(this.el);
  }

  setTokenLimit(limit) {
    this.tokenLimit = limit || DEFAULT_TOKEN_LIMIT;
    this._render();
  }

  add(item) {
    if (!item) return;
    // 去重:同值同类型不重复添加
    const exists = this.items.some(
      (it) => it.type === item.type && it.value === item.value,
    );
    if (exists) return;
    this.items.push({
      id: item.id || `${item.type}-${item.value}-${Date.now()}`,
      type: item.type,
      value: item.value,
      label: item.label || item.value,
      icon: item.icon || this._defaultIcon(item.type),
      size: item.size || 0,
      tokens: item.tokens || this._estimateTokens(item),
    });
    this._render();
    this._notify();
  }

  remove(idOrIndex) {
    const idx =
      typeof idOrIndex === "number"
        ? idOrIndex
        : this.items.findIndex((it) => it.id === idOrIndex);
    if (idx < 0 || idx >= this.items.length) return;
    this.items.splice(idx, 1);
    this._render();
    this._notify();
  }

  clear() {
    this.items = [];
    this._render();
    this._notify();
  }

  getItems() {
    return this.items.slice();
  }

  getSummary() {
    const totalTokens = this.items.reduce((sum, it) => sum + (it.tokens || 0), 0);
    return {
      totalTokens,
      limit: this.tokenLimit,
      overLimit: totalTokens > this.tokenLimit,
      overEighty: totalTokens > this.tokenLimit * 0.8,
    };
  }

  /**
   * 将 chip 解析为发送 payload
   *   - file chip -> 文本中 @path, fileRefs 中 { name, path, relativePath }
   *   - context chip -> 文本中 #value
   *   - 图片附件保持由调用方单独处理
   */
  toPayload() {
    const parts = [];
    const fileRefs = [];
    for (const it of this.items) {
      if (it.type === "file") {
        parts.push(`@${it.value}`);
        fileRefs.push({
          name: it.label,
          path: it.value,
          relativePath: it.value,
        });
      } else if (it.type === "context") {
        parts.push(`#${it.value}`);
      }
    }
    return { text: parts.join(" "), fileRefs };
  }

  _defaultIcon(type) {
    if (type === "file") return "fa-file-code";
    if (type === "context") return "fa-i-cursor";
    return "fa-circle";
  }

  _estimateTokens(item) {
    if (item.tokens) return item.tokens;
    if (item.size && item.size > 0) return Math.ceil(item.size / 4);
    // 文件路径粗略估算;上下文项固定 500 tokens
    if (item.type === "file") return Math.ceil((item.value?.length || 0) / 4) + 50;
    if (item.type === "context") return 500;
    return Math.ceil((item.value?.length || 0) / 4);
  }

  _notify() {
    if (this.onChange) this.onChange(this.getSummary());
  }

  _render() {
    const summary = this.getSummary();
    const overClass = summary.overLimit ? " over-limit" : summary.overEighty ? " near-limit" : "";

    const chipsHtml = this.items
      .map(
        (it) => `
          <div class="context-chip${it.type === "file" ? " file" : " context"}" data-cs-chip-id="${escapeHtml(it.id)}" title="${escapeHtml(it.label)} (${it.tokens || 0} tokens)">
            <i class="fas ${escapeHtml(it.icon)}"></i>
            <span class="context-chip-label">${escapeHtml(it.label)}</span>
            <button class="context-chip-remove" data-cs-chip-id="${escapeHtml(it.id)}" aria-label="移除">
              <i class="fas fa-xmark"></i>
            </button>
          </div>
        `,
      )
      .join("");

    const hint = summary.overLimit
      ? `超出 token 限制: ${summary.totalTokens.toLocaleString()} / ${summary.limit.toLocaleString()}`
      : summary.overEighty
        ? `接近 token 限制: ${summary.totalTokens.toLocaleString()} / ${summary.limit.toLocaleString()}`
        : `${summary.totalTokens.toLocaleString()} / ${summary.limit.toLocaleString()} tokens`;

    this.el.innerHTML = `
      <div class="context-chips-row${overClass}">${chipsHtml}</div>
      <div class="context-chips-hint${overClass}">${hint}</div>
    `;

    this.el.querySelectorAll(".context-chip-remove").forEach((btn) => {
      btn.addEventListener("click", () => this.remove(btn.dataset.csChipId));
    });
  }
}
