/**
 * cs-thinking-v2.js — 结构化推理地图
 * ====================================
 *
 * 目标：把 `cs-thinking` 从“可折叠纯文本”升级为“结构化推理地图”。
 *
 * 解析策略（混合方案 A + B）：
 *   1. 后端结构化：识别 `<think>` 标签与 Markdown heading（## 目标理解 / ## 分析 ...）。
 *   2. 前端兜底：对未结构化内容按关键词启发式分段，或按段落/换行拆分。
 *   3. 提供“简洁 / 详细 / 原始”三种模式切换。
 *
 * 关键交互：
 *   - 阶段进度条：header 显示当前阶段（如“分析中…”）。
 *   - 关键词高亮：对“错误”“修正”“所以”“决定”等词加语义色。
 *   - 内部搜索框：长 thinking 支持搜索。
 */

import { escapeHtml } from "../utils.js";
import { icon } from "../icons.js";

const RUNNING_DOT_HTML = '<span class="thinking-dot"></span>'.repeat(3);

/** 已知结构化阶段标题（中文/英文） */
const KNOWN_SECTIONS = {
  目标理解: "goal",
  目标: "goal",
  问题理解: "goal",
  理解: "goal",
  分析: "analysis",
  问题分析: "analysis",
  思路: "analysis",
  尝试: "attempt",
  尝试与修正: "attempt",
  修正: "attempt",
  纠错: "attempt",
  错误: "error",
  问题: "error",
  结论: "conclusion",
  总结: "conclusion",
  决定: "decision",
  决策: "decision",
  计划: "plan",
  方案: "plan",
  注意: "note",
};

/** 语义关键词 -> CSS 类 */
const SEMANTIC_KEYWORDS = {
  错误: "kw-error",
  失败: "kw-error",
  异常: "kw-error",
  修正: "kw-fix",
  修复: "kw-fix",
  调整: "kw-fix",
  所以: "kw-conclusion",
  因此: "kw-conclusion",
  决定: "kw-decision",
  计划: "kw-plan",
  注意: "kw-note",
};

/** 判断文本是否包含结构化 heading */
function hasStructuredHeadings(text) {
  return /(?:^|\n)#{2,3}\s+/.test(text) || /<think>/.test(text);
}

/** 提取 think 标签内内容 */
function extractThinkContent(text) {
  const match = text.match(/<think>([\s\S]*?)<\/think>/i);
  return match ? match[1].trim() : text;
}

/**
 * 解析 thinking 文本为 section 数组。
 * @returns {{key: string, title: string, content: string}[]}
 */
export function parseThinkingSections(text) {
  if (!text) return [];
  const raw = extractThinkContent(text);
  if (!raw) return [];

  // 如果包含 heading，按 heading 拆分
  if (hasStructuredHeadings(raw)) {
    return parseByHeadings(raw);
  }

  // 兜底：按关键词分段或按段落拆分
  return parseHeuristic(raw);
}

