/**
 * Markdown 渲染 v2.0
 * ===================
 * - 流式打字时按需渲染:对单段 text-stream-segment 直接用 marked.parse
 * - 高亮:hljs 已经在 index.html 同步加载
 * - 兜底:无 marked 时只做 escape
 */

import { escapeHtml } from "./utils.js";

let _marked = null;
let _markedPromise = null;

function loadMarked() {
    if (_marked) return Promise.resolve(_marked);
    if (_markedPromise) return _markedPromise;
    _markedPromise = new Promise((resolve) => {
        if (typeof window.marked !== "undefined") {
            _marked = window.marked;
            try {
                if (_marked.setOptions) {
                    _marked.setOptions({
                        gfm: true,
                        breaks: true,
                        headerIds: false,
                        mangle: false,
                    });
                }
            } catch {}
            resolve(_marked);
            return;
        }
        const script = document.createElement("script");
        script.src = "lib/marked.min.js";
        script.onload = () => {
            _marked = window.marked || null;
            if (_marked && _marked.setOptions) {
                _marked.setOptions({
                    gfm: true,
                    breaks: true,
                    headerIds: false,
                    mangle: false,
                });
            }
            resolve(_marked);
        };
        script.onerror = () => {
            console.warn("[markdown] failed to load marked, using fallback");
            resolve(null);
        };
        document.head.appendChild(script);
    });
    return _markedPromise;
}

/** 同步渲染:在 marked 还没加载时降级为 escape */
export function renderMarkdown(text) {
    if (!text) return "";
    if (_marked && typeof _marked.parse === "function") {
        try {
            return _marked.parse(text);
        } catch (e) {
            console.warn("[markdown] parse error:", e);
        }
    }
    // 兜底:转义 + 简单换行
    return escapeHtml(text).replace(/\n/g, "<br>");
}

export function preloadMarkdown() {
    return loadMarked();
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

/** 增强 code 块:加复制/语言徽标(在容器初始化后调用一次) */
export function enhanceCodeBlocks(container) {
    if (!container) return;
    container.querySelectorAll("pre").forEach((pre) => {
        if (pre.dataset.csEnhanced) return;
        pre.dataset.csEnhanced = "1";
        // 已经是 cs-code-block 包装的不再处理
        if (pre.parentElement?.classList.contains("code-block")) return;

        const codeEl = pre.querySelector("code");
        const lang =
            (codeEl?.className.match(/language-([\w-]+)/) || [])[1] || "text";
        const block = document.createElement("div");
        block.className = "code-block";
        const header = document.createElement("div");
        header.className = "code-block-header";
        header.innerHTML = `
            <span class="code-block-lang"><i class="fas fa-code"></i> ${escapeHtml(lang)}</span>
            <span class="code-block-actions">
                <button class="code-block-action" data-cs-action="copy" title="复制" aria-label="复制">
                    <i class="fas fa-copy"></i>
                </button>
            </span>
        `;
        pre.parentNode.insertBefore(block, pre);
        block.appendChild(header);
        block.appendChild(pre);
        const copyBtn = header.querySelector('[data-cs-action="copy"]');
        copyBtn?.addEventListener("click", () => {
            const text = codeEl?.textContent || pre.textContent || "";
            navigator.clipboard?.writeText(text).then(() => {
                copyBtn.classList.add("copied");
                const icon = copyBtn.querySelector("i");
                const orig = icon?.className;
                if (icon) icon.className = "fas fa-check";
                setTimeout(() => {
                    copyBtn.classList.remove("copied");
                    if (icon) icon.className = orig;
                }, 1500);
            });
        });
    });
}
