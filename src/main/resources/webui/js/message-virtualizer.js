/**
 * message-virtualizer.js — 长对话虚拟滚动(方案 A)
 *
 * 阶段实现:IntersectionObserver + DOM 回收。
 *   - 保留消息数据全量
 *   - 仅可见区 + overscan 保留 DOM
 *   - 上下占位 spacer 保持连续滚动条体验
 *   - 被 pin 的消息(流式/最近 turn)始终保留在 DOM
 *
 * 接口保持兼容:add / remove / clear / finishBatch / getVisibleCount / getTotalCount。
 */

export class MessageVirtualizer {
  constructor(container, options = {}) {
    this.container = container;
    this.limit = options.limit || 50; // 向后兼容,现在作为 overscan 参考
    this.overscan = options.overscan || 8;
    this.items = []; // { id, el, height, inDom, pinned }
    this.pinnedIds = new Set();
    this._topSpacer = null;
    this._bottomSpacer = null;
    this._observer = null;
    this._resizeObserver = null;
    this._scrollHandler = null;
    this._scrollTicking = false;

    if (this.container) {
      this._ensureSpacers();
      this._initObservers();
    }
  }

  _ensureSpacers() {
    if (!this._topSpacer) {
      this._topSpacer = document.createElement("div");
      this._topSpacer.className = "virtualizer-top-spacer";
      this._topSpacer.style.height = "0px";
      this._topSpacer.style.flexShrink = "0";
    }
    if (!this._bottomSpacer) {
      this._bottomSpacer = document.createElement("div");
      this._bottomSpacer.className = "virtualizer-bottom-spacer";
      this._bottomSpacer.style.height = "0px";
      this._bottomSpacer.style.flexShrink = "0";
    }
    if (this._topSpacer.parentNode !== this.container) {
      this.container.insertBefore(this._topSpacer, this.container.firstChild);
    }
    if (this._bottomSpacer.parentNode !== this.container) {
      this.container.appendChild(this._bottomSpacer);
    }
  }

  _initObservers() {
    if (typeof IntersectionObserver === "undefined") return;
    this._observer = new IntersectionObserver(
      (entries) => this._onIntersection(entries),
      {
        root: this.container,
        rootMargin: `${this._estimateHeight() * this.overscan}px 0px`,
        threshold: 0,
      },
    );

    if (typeof ResizeObserver !== "undefined") {
      this._resizeObserver = new ResizeObserver((entries) => {
        for (const entry of entries) {
          const item = this.items.find((it) => it.el === entry.target);
          if (item && entry.contentRect) {
            item.height = entry.contentRect.height;
          }
        }
        this._updateSpacers();
      });
    }

    this._scrollHandler = () => {
      if (this._scrollTicking) return;
      this._scrollTicking = true;
      requestAnimationFrame(() => {
        this._scrollTicking = false;
        this._virtualize();
      });
    };
    this.container.addEventListener("scroll", this._scrollHandler);
  }

  _estimateHeight() {
    if (!this.items.length) return 80;
    const measured = this.items.filter((it) => (it.height || 0) > 0);
    if (measured.length === 0) return 80;
    const sum = measured.reduce((a, it) => a + it.height, 0);
    return Math.max(40, sum / measured.length);
  }

  _onIntersection(entries) {
    for (const entry of entries) {
      const item = this.items.find((it) => it.el === entry.target);
      if (!item) continue;
      item._intersecting = entry.isIntersecting;
    }
    this._virtualize();
  }

  /**
   * 根据可见性 + overscan + pinned 状态决定哪些 item 留在 DOM
   */
  _virtualize() {
    if (!this.container) return;
    const firstVisible = this.items.findIndex(
      (it) => it._intersecting || it.pinned,
    );
    const lastVisible = this.items.reduce(
      (last, it, idx) => (it._intersecting || it.pinned ? idx : last),
      -1,
    );

    let start;
    let end;
    if (firstVisible < 0) {
      // 无可见信息时兜底保留最后 limit 条,不加 overscan
      start = Math.max(0, this.items.length - this.limit);
      end = this.items.length - 1;
    } else {
      start = Math.max(0, firstVisible - this.overscan);
      end = Math.min(this.items.length - 1, lastVisible + this.overscan);
    }

    for (let i = 0; i < this.items.length; i++) {
      const item = this.items[i];
      const shouldBeInDom = i >= start && i <= end;
      if (shouldBeInDom && !item.inDom) {
        this._insertItemAt(item, i);
        item.inDom = true;
        this._observer?.observe(item.el);
        this._resizeObserver?.observe(item.el);
      } else if (!shouldBeInDom && item.inDom && !item.pinned) {
        this._measureAndDetach(item);
      }
    }

    this._updateSpacers();
  }

