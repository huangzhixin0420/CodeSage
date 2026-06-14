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

/** 判断代码块是否为 diff / patch */
function _isDiffCode(text, lang) {
  if (!text) return false;
  const trimmed = text.trim();
  if (lang === "diff" || lang === "patch") return true;
  return (
    /^---[ \t]+.*\n\+\+\+[ \t]+.*/.test(trimmed) || /^@@ -\d+/.test(trimmed)
  );
}

/** 从 unified diff 文本重建 old/new 两个版本,供 CsDiffViewer 使用 */
function _reconstructFromDiff(diffText) {
  const oldLines = [];
  const newLines = [];
  const lines = diffText.split(/\r?\n/);
  let inHunk = false;
  let oldIdx = 0;
  let newIdx = 0;

  for (const line of lines) {
    if (line.startsWith("@@")) {
      const m = line.match(/@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/);
      if (m) {
        inHunk = true;
        oldIdx = parseInt(m[1], 10) - 1;
        newIdx = parseInt(m[3], 10) - 1;
      }
      continue;
    }
    if (!inHunk) continue;
    const marker = line.charAt(0);
    const text = line.slice(1);
    if (marker === " ") {
      oldLines[oldIdx++] = text;
      newLines[newIdx++] = text;
    } else if (marker === "-") {
      oldLines[oldIdx++] = text;
    } else if (marker === "+") {
      newLines[newIdx++] = text;
    }
  }
  return { oldText: oldLines.join("\n"), newText: newLines.join("\n") };
}

