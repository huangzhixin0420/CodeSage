/**
 * CodeSage 工具函数
 * 纯函数集合,无副作用
 */

/** 转义 HTML 字符 */
export function escapeHtml(text) {
  if (text == null) return "";
  const div = document.createElement("div");
  div.textContent = String(text);
  return div.innerHTML;
}

/** 转义 JS 字符串(单/双引号) */
export function escapeJs(text) {
  if (text == null) return "";
  return String(text)
    .replace(/\\/g, "\\\\")
    .replace(/'/g, "\\'")
    .replace(/"/g, '\\"');
}

/** 简易 HTML 清理(去除危险标签和事件处理器) */
export function sanitizeHtml(html) {
  const parser = new DOMParser();
  const doc = parser.parseFromString(html, "text/html");
  doc
    .querySelectorAll("script, style, iframe, object, embed, form")
    .forEach((el) => el.remove());
  const walk = (node) => {
    if (node.nodeType === Node.ELEMENT_NODE) {
      const attrs = Array.from(node.attributes);
      for (const attr of attrs) {
        const name = attr.name.toLowerCase();
        if (
          name.startsWith("on") ||
          (name === "href" &&
            /^(javascript|data|vbscript):/i.test(attr.value)) ||
          name === "action"
        ) {
          node.removeAttribute(attr.name);
        }
      }
    }
    Array.from(node.childNodes).forEach(walk);
  };
  walk(doc.body);
  return doc.body.innerHTML;
}

/** 格式化相对时间(用于会话列表) */
export function formatRelativeTime(timestamp) {
  if (!timestamp) return "";
  const now = Date.now();
  const diff = now - timestamp;
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diff < minute) return "刚刚";
  if (diff < hour) return Math.floor(diff / minute) + " 分钟前";
  if (diff < day) return Math.floor(diff / hour) + " 小时前";
  if (diff < day * 2) return "昨天";
  if (diff < day * 7) return Math.floor(diff / day) + " 天前";
  const date = new Date(timestamp);
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

/** 截断字符串 */
export function truncate(text, max = 80, suffix = "...") {
  if (!text) return "";
  text = String(text);
  if (text.length <= max) return text;
  return text.substring(0, max - suffix.length) + suffix;
}

/** 高亮 @ 引用(返回 HTML 字符串) */
export function highlightAtReferences(text) {
  return escapeHtml(text).replace(
    /@([^\s@]+)/g,
    '<span class="at-ref">@$1</span>',
  );
}

/** 滚动到底部(节流) */
let _scrollScheduled = false;
export function scrollToBottom(container, force = false) {
  if (_scrollScheduled && !force) return;
  _scrollScheduled = true;
  requestAnimationFrame(() => {
    const c =
      typeof container === "string"
        ? document.getElementById(container)
        : container;
    if (c) c.scrollTop = c.scrollHeight;
    _scrollScheduled = false;
  });
}

/** 防抖 */
export function debounce(fn, wait) {
  let t;
  return function (...args) {
    clearTimeout(t);
    t = setTimeout(() => fn.apply(this, args), wait);
  };
}

/** 节流 */
export function throttle(fn, wait) {
  let last = 0;
  return function (...args) {
    const now = Date.now();
    if (now - last >= wait) {
      last = now;
      fn.apply(this, args);
    }
  };
}

/** 创建带 id 的 DOM 元素 */
export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") node.className = v;
    else if (k === "style" && typeof v === "object")
      Object.assign(node.style, v);
    else if (k === "dataset") Object.assign(node.dataset, v);
    else if (k.startsWith("on") && typeof v === "function") {
      node.addEventListener(k.substring(2).toLowerCase(), v);
    } else if (k === "html") {
      node.innerHTML = v;
    } else if (v === false || v == null) {
      // skip
    } else if (v === true) {
      node.setAttribute(k, "");
    } else {
      node.setAttribute(k, v);
    }
  }
  for (const child of children) {
    if (child == null) continue;
    if (Array.isArray(child))
      child.forEach(
        (c) =>
          c &&
          node.appendChild(c.nodeType ? c : document.createTextNode(String(c))),
      );
    else if (child.nodeType) node.appendChild(child);
    else node.appendChild(document.createTextNode(String(child)));
  }
  return node;
}

/** 浅拷贝 */
export function clone(obj) {
  return JSON.parse(JSON.stringify(obj));
}

/** 格式化时长 */
export function formatDuration(ms) {
  if (ms == null) return "";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  return `${m}m${s}s`;
}
