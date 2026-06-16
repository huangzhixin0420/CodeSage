/**
 * Markdown 渲染 v2.6
 * ===================
 * - 引擎: markdown-it (vendored UMD, lib/markdown-it.min.js)
 * - 高亮: hljs 已经在 index.html 同步加载
 * - 兜底: markdown-it 还没就绪时只做 escape
 * - code block action bar: Apply / Insert / Create File / Copy, diff viewer + hunk Accept/Reject
 */

import { escapeHtml } from "./utils.js";
import { bridge } from "./bridge.js";
import { CsDiffViewer } from "./components/cs-diff-viewer.js";

let _md = null;
let _mdPromise = null;

function buildOptions() {
  return {
    // 切到 markdown-it 之前用的是 marked 的 breaks:true。
    // markdown-it 的 breaks 跟 marked 语义一致(单 \n → <br>),
    // 默认 false。AI 输出的"两行连写"在流式阶段由 .text-stream-segment
    // 的 white-space: pre-wrap 自然换行,不需要 <br> 介入。
    html: false, // 默认安全:不渲染原始 HTML
    xhtmlOut: false,
    breaks: false,
    linkify: true, // 裸 URL 自动转链接
    typographer: false,
    // 表格 / 删除线 / task list 走默认 GFM 兼容 preset
  };
}

function loadMarkdownIt() {
  if (_md) return Promise.resolve(_md);
  if (_mdPromise) return _mdPromise;
  _mdPromise = new Promise((resolve) => {
    if (typeof window.markdownit === "function") {
      try {
        _md = window.markdownit(buildOptions());
      } catch (e) {
        console.warn("[markdown] markdownit init failed:", e);
        _md = null;
      }
      resolve(_md);
      return;
    }
    const script = document.createElement("script");
    script.src = "lib/markdown-it.min.js";
    script.onload = () => {
      try {
        if (typeof window.markdownit === "function") {
          _md = window.markdownit(buildOptions());
        } else {
          console.warn("[markdown] markdownit not on window, using fallback");
        }
      } catch (e) {
        console.warn("[markdown] markdownit init failed:", e);
        _md = null;
      }
      resolve(_md);
    };
    script.onerror = () => {
      console.warn("[markdown] failed to load markdown-it, using fallback");
      resolve(null);
    };
    document.head.appendChild(script);
  });
  return _mdPromise;
}

/** 同步渲染:在 markdown-it 还没加载时降级为 escape */
export function renderMarkdown(text) {
  if (!text) return "";
  if (_md && typeof _md.render === "function") {
    try {
      return _postProcessTaskList(_md.render(text));
    } catch (e) {
      console.warn("[markdown] parse error:", e);
    }
  }
  // 兜底:转义 + 简单换行
  return escapeHtml(text).replace(/\n/g, "<br>");
}

export function preloadMarkdown() {
  return loadMarkdownIt();
}

/**
 * 任务列表后处理 — markdown-it 默认不渲染 [ ] / [x] 为 checkbox,
 * 这里在 _md.render 之后,用临时 DOM 容器遍历 <li> 节点,把
 * 文本开头是 "[ ] " / "[x] " 的 <li> 替换为 checkbox + 剩余文本。
 *
 * 为什么不用 regex 操作 HTML 字符串:嵌套 <ul> 会让 regex 跨节点吃
 * </li>,产生破损 HTML。querySelectorAll('li') 是天然 nested-aware。
 *
 * 仓库现状:task list 没有任何 css/test 覆盖,marked 的实现也只是默认 GFM,
 * 这里输出结构跟 marked 等价:
 *   <li class="task-list-item"><input ... checked? ...> 剩余文本
 * marked 原版不带 task-list-item 类,这里加上以备未来扩展样式钩子。
 */
const TASK_LIST_RE = /^\s*\[( |x|X)\] (.*)$/s;
let _taskListContainer = null;
function _postProcessTaskList(html) {
  if (!/<li>|\[ \]|\[[xX]\]/.test(html)) return html;
  if (!_taskListContainer) {
    _taskListContainer = document.createElement("div");
    _taskListContainer.style.display = "none";
  }
  _taskListContainer.innerHTML = html;
  const items = _taskListContainer.querySelectorAll("li");
  for (const li of items) {
    const firstText = Array.from(li.childNodes).find(
      (n) =>
        n.nodeType === 3 /* Node.TEXT_NODE */ &&
        n.textContent.trim().length > 0,
    );
    if (!firstText) continue;
    const m = firstText.textContent.match(TASK_LIST_RE);
    if (!m) continue;
    const checked = m[1] !== " ";
    firstText.textContent = m[2];
    const cb = document.createElement("span");
    cb.className = "task-list-item";
    const input = document.createElement("input");
    input.type = "checkbox";
    input.disabled = true;
    if (checked) input.checked = true;
    cb.appendChild(input);
    li.insertBefore(cb, li.firstChild);
  }
  return _taskListContainer.innerHTML;
}

/** 容器内 code 块高亮(已包含 hljs) */
export function highlightCode(container) {
  if (!window.hljs || !container) return;
  container.querySelectorAll("pre code").forEach((b) => {
    if (b.dataset.csHighlighted) return;
    try {
      window.hljs.highlightElement(b);
      b.dataset.csHighlighted = "1";
    } catch {}
  });
}