/** 尝试从 diff 头或 DOM 推断文件路径 */
function _resolveFilePath(pre, codeText) {
  // 1. 优先用后端显式提供的 data-cs-file-path
  let path = pre.dataset.csFilePath;
  if (path) return path;

  // 2. 从父/祖先查找
  let el = pre.parentElement;
  while (el && el !== document.body) {
    if (el.dataset?.csFilePath) return el.dataset.csFilePath;
    el = el.parentElement;
  }

  // 3. 从 diff 头解析
  const m = codeText.match(/^---[ \t]+(.*?)\r?\n/);
  if (m) {
    const raw = m[1].trim();
    // 忽略 /dev/null
    if (raw && raw !== "/dev/null") return raw.replace(/^a\//, "");
  }
  const n = codeText.match(/^\+\+\+[ \t]+(.*?)\r?\n/);
  if (n) {
    const raw = n[1].trim();
    if (raw && raw !== "/dev/null") return raw.replace(/^b\//, "");
  }
  return "";
}

/** 为代码块添加行号并重新高亮(每行独立高亮,保留行号结构) */
function _addLineNumbers(pre, codeText, lang) {
  if (!window.hljs) return;
  const codeEl = pre.querySelector("code");
  if (!codeEl) return;

  const lines = codeText.split("\n");
  const effectiveLang = window.hljs.getLanguage(lang) ? lang : "plaintext";

  const numberedHtml = lines
    .map((line, i) => {
      try {
        const result = window.hljs.highlight(line, {
          language: effectiveLang,
          ignoreIllegals: true,
        });
        return `<div class="ln-line"><span class="ln">${i + 1}</span><span class="ln-code">${result.value}</span></div>`;
      } catch {
        return `<div class="ln-line"><span class="ln">${i + 1}</span><span class="ln-code">${escapeHtml(line)}</span></div>`;
      }
    })
    .join("");

  codeEl.innerHTML = numberedHtml;
  codeEl.dataset.csHighlighted = "1";
}

/** 当 code-block-body 可滚动时添加 overflow hint class */
function _setupOverflowHint(body) {
  const updateHint = () => {
    body.classList.toggle(
      "has-overflow",
      body.scrollHeight > body.clientHeight + 2,
    );
  };
  updateHint();
  body.addEventListener("scroll", updateHint);
  // 延迟再测一次,确保图片/字体加载后状态正确(Node 环境用 setTimeout 兜底)
  const raf =
    typeof requestAnimationFrame !== "undefined"
      ? requestAnimationFrame
      : (cb) => setTimeout(cb, 16);
  raf(updateHint);
}

function _makeActionBtn(action, icon, title) {
  const btn = document.createElement("button");
  btn.className = "code-block-action";
  btn.dataset.csAction = action;
  btn.title = title;
  btn.setAttribute("aria-label", title);
  btn.innerHTML = `<i class="fas ${icon}"></i>`;
  return btn;
}

function _buildNonDiffBlock(block, pre, codeText, lang, filePath, sendAction) {
  const header = document.createElement("div");
  header.className = "code-block-header";

  const langSpan = document.createElement("span");
  langSpan.className = "code-block-lang";
  langSpan.dataset.lang = lang;
  langSpan.innerHTML = `${escapeHtml(lang)}`;
  if (filePath) {
    const pathSpan = document.createElement("span");
    pathSpan.className = "code-block-path";
    pathSpan.textContent = filePath;
    pathSpan.title = filePath;
    langSpan.appendChild(pathSpan);
  }

  const actions = document.createElement("span");
  actions.className = "code-block-actions";

  const payload = {
    code: codeText,
    language: lang,
    filePath,
  };

  const applyBtn = _makeActionBtn("apply", "fa-file-import", "应用到编辑器");
  applyBtn.addEventListener("click", () =>
    sendAction("apply_code_block", payload),
  );
  actions.appendChild(applyBtn);

  const insertBtn = _makeActionBtn("insert", "fa-i-cursor", "插入光标处");
  insertBtn.addEventListener("click", () =>
    sendAction("insert_at_cursor", payload),
  );
  actions.appendChild(insertBtn);

  const createBtn = _makeActionBtn("create", "fa-file-circle-plus", "创建文件");
  createBtn.addEventListener("click", () =>
    sendAction("create_file_from_code", payload),
  );
  actions.appendChild(createBtn);

  const copyBtn = _makeActionBtn("copy", "fa-copy", "复制");
  copyBtn.addEventListener("click", () => {
    navigator.clipboard?.writeText(codeText).then(() => {
      const icon = copyBtn.querySelector("i");
      if (icon) icon.className = "fas fa-check";
      setTimeout(() => {
        if (icon) icon.className = "fas fa-copy";
      }, 1500);
    });
  });
  actions.appendChild(copyBtn);

  // O9 / T6: Diff 按钮 — 与当前文件做对比(有 filePath 时)
  if (filePath) {
    const diffBtn = _makeActionBtn("diff", "fa-code-compare", "Diff 对比");
    diffBtn.addEventListener("click", () =>
      sendAction("show_code_diff", payload),
    );
    actions.appendChild(diffBtn);
  }

  // O9 / T6: 拒绝按钮 — 删除代码块
  const rejectBtn = _makeActionBtn("reject", "fa-xmark", "拒绝(删除)");
  rejectBtn.classList.add("code-block-action-reject");
  rejectBtn.addEventListener("click", () => {
    if (block.parentNode) {
      // T6: 拒绝 = 从 DOM 移除 + 通知后端(用于审计/历史)
      block.parentNode.removeChild(block);
      sendAction("reject_code_block", {
        code: codeText,
        language: lang,
        filePath,
      });
    }
  });
  actions.appendChild(rejectBtn);

  header.appendChild(langSpan);
  header.appendChild(actions);

  // 包装 body:max-height + 行号
  const body = document.createElement("div");
  body.className = "code-block-body";

  pre.parentNode.insertBefore(block, pre);
  block.appendChild(header);
  _addLineNumbers(pre, codeText, lang);
  body.appendChild(pre);
  block.appendChild(body);
  _setupOverflowHint(body);
}

function _buildDiffBlock(block, pre, codeText, filePath, sendAction) {
  pre.parentNode.insertBefore(block, pre);

  const { oldText, newText } = _reconstructFromDiff(codeText);

  const header = document.createElement("div");
  header.className = "code-block-header";
  const langSpan = document.createElement("span");
  langSpan.className = "code-block-lang";
  langSpan.dataset.lang = "diff";
  langSpan.innerHTML = `diff`;
  if (filePath) {
    const pathSpan = document.createElement("span");
    pathSpan.className = "code-block-path";
    pathSpan.textContent = filePath;
    pathSpan.title = filePath;
    langSpan.appendChild(pathSpan);
  }
  const actions = document.createElement("span");
  actions.className = "code-block-actions";
  const copyBtn = _makeActionBtn("copy", "fa-copy", "复制");
  copyBtn.addEventListener("click", () =>
    navigator.clipboard?.writeText(codeText),
  );
  actions.appendChild(copyBtn);

  header.appendChild(langSpan);
  header.appendChild(actions);
  block.appendChild(header);

  // diff viewer 容器,替换 pre
  const viewerRoot = document.createElement("div");
  viewerRoot.className = "code-block-diff-viewer";
  block.appendChild(viewerRoot);
  pre.remove();

  const viewer = new CsDiffViewer(viewerRoot, {
    showHunkActions: true,
    maxHeight: "none",
  });
  viewer.setDiff(oldText, newText);

  // 监听 hunk accept/reject
  viewer.el.addEventListener("accept", (e) => {
    const hunkIndex = e.detail?.hunkIndex ?? -1;
    sendAction("accept_hunk", {
      hunkIndex,
      diff: codeText,
      filePath,
    });
  });
  viewer.el.addEventListener("reject", (e) => {
    const hunkIndex = e.detail?.hunkIndex ?? -1;
    sendAction("reject_hunk", {
      hunkIndex,
      diff: codeText,
      filePath,
    });
  });
}

/** 增强 code 块:加复制/语言徽标/文件路径/操作按钮(在容器初始化后调用一次) */
export function enhanceCodeBlocks(container, options = {}) {
  if (!container) return;
  const sendAction =
    options.onAction ||
    ((type, payload) => {
      if (bridge?.bridgeReady) {
        bridge.send({ type, ...payload });
      } else {
        console.warn(`[markdown] bridge not ready, cannot send ${type}`);
      }
    });

  container.querySelectorAll("pre").forEach((pre) => {
    if (pre.dataset.csEnhanced) return;
    pre.dataset.csEnhanced = "1";
    // 已经是 cs-code-block 包装的不再处理
    if (pre.parentElement?.classList.contains("code-block")) return;

    const codeEl = pre.querySelector("code");
    const codeText = (codeEl?.textContent || pre.textContent || "").replace(
      /\n$/,
      "",
    );
    const lang =
      (codeEl?.className.match(/language-([\w-]+)/) || [])[1] || "text";

    const block = document.createElement("div");
    block.className = "code-block";

    const filePath = _resolveFilePath(pre, codeText);

    if (_isDiffCode(codeText, lang)) {
      _buildDiffBlock(block, pre, codeText, filePath, sendAction);
    } else {
      _buildNonDiffBlock(block, pre, codeText, lang, filePath, sendAction);
    }
  });
}
