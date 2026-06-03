/**
 * Markdown 渲染 + 代码块增强
 *
 * 注意:
 *   - 早期原 chat.html 使用 marked 9.x(老版本 API)
 *   - 现阶段在 index.html 中没有引用 marked.min.js
 *   - 如果未来要启用,需要自托管到 vendor/marked.min.js 并在 index.html 加载
 *   - 此处用 marked 作为可选依赖,缺失时降级为 escapeHtml(text)
 */

import { sanitizeHtml, escapeHtml } from "./utils.js";

/** 加载 marked(动态导入 vendor 目录) */
let markedPromise = null;
function loadMarked() {
    if (markedPromise) return markedPromise;
    markedPromise = new Promise((resolve) => {
        if (typeof window.marked !== "undefined") {
            resolve(window.marked);
            return;
        }
        const script = document.createElement("script");
        script.src = "lib/marked.min.js";
        script.onload = () => resolve(window.marked || null);
        script.onerror = () => {
            console.warn("[markdown] failed to load marked, falling back");
            resolve(null);
        };
        document.head.appendChild(script);
    });
    return markedPromise;
}

/** 同步渲染(没有 marked 时降级) */
export function renderMarkdownSync(text) {
    if (text == null) return "";
    if (typeof window.marked === "undefined") {
        return escapeHtml(text).replace(/\n/g, "<br/>");
    }
    try {
        const rawHtml = window.marked.parse(text);
        return sanitizeHtml(rawHtml);
    } catch (e) {
        console.error("[markdown] render failed:", e);
        return escapeHtml(text);
    }
}

/** 异步预加载(应用启动时调用) */
export function preloadMarkdown() {
    return loadMarked();
}

/**
 * 给容器内所有 <pre> 包裹 .code-block,加 header + 操作按钮
 */
export function enhanceCodeBlocks(container) {
    if (!container) return;
    const blocks = container.querySelectorAll("pre");
    blocks.forEach((pre) => {
        if (pre.closest(".code-block")) return;
        const code = pre.querySelector("code");
        const langMatch = code?.className?.match(/language-(\w+)/);
        const lang = langMatch ? langMatch[1] : "text";
        const wrapper = document.createElement("div");
        wrapper.className = "code-block";
        const header = document.createElement("div");
        header.className = "code-block-header";
        header.innerHTML = `
            <span class="code-lang">${escapeHtml(lang)}</span>
            <div class="code-actions">
                <button class="code-action-btn" data-cs-action="copy" data-cs-tooltip="复制代码">
                    <i class="fas fa-copy"></i>&nbsp;复制
                </button>
                <button class="code-action-btn" data-cs-action="open" data-cs-tooltip="在新窗口打开">
                    <i class="fas fa-external-link-alt"></i>&nbsp;打开
                </button>
            </div>
        `;
        wrapper.appendChild(header);
        wrapper.appendChild(pre.cloneNode(true));
        pre.replaceWith(wrapper);
    });

    // 事件委托:copy / open
    container.querySelectorAll('[data-cs-action]').forEach((btn) => {
        btn.addEventListener("click", (e) => {
            const action = btn.getAttribute("data-cs-action");
            const block = btn.closest(".code-block");
            const code = block?.querySelector("code");
            if (!code) return;
            if (action === "copy") {
                navigator.clipboard?.writeText(code.textContent);
                const original = btn.innerHTML;
                btn.innerHTML = '<i class="fas fa-check"></i>&nbsp;已复制';
                setTimeout(() => (btn.innerHTML = original), 1200);
            } else if (action === "open") {
                const win = window.open("about:blank", "_blank");
                if (win) {
                    const text = code.textContent;
                    win.document.write(
                        `<!doctype html><html><head><meta charset="utf-8"><title>Code</title>` +
                        `<style>body{background:#1e1e2e;color:#c0c0d0;font-family:monospace;` +
                        `padding:20px;margin:0;white-space:pre-wrap;word-break:break-all;` +
                        `line-height:1.6;font-size:13px;}</style></head>` +
                        `<body>${escapeHtml(text)}</body></html>`,
                    );
                    win.document.close();
                }
            }
        });
    });
}

/** 同步高亮(已加载 hljs) */
export function highlightCode(container) {
    if (!container || typeof window.hljs === "undefined") return;
    container.querySelectorAll("pre code").forEach((block) => {
        try {
            window.hljs.highlightElement(block);
        } catch (e) {
            // ignore
        }
    });
}
