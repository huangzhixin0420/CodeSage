/**
 * message-virtualizer.js — 长对话性能优化
 *
 * 阶段实现：截断 + "加载更早消息"（方案 B）。
 * 当消息数超过 limit 时，只保留最近 limit 条在 DOM 中；
 * 更早的消息以数据形式保存在内存，用户点击按钮后分批回填。
 *
 * 未来可演进为 IntersectionObserver + DOM 回收（方案 A），
 * 此模块接口保持兼容：add / remove / clear / getVisibleCount / getTotalCount。
 */

export class MessageVirtualizer {
  constructor(container, options = {}) {
    this.container = container;
    this.limit = options.limit || 50;
    this.batch = options.batch || 50;
    this.items = []; // { id, el, inDom }
    this.placeholder = null;
    this._onLoadEarlier = this._onLoadEarlier.bind(this);
  }

  /**
   * Register an existing DOM element as a message.
   * If the element is not already in container, it will be appended.
   */
  add(el) {
    if (!el) return;
    const existing = this.items.find((it) => it.el === el);
    if (!existing) {
      this.items.push({
        id: el.id || String(this.items.length),
        el,
        inDom: false,
      });
    }
    if (el.parentNode !== this.container) {
      // If placeholder exists, insert before it; otherwise append
      if (this.placeholder && this.placeholder.parentNode === this.container) {
        this.container.insertBefore(el, this.placeholder);
      } else {
        this.container.appendChild(el);
      }
    }
    const item = this.items.find((it) => it.el === el);
    if (item) item.inDom = true;
    this._updatePlaceholder();
  }

  /**
   * Remove an element from management.
   */
  remove(el) {
    const idx = this.items.findIndex((it) => it.el === el);
    if (idx === -1) return;
    const item = this.items[idx];
    if (item.el.parentNode) item.el.parentNode.removeChild(item.el);
    this.items.splice(idx, 1);
    this._updatePlaceholder();
  }

  /**
   * Reset and optionally truncate DOM after loading a large batch of messages.
   */
  finishBatch() {
    this._reconcile();
    this._truncate();
  }

  /**
   * Clear all tracked items.
   */
  clear() {
    this.items = [];
    this._removePlaceholder();
  }

  get visibleCount() {
    return this.items.filter((it) => it.inDom).length;
  }

  get totalCount() {
    return this.items.length;
  }

  _reconcile() {
    // Ensure all container children that look like messages are tracked
    const children = Array.from(this.container.children);
    for (const el of children) {
      if (
        el.classList?.contains("message") ||
        el.dataset?.csRole === "message"
      ) {
        const existing = this.items.find((it) => it.el === el);
        if (!existing) {
          this.items.push({
            id: el.id || String(this.items.length),
            el,
            inDom: true,
          });
        } else {
          existing.inDom = true;
        }
      }
    }
  }

  _truncate() {
    if (this.items.length <= this.limit) {
      this._updatePlaceholder();
      return;
    }
    // Keep the last `limit` items in DOM; move earlier items to memory
    const hiddenCount = this.items.length - this.limit;
    for (let i = 0; i < hiddenCount; i++) {
      const item = this.items[i];
      if (item.inDom && item.el.parentNode) {
        item.el.parentNode.removeChild(item.el);
        item.inDom = false;
      }
    }
    for (let i = hiddenCount; i < this.items.length; i++) {
      const item = this.items[i];
      if (!item.inDom) {
        this.container.appendChild(item.el);
        item.inDom = true;
      }
    }
    this._updatePlaceholder();
  }

  _updatePlaceholder() {
    const hiddenCount = this.items.filter((it) => !it.inDom).length;
    if (hiddenCount === 0) {
      this._removePlaceholder();
      return;
    }
    if (!this.placeholder) {
      this.placeholder = document.createElement("div");
      this.placeholder.className = "message-load-earlier";
      this.placeholder.addEventListener("click", this._onLoadEarlier);
    }
    this.placeholder.innerHTML = `
      <button class="message-load-earlier-btn">
        <i class="fas fa-arrow-up"></i>
        加载更早的 ${Math.min(hiddenCount, this.batch)} 条消息
      </button>
    `;
    if (this.placeholder.parentNode !== this.container) {
      this.container.insertBefore(this.placeholder, this.container.firstChild);
    }
  }

  _removePlaceholder() {
    if (this.placeholder && this.placeholder.parentNode) {
      this.placeholder.parentNode.removeChild(this.placeholder);
    }
    this.placeholder = null;
  }

  _onLoadEarlier() {
    const hidden = this.items.filter((it) => !it.inDom);
    const batch = hidden.slice(-this.batch);
    if (batch.length === 0) return;

    // Prepend batch before placeholder (preserve original order)
    const frag = document.createDocumentFragment();
    for (const item of batch) {
      frag.appendChild(item.el);
      item.inDom = true;
    }
    if (this.placeholder && this.placeholder.parentNode) {
      this.container.insertBefore(frag, this.placeholder);
    } else {
      this.container.insertBefore(frag, this.container.firstChild);
    }
    this._updatePlaceholder();

    // Scroll the newly loaded first item into view
    const firstLoaded = batch[0]?.el;
    if (firstLoaded && typeof firstLoaded.scrollIntoView === "function") {
      firstLoaded.scrollIntoView({ block: "start" });
    }

    // Dispatch event for telemetry / logging
    this.container.dispatchEvent(
      new CustomEvent("cs:loadEarlier", {
        detail: {
          loaded: batch.length,
          remaining: this.items.filter((it) => !it.inDom).length,
        },
      }),
    );
  }
}
