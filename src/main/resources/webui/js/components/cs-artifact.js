/**
 * cs-artifact.js — Single artifact panel with versions, diff, preview and actions.
 *
 * Manages state:
 *   - versions: [{number, content, timestamp, status}]
 *   - currentVersionNumber
 *   - status: pending | applied | rejected
 *
 * Emits bridge messages via onAction callback:
 *   {type:'apply_artifact'|'reject_artifact'|'preview_artifact'|'create_file', artifactId, content, version}
 */

import { CsDiffViewer } from "./cs-diff-viewer.js";
import { escapeHtml, formatTimeOfDay } from "../utils.js";

export class CsArtifact {
  constructor(options = {}) {
    this.id = options.id || "";
    this.title = options.title || "artifact";
    this.language = options.language || "text";
    this.onAction = options.onAction || (() => {});

    this.versions = [];
    if (options.content != null) {
      this.versions.push({
        number: 1,
        content: options.content,
        timestamp: Date.now(),
        status: "pending",
      });
    }
    this.currentVersion = 1;
    this.status = "pending";
    this.compareVersion = 0; // 0 means compare with previous

    this.el = document.createElement("div");
    this.el.className = "cs-artifact-panel";
    this.el.id = "artifact-" + this.id;
    this.el.dataset.artifactId = String(this.id);
    this._render();
  }

  mount(parent) {
    parent.appendChild(this.el);
  }

  /**
   * Add or update a version. If versionNumber matches existing, replace content.
   */
  addVersion(content, { versionNumber, status, timestamp } = {}) {
    const num = versionNumber || this.versions.length + 1;
    const existing = this.versions.find((v) => v.number === num);
    if (existing) {
      existing.content = content;
      if (status) existing.status = status;
      if (timestamp) existing.timestamp = timestamp;
    } else {
      this.versions.push({
        number: num,
        content,
        timestamp: timestamp || Date.now(),
        status: status || "pending",
      });
    }
    this.currentVersion = num;
    this.status = status || this.status;
    this._render();
  }

  setStatus(status) {
    this.status = status;
    const current = this.versions.find((v) => v.number === this.currentVersion);
    if (current) current.status = status;
    this._render();
  }

  setOriginalContent(originalContent) {
    this.originalContent = originalContent;
    this._render();
  }

  setKind(kind) {
    this.kind = kind;
    this._render();
  }

  get currentContent() {
    const v = this.versions.find((x) => x.number === this.currentVersion);
    return v ? v.content : "";
  }

  get previousContent() {
    const idx = this.versions.findIndex(
      (x) => x.number === this.currentVersion,
    );
    if (idx > 0) return this.versions[idx - 1].content;
    return this.originalContent || "";
  }

  _render() {
    const hasVersions = this.versions.length > 0;
    const current = this.versions.find((v) => v.number === this.currentVersion);
    const statusClass =
      this.status === "applied"
        ? "applied"
        : this.status === "rejected"
          ? "rejected"
          : "pending";

    this.el.innerHTML = `
      <div class="cs-artifact-header">
        <span class="cs-artifact-title">
          <span class="cs-artifact-status ${statusClass}"></span>
          ${this._fileIcon()}
          <span class="cs-artifact-name" title="${escapeHtml(this.title)}">${escapeHtml(this.title)}</span>
          ${hasVersions ? `<span class="cs-artifact-version">v${this.currentVersion}</span>` : ""}
        </span>
        <span class="cs-artifact-actions">
          <button class="icon-btn" data-art-action="copy" title="复制" aria-label="复制">
            <i class="fas fa-copy"></i>
          </button>
          <button class="icon-btn" data-art-action="apply" title="应用到编辑器" aria-label="应用">
            <i class="fas fa-file-import"></i>
          </button>
          <button class="icon-btn" data-art-action="reject" title="拒绝" aria-label="拒绝">
            <i class="fas fa-xmark"></i>
          </button>
        </span>
      </div>
      <div class="cs-artifact-tabs">
        <button class="cs-artifact-tab active" data-tab="code">代码</button>
        <button class="cs-artifact-tab" data-tab="diff">Diff</button>
        ${this._canPreview() ? `<button class="cs-artifact-tab" data-tab="preview">预览</button>` : ""}
        <button class="cs-artifact-tab" data-tab="versions">版本</button>
      </div>
      <div class="cs-artifact-body">
        <div class="cs-artifact-tab-panel active" data-panel="code">
          <pre class="cs-artifact-code"><code class="language-${escapeHtml(this.language)}">${escapeHtml(current ? current.content : "")}</code></pre>
        </div>
        <div class="cs-artifact-tab-panel" data-panel="diff"></div>
        <div class="cs-artifact-tab-panel" data-panel="preview"></div>
        <div class="cs-artifact-tab-panel" data-panel="versions"></div>
      </div>
      <div class="cs-artifact-footer">
        <span class="cs-artifact-meta">${this.versions.length} 个版本</span>
        <span class="cs-artifact-meta">${this.language}</span>
      </div>
    `;

    this._bindHeaderActions();
    this._bindTabs();
    this._renderVersionsPanel();
    this._renderPreviewPanel();
    this._maybeRenderDiff();
    this._highlightCode();
  }

