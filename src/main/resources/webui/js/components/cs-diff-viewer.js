/**
 * cs-diff-viewer.js — Unified diff viewer with hunk actions.
 *
 * Renders line-level diffs computed by ../diff.js.
 * Supports unified view only (sufficient for IDE code changes).
 * Emits:
 *   - "accept"  ({hunkIndex}) when user accepts a hunk
 *   - "reject"  ({hunkIndex}) when user rejects a hunk
 */

import { diffLines, computeGutter, applyPatch } from "../diff.js";
import { escapeHtml } from "../utils.js";

export class CsDiffViewer {
  constructor(container, options = {}) {
    this.container = container;
    this.options = {
      showHunkActions: true,
      maxHeight: "360px",
      ...options,
    };
    this.oldText = "";
    this.newText = "";
    this.hunks = [];
    this.el = document.createElement("div");
    this.el.className = "cs-diff-viewer";
    if (this.options.maxHeight) {
      this.el.style.maxHeight = this.options.maxHeight;
      this.el.style.overflow = "auto";
    }
    this.container.appendChild(this.el);
  }

  setDiff(oldText, newText) {
    this.oldText = oldText ?? "";
    this.newText = newText ?? "";
    this._render();
  }

  _render() {
    const changes = diffLines(this.oldText, this.newText);
    const lines = [];
    for (const ch of changes) {
      const parts = ch.value.split("\n");
      for (const p of parts) lines.push({ type: ch.type, value: p });
    }

    // Build hunks with context window
    const context = 3;
    const changed = [];
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].type !== "equal") changed.push(i);
    }

    this.hunks = [];
    if (changed.length === 0) {
      this.el.innerHTML = `<div class="cs-diff-empty">无变更</div>`;
      return;
    }

    let start = Math.max(0, changed[0] - context);
    let end = Math.min(lines.length - 1, changed[0] + context);
    for (let c = 1; c < changed.length; c++) {
      const idx = changed[c];
      if (idx - changed[c - 1] <= 2 * context + 1) {
        end = Math.min(lines.length - 1, idx + context);
      } else {
        this.hunks.push({ start, end });
        start = Math.max(0, idx - context);
        end = Math.min(lines.length - 1, idx + context);
      }
    }
    this.hunks.push({ start, end });

    // Compute line numbers
    const { oldLines, newLines } = computeGutter(changes);

    const html = this.hunks
      .map((hunk, idx) => this._renderHunk(hunk, idx, lines, oldLines, newLines))
      .join("");

    this.el.innerHTML = html;

    // Bind hunk actions
    if (this.options.showHunkActions) {
      this.el.querySelectorAll("[data-diff-action]").forEach((btn) => {
        btn.addEventListener("click", (e) => {
          const hunkIndex = Number(btn.closest("[data-hunk-index]")?.dataset?.hunkIndex);
          const action = btn.dataset.diffAction;
          this._emit(action, { hunkIndex });
        });
      });
    }
  }

  _renderHunk(hunk, hunkIndex, lines, oldLines, newLines) {
    let oldStart = 1;
    let newStart = 1;
    for (let i = 0; i < hunk.start; i++) {
      const t = lines[i].type;
      if (t === "equal" || t === "remove") oldStart++;
      if (t === "equal" || t === "add") newStart++;
    }

    let oldCount = 0;
    let newCount = 0;
    const rows = [];
    for (let i = hunk.start; i <= hunk.end; i++) {
      const ln = lines[i];
      if (ln.type === "equal" || ln.type === "remove") oldCount++;
      if (ln.type === "equal" || ln.type === "add") newCount++;
      const oldGutter = oldLines[i];
      const newGutter = newLines[i];
      const cls = `cs-diff-line ${ln.type}`;
      const marker = ln.type === "add" ? "+" : ln.type === "remove" ? "-" : " ";
      rows.push(`
        <div class="${cls}">
          <span class="cs-diff-gutter old">${oldGutter?.num ?? ""}</span>
          <span class="cs-diff-gutter new">${newGutter?.num ?? ""}</span>
          <span class="cs-diff-marker">${marker}</span>
          <span class="cs-diff-code">${escapeHtml(ln.value)}</span>
        </div>
      `);
    }

    const actions = this.options.showHunkActions
      ? `
        <div class="cs-diff-hunk-actions">
          <button class="cs-diff-btn accept" data-diff-action="accept" title="接受此 hunk">接受</button>
          <button class="cs-diff-btn reject" data-diff-action="reject" title="拒绝此 hunk">拒绝</button>
        </div>
      `
      : "";

    return `
      <div class="cs-diff-hunk" data-hunk-index="${hunkIndex}">
        <div class="cs-diff-hunk-header">
          <span>@@ -${oldStart},${oldCount} +${newStart},${newCount} @@</span>
          ${actions}
        </div>
        ${rows.join("")}
      </div>
    `;
  }

  _emit(type, detail) {
    const event = new CustomEvent(type, { detail, bubbles: true });
    this.el.dispatchEvent(event);
  }

  destroy() {
    this.el.remove();
  }
}