  _insertItemAt(item, index) {
    // 找到当前 DOM 中 index 之前最后一个 inDom 的元素
    let before = this._topSpacer;
    for (let i = index - 1; i >= 0; i--) {
      const prev = this.items[i];
      if (prev.inDom && prev.el.parentNode === this.container) {
        before = prev.el;
        break;
      }
    }
    if (before === this._topSpacer) {
      this.container.insertBefore(item.el, this._topSpacer.nextSibling);
    } else {
      if (before.nextSibling) {
        this.container.insertBefore(item.el, before.nextSibling);
      } else {
        this.container.appendChild(item.el);
      }
    }
  }

  _measureAndDetach(item) {
    if (item.el && item.el.parentNode) {
      const rect = item.el.getBoundingClientRect();
      if (rect.height > 0) item.height = rect.height;
      item.el.parentNode.removeChild(item.el);
    }
    item.inDom = false;
    this._observer?.unobserve(item.el);
    this._resizeObserver?.unobserve(item.el);
  }

  _updateSpacers() {
    if (!this._topSpacer || !this._bottomSpacer) return;
    let topHeight = 0;
    let bottomHeight = 0;
    let firstInDomIndex = -1;
    let lastInDomIndex = -1;
    for (let i = 0; i < this.items.length; i++) {
      const item = this.items[i];
      if (item.inDom) {
        if (firstInDomIndex < 0) firstInDomIndex = i;
        lastInDomIndex = i;
      }
    }
    for (let i = 0; i < firstInDomIndex; i++) {
      const item = this.items[i];
      topHeight += item.height || this._estimateHeight();
    }
    for (let i = lastInDomIndex + 1; i < this.items.length; i++) {
      const item = this.items[i];
      bottomHeight += item.height || this._estimateHeight();
    }
    this._topSpacer.style.height = `${topHeight}px`;
    this._bottomSpacer.style.height = `${bottomHeight}px`;
  }

  add(el) {
    if (!el) return;
    const existing = this.items.find((it) => it.el === el);
    if (!existing) {
      this.items.push({
        id: el.id || String(this.items.length),
        el,
        height: 0,
        inDom: false,
        pinned: false,
        _intersecting: false,
      });
    }
    const item = this.items.find((it) => it.el === el);
    if (item) {
      if (el.parentNode !== this.container) {
        this.container.appendChild(el);
      }
      item.inDom = true;
      this._observer?.observe(el);
      this._resizeObserver?.observe(el);
    }
    this._ensureSpacers();
    this._virtualize();
  }

  remove(el) {
    const idx = this.items.findIndex((it) => it.el === el);
    if (idx === -1) return;
    const item = this.items[idx];
    this._observer?.unobserve(item.el);
    this._resizeObserver?.unobserve(item.el);
    if (item.el.parentNode) item.el.parentNode.removeChild(item.el);
    this.items.splice(idx, 1);
    this._updateSpacers();
  }

  /**
   * 把消息固定住,不参与回收。用于流式消息/最近 turn。
   */
  pin(el) {
    const item = this.items.find((it) => it.el === el);
    if (item) {
      item.pinned = true;
      this.pinnedIds.add(item.id);
    }
  }

  unpin(el) {
    const item = this.items.find((it) => it.el === el);
    if (item) {
      item.pinned = false;
      this.pinnedIds.delete(item.id);
    }
  }

  /**
   * 批量同步后触发一次整理。
   */
  finishBatch() {
    this._reconcile();
    this._virtualize();
  }

  clear() {
    for (const item of this.items) {
      this._observer?.unobserve(item.el);
      this._resizeObserver?.unobserve(item.el);
    }
    this.items = [];
    this.pinnedIds.clear();
    this._removeSpacers();
  }

  _reconcile() {
    const children = Array.from(this.container.children);
    const existingEls = new Set(this.items.map((it) => it.el));
    for (const el of children) {
      if (
        el.classList?.contains("message") ||
        el.dataset?.csRole === "message"
      ) {
        if (!existingEls.has(el)) {
          this.items.push({
            id: el.id || String(this.items.length),
            el,
            height: 0,
            inDom: true,
            pinned: false,
            _intersecting: false,
          });
          this._observer?.observe(el);
          this._resizeObserver?.observe(el);
        }
      }
    }
  }

  _removeSpacers() {
    if (this._topSpacer && this._topSpacer.parentNode) {
      this._topSpacer.parentNode.removeChild(this._topSpacer);
    }
    if (this._bottomSpacer && this._bottomSpacer.parentNode) {
      this._bottomSpacer.parentNode.removeChild(this._bottomSpacer);
    }
    this._topSpacer = null;
    this._bottomSpacer = null;
  }

  get visibleCount() {
    return this.items.filter((it) => it.inDom).length;
  }

  get totalCount() {
    return this.items.length;
  }

  destroy() {
    this.container?.removeEventListener("scroll", this._scrollHandler);
    this._observer?.disconnect();
    this._resizeObserver?.disconnect();
    this._removeSpacers();
    this.items = [];
  }
}