function parseByHeadings(text) {
  const lines = text.split("\n");
  const sections = [];
  let current = null;

  const flush = () => {
    if (current) {
      current.content = current.content.trim();
      if (current.content) sections.push(current);
    }
  };

  for (const line of lines) {
    const headingMatch = line.match(/^#{2,3}\s+(.+)$/);
    if (headingMatch) {
      flush();
      const title = headingMatch[1].trim();
      const key = guessSectionKey(title);
      current = { key, title, content: "" };
    } else if (current) {
      current.content += line + "\n";
    } else {
      // heading 之前的内容作为默认段
      current = { key: "default", title: "推理", content: line + "\n" };
    }
  }
  flush();

  if (sections.length === 0) {
    return [{ key: "default", title: "推理", content: text }];
  }
  return sections;
}

function parseHeuristic(text) {
  const lines = text.split("\n");
  const sections = [];
  let current = { key: "default", title: "推理", content: "" };

  const flush = () => {
    const content = current.content.trim();
    if (content) {
      sections.push({ ...current, content });
    }
    current = { key: "default", title: "推理", content: "" };
  };

  for (const line of lines) {
    const key = guessSectionKey(line);
    if (key !== "default" && key !== current.key) {
      flush();
      current.key = key;
      current.title = line.trim();
    }
    current.content += line + "\n";
  }
  flush();

  if (sections.length === 0) {
    return [{ key: "default", title: "推理", content: text }];
  }
  return sections;
}

function guessSectionKey(title) {
  const t = String(title).trim().replace(/[：:]/g, "");
  for (const [name, key] of Object.entries(KNOWN_SECTIONS)) {
    if (t.includes(name)) return key;
  }
  return "default";
}

/** 对内容中的语义关键词加高亮 span */
function highlightSemanticKeywords(html) {
  let out = html;
  for (const [word, cls] of Object.entries(SEMANTIC_KEYWORDS)) {
    const re = new RegExp(escapeRegExp(word), "g");
    out = out.replace(re, `<span class="${cls}">${word}</span>`);
  }
  return out;
}

function escapeRegExp(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * 根据当前 section 推断当前阶段标签。
 */
function inferCurrentStageLabel(sections) {
  if (!sections.length) return "思考中…";
  const last = sections[sections.length - 1];
  const labels = {
    goal: "理解目标…",
    analysis: "分析中…",
    attempt: "尝试与修正…",
    error: "处理错误…",
    conclusion: "形成结论…",
    decision: "做决策…",
    plan: "制定计划…",
    note: "记录注意…",
    default: "推理中…",
  };
  return labels[last.key] || "推理中…";
}

export class StructuredThinking {
  /**
   * @param {object} opts
   * @param {string} [opts.id]
   * @param {string} [opts.mode="detailed"] - "compact" | "detailed" | "raw"
   */
  constructor(opts = {}) {
    this.id =
      opts.id ||
      "thinking-" + Date.now() + "-" + Math.random().toString(36).slice(2, 7);
    this.state = "running";
    this.startTime = Date.now();
    this.elapsedMs = 0;
    this.tokenCount = 0;
    this.content = "";
    this.mode = opts.mode || "detailed";

    this.el = document.createElement("div");
    this.el.className = "thinking-card running structured";
    this.el.setAttribute("data-cs-thinking", this.id);
    this._render();
  }

  _render() {
    const isRunning = this.state === "running";
    const sections = parseThinkingSections(this.content);
    const stageLabel = isRunning
      ? inferCurrentStageLabel(sections)
      : `已思考 ${(this.elapsedMs / 1000).toFixed(1)}s`;
    const iconHtml = isRunning
      ? RUNNING_DOT_HTML
      : icon("check", "thinking-status-icon");
    const metaHtml =
      this.tokenCount > 0
        ? `<span class="thinking-meta">${this.tokenCount} tokens</span>`
        : "";

    this.el.className = `thinking-card ${isRunning ? "running" : "completed"} structured`;

    const bodyHtml = this._renderBody(sections);

    this.el.innerHTML = `
            <div class="thinking-header" data-cs-role="header" role="button" tabindex="0">
                <div class="thinking-icon">${iconHtml}</div>
                <div class="thinking-label">
                    <span class="thinking-label-text">${escapeHtml(stageLabel)}</span>
                    ${metaHtml}
                </div>
                <div class="thinking-mode-switch" data-cs-role="mode-switch">
                    <button class="thinking-mode-btn${this.mode === "compact" ? " active" : ""}" data-mode="compact" title="简洁">简</button>
                    <button class="thinking-mode-btn${this.mode === "detailed" ? " active" : ""}" data-mode="detailed" title="详细">详</button>
                    <button class="thinking-mode-btn${this.mode === "raw" ? " active" : ""}" data-mode="raw" title="原始">原</button>
                </div>
                <i class="fas fa-chevron-down thinking-chevron" data-cs-role="chevron"></i>
            </div>
            <div class="thinking-body" data-cs-role="body">
                <div class="thinking-body-content">
                    ${this.mode !== "raw" && sections.length > 1 ? this._renderStageProgress(sections) : ""}
                    ${bodyHtml}
                </div>
            </div>
        `;

    this._bodyEl = this.el.querySelector('[data-cs-role="body"]');
    this._contentEl =
      this.el.querySelector(".thinking-body-content") || this._bodyEl;
    this._labelEl = this.el.querySelector(".thinking-label-text");
    this._headerEl = this.el.querySelector('[data-cs-role="header"]');
    this._searchEl = this.el.querySelector('[data-cs-role="search"]');

    this._headerEl.addEventListener("click", () => this.toggle());
    this._headerEl.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        this.toggle();
      }
    });

    this.el
      .querySelectorAll('[data-cs-role="mode-switch"] button')
      .forEach((btn) => {
        btn.addEventListener("click", (e) => {
          e.stopPropagation();
          this.setMode(btn.dataset.mode);
        });
      });

    if (this._searchEl) {
      this._searchEl.addEventListener("input", (e) => {
        this._filterSections(e.target.value);
      });
    }
  }

  _renderStageProgress(sections) {
    const total = sections.length;
    const completed = this.state === "running" ? total - 1 : total;
    const percent = total > 0 ? Math.round((completed / total) * 100) : 0;
    const items = sections
      .map(
        (s, i) => `
                    <div class="thinking-progress-item ${i < completed ? "completed" : ""}${i === completed && this.state === "running" ? " active" : ""}" data-section-key="${escapeHtml(s.key)}">
                        <span class="thinking-progress-dot"></span>
                        <span class="thinking-progress-title">${escapeHtml(s.title)}</span>
                    </div>
                `,
      )
      .join("");
    return `
            <div class="thinking-progress">
                <div class="thinking-progress-bar" aria-hidden="true">
                    <div class="thinking-progress-fill" style="width:${percent}%"></div>
                </div>
                <div class="thinking-progress-items">${items}</div>
            </div>
        `;
  }

  _renderBody(sections) {
    if (this.mode === "raw") {
      const rawHtml = escapeHtml(this.content);
      return `<div class="thinking-raw"><pre>${rawHtml}</pre></div>`;
    }

    if (this.mode === "compact") {
      // 简洁模式：只显示最后一段
      const last = sections[sections.length - 1];
      if (!last) {
        return `<div class="thinking-section">（无内容）</div>`;
      }
      const html = escapeHtml(last.content).replace(/\n/g, "<br>");
      return `
                <div class="thinking-section" data-section-key="${escapeHtml(last.key)}">
                    <div class="thinking-section-title">${escapeHtml(last.title)}</div>
                    <div class="thinking-section-content">${highlightSemanticKeywords(html)}</div>
                </div>
            `;
    }

    // detailed
    if (!sections.length) {
      return `<div class="thinking-section">（无内容）</div>`;
    }

    const searchBox =
      sections.length > 2
        ? `<div class="thinking-search"><i class="fas fa-magnifying-glass"></i><input type="text" placeholder="搜索推理内容…" data-cs-role="search" /></div>`
        : "";

    const sectionsHtml = sections
      .map(
        (s) => `
                    <div class="thinking-section" data-section-key="${escapeHtml(s.key)}">
                        <div class="thinking-section-title">${escapeHtml(s.title)}</div>
                        <div class="thinking-section-content">${highlightSemanticKeywords(
                          escapeHtml(s.content).replace(/\n/g, "<br>"),
                        )}</div>
                    </div>
                `,
      )
      .join("");

    return `${searchBox}${sectionsHtml}`;
  }

  _filterSections(query) {
    const q = (query || "").toLowerCase();
    this.el.querySelectorAll(".thinking-section").forEach((sec) => {
      const visible = !q || sec.textContent.toLowerCase().includes(q);
      sec.style.display = visible ? "" : "none";
    });
  }

  appendContent(text) {
    this.content += text;
    this.tokenCount = Math.ceil(this.content.length / 3);
    this._render();
  }

  setContent(text) {
    this.content = text;
    this.tokenCount = Math.ceil(text.length / 3);
    this._render();
  }

  setMode(mode) {
    if (!["compact", "detailed", "raw"].includes(mode)) return;
    this.mode = mode;
    this._render();
  }

  _updateRunningMeta() {
    if (!this._labelEl || this.state !== "running") return;
    const sections = parseThinkingSections(this.content);
    this._labelEl.textContent = inferCurrentStageLabel(sections);
  }

  complete(elapsedMs) {
    this.state = "completed";
    this.elapsedMs = elapsedMs ?? Date.now() - this.startTime;
    this._render();
    this.setContent(this.content);
    this._bodyEl.classList.remove("open");
    this.el.querySelector('[data-cs-role="chevron"]')?.classList.remove("open");
  }

  toggle() {
    if (!this._bodyEl) return;
    const isOpen = this._bodyEl.classList.toggle("open");
    this.el
      .querySelector('[data-cs-role="chevron"]')
      ?.classList.toggle("open", isOpen);
  }

  collapse() {
    this._bodyEl?.classList.remove("open");
    this.el
      ?.querySelector('[data-cs-role="chevron"]')
      ?.classList.remove("open");
  }

  expand() {
    this._bodyEl?.classList.add("open");
    this.el?.querySelector('[data-cs-role="chevron"]')?.classList.add("open");
  }

  destroy() {
    this.el.remove();
  }
}