  _fileIcon() {
    if (/\.(svg|html?)$/i.test(this.title))
      return `<i class="fas fa-image"></i>`;
    if (/\.(md|markdown)$/i.test(this.title))
      return `<i class="fas fa-file-lines"></i>`;
    return `<i class="fas fa-file-code"></i>`;
  }

  _canPreview() {
    return /\.(svg|html?|md|markdown)$/i.test(this.title);
  }

  _bindHeaderActions() {
    this.el.querySelectorAll("[data-art-action]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const action = btn.dataset.artAction;
        if (action === "copy") {
          navigator.clipboard?.writeText(this.currentContent);
          const t = window.CodeSage?.toast;
          if (t?.success) t.success("已复制");
          else if (window.__cs_toast?.success)
            window.__cs_toast.success("已复制");
        } else if (action === "apply") {
          this.setStatus("applied");
          this.onAction({
            type: "apply_artifact",
            artifactId: this.id,
            content: this.currentContent,
            version: this.currentVersion,
          });
        } else if (action === "reject") {
          this.setStatus("rejected");
          this.onAction({
            type: "reject_artifact",
            artifactId: this.id,
            content: this.currentContent,
            version: this.currentVersion,
          });
        }
      });
    });
  }

  _bindTabs() {
    this.el.querySelectorAll("[data-tab]").forEach((tab) => {
      tab.addEventListener("click", () => {
        const name = tab.dataset.tab;
        this.el
          .querySelectorAll("[data-tab]")
          .forEach((t) => t.classList.remove("active"));
        tab.classList.add("active");
        this.el
          .querySelectorAll("[data-panel]")
          .forEach((p) => p.classList.remove("active"));
        const panel = this.el.querySelector(`[data-panel="${name}"]`);
        panel?.classList.add("active");
        if (name === "diff") this._maybeRenderDiff();
        if (name === "preview") this._renderPreviewPanel();
      });
    });
  }

  _maybeRenderDiff() {
    const panel = this.el.querySelector('[data-panel="diff"]');
    if (!panel || panel.dataset.rendered) return;
    panel.dataset.rendered = "1";
    panel.innerHTML = "";
    const diffContainer = document.createElement("div");
    diffContainer.className = "cs-diff-container";
    panel.appendChild(diffContainer);

    const oldText = this.compareVersion
      ? (this.versions.find((v) => v.number === this.compareVersion)?.content ??
        "")
      : this.previousContent;
    const viewer = new CsDiffViewer(diffContainer, {
      showHunkActions: false,
      maxHeight: "none",
    });
    viewer.setDiff(oldText, this.currentContent);
  }

  _renderPreviewPanel() {
    const panel = this.el.querySelector('[data-panel="preview"]');
    if (!panel || !this._canPreview()) return;
    const content = this.currentContent;
    if (/\.svg$/i.test(this.title)) {
      panel.innerHTML = `<div class="cs-artifact-preview-frame">${content}</div>`;
    } else if (/\.(md|markdown)$/i.test(this.title)) {
      panel.innerHTML = `<div class="cs-artifact-preview-frame markdown-preview">${escapeHtml(content)}</div>`;
    } else {
      panel.innerHTML = `<iframe class="cs-artifact-preview-frame" sandbox="allow-scripts" srcdoc="${escapeHtml(content)}"></iframe>`;
    }
  }

  _renderVersionsPanel() {
    const panel = this.el.querySelector('[data-panel="versions"]');
    if (!panel) return;
    if (this.versions.length === 0) {
      panel.innerHTML = `<div class="cs-artifact-empty">暂无版本历史</div>`;
      return;
    }
    panel.innerHTML = `
      <div class="cs-artifact-versions">
        ${this.versions
          .map(
            (v) => `
          <div class="cs-artifact-version-item ${v.number === this.currentVersion ? "current" : ""} ${v.status || "pending"}" data-version="${v.number}">
            <span class="version-number">v${v.number}</span>
            <span class="version-time">${formatTimeOfDay(v.timestamp)}</span>
            <span class="version-status">${v.status || "pending"}</span>
          </div>
        `,
          )
          .join("")}
      </div>
    `;
    panel.querySelectorAll("[data-version]").forEach((item) => {
      item.addEventListener("click", () => {
        const num = Number(item.dataset.version);
        this.currentVersion = num;
        this.compareVersion = 0;
        this.el
          .querySelector('[data-panel="diff"]')
          ?.removeAttribute("data-rendered");
        this._render();
      });
    });
  }

  _highlightCode() {
    if (window.hljs) {
      this.el.querySelectorAll("pre code").forEach((block) => {
        try {
          window.hljs.highlightElement(block);
        } catch {}
      });
    }
  }

  destroy() {
    this.el.remove();
  }
}
