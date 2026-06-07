/**
 * utils.js — 通用工具
 * ====================
 */

export function escapeHtml(str) {
    if (str == null) return "";
    return String(str)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

export function escapeJs(str) {
    if (str == null) return "";
    return String(str)
        .replace(/\\/g, "\\\\")
        .replace(/'/g, "\\'")
        .replace(/"/g, '\\"')
        .replace(/\n/g, "\\n")
        .replace(/\r/g, "\\r")
        .replace(/\t/g, "\\t");
}

export function truncate(str, n = 80) {
    if (!str) return "";
    if (str.length <= n) return str;
    return str.slice(0, n - 1) + "…";
}

export function formatDuration(ms) {
    if (ms == null || ms < 0) return "—";
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const m = Math.floor(ms / 60_000);
    const s = Math.floor((ms % 60_000) / 1000);
    return `${m}m ${s}s`;
}

export function formatRelativeTime(ts) {
    if (!ts) return "";
    const diff = Date.now() - ts;
    if (diff < 60_000) return "刚刚";
    if (diff < 60 * 60_000) return `${Math.floor(diff / 60_000)} 分钟前`;
    if (diff < 24 * 60 * 60_000) return `${Math.floor(diff / (60 * 60_000))} 小时前`;
    if (diff < 7 * 24 * 60 * 60_000) return `${Math.floor(diff / (24 * 60 * 60_000))} 天前`;
    const d = new Date(ts);
    return `${d.getMonth() + 1}/${d.getDate()}`;
}

export function formatTimeOfDay(ts) {
    if (!ts) return "";
    const d = new Date(ts);
    return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

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

export function debounce(fn, wait) {
    let t;
    return function (...args) {
        clearTimeout(t);
        t = setTimeout(() => fn.apply(this, args), wait);
    };
}

export function throttle(fn, wait) {
    let last = 0;
    let timer = null;
    return function (...args) {
        const now = Date.now();
        const remaining = wait - (now - last);
        if (remaining <= 0) {
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            last = now;
            fn.apply(this, args);
        } else if (!timer) {
            timer = setTimeout(() => {
                last = Date.now();
                timer = null;
                fn.apply(this, args);
            }, remaining);
        }
    };
}

/** 生成 turnId / toolId 等短 id */
let _idCounter = 0;
export function genId(prefix = "id") {
    _idCounter += 1;
    return `${prefix}_${Date.now().toString(36)}_${_idCounter.toString(36)}`;
}

/** 检测 mac/win 平台 (用于快捷键显示) */
export function getPlatform() {
    const ua = navigator.userAgent || "";
    if (/Mac|iPhone|iPad/i.test(ua)) return "mac";
    if (/Windows/i.test(ua)) return "win";
    if (/Linux/i.test(ua)) return "linux";
    return "mac";
}

export function shortcutLabel(combo) {
    const p = getPlatform();
    return combo
        .replace(/Cmd\+/g, p === "mac" ? "⌘" : "Ctrl+")
        .replace(/Option\+/g, p === "mac" ? "⌥" : "Alt+")
        .replace(/Shift\+/g, p === "mac" ? "⇧" : "Shift+")
        .replace(/Enter/g, "⏎");
}
