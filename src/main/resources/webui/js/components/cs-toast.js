/**
 * Toast v2.0
 * ==========
 */

const VARIANT_META = {
    success: { icon: "fa-check" },
    error: { icon: "fa-xmark" },
    warning: { icon: "fa-triangle-exclamation" },
    info: { icon: "fa-circle-info" },
};

class ToastManager {
    constructor() {
        this.container = null;
        // 2026-06 E2E 友好: 延迟到第一次 show() 才找 DOM。
        // 之前在 constructor 调 _ensureContainer() 会让 module-level `new ToastManager()`
        // 在 document 还没准备好时炸(JSDOM 测试 / 单元测试场景)。
        this._initialized = false;
    }

    _ensureContainer() {
        if (this._initialized && this.container && document.body.contains(this.container)) {
            return;
        }
        this._initialized = true;
        if (typeof document === "undefined") return;  // 防御: 无 DOM 环境
        this.container =
            document.getElementById("cs-toast-container") ||
            (() => {
                const d = document.createElement("div");
                d.id = "cs-toast-container";
                d.className = "cs-toast-container";
                document.body.appendChild(d);
                return d;
            })();
    }

    show(message, variant = "info", duration = 3000) {
        this._ensureContainer();
        if (!this.container) return;
        const meta = VARIANT_META[variant] || VARIANT_META.info;
        const el = document.createElement("div");
        el.className = `cs-toast ${variant}`;
        el.innerHTML = `
            <span class="cs-toast-icon"><i class="fas ${meta.icon}"></i></span>
            <span class="cs-toast-message">${String(message || "").replace(/</g, "&lt;")}</span>
        `;
        this.container.appendChild(el);
        setTimeout(() => {
            el.classList.add("dismissing");
            setTimeout(() => el.remove(), 200);
        }, duration);
    }

    success(msg) { this.show(msg, "success"); }
    error(msg) { this.show(msg, "error", 4500); }
    warning(msg) { this.show(msg, "warning"); }
    info(msg) { this.show(msg, "info"); }
}

// Lazy singleton — 第一次访问时才创建, 避免 module 顶层触发 DOM 操作
let _toastInstance = null;
function _getToast() {
    if (!_toastInstance) _toastInstance = new ToastManager();
    return _toastInstance;
}
export const toast = new Proxy({}, {
    get(_, prop) {
        const t = _getToast();
        const v = t[prop];
        return typeof v === "function" ? v.bind(t) : v;
    },
});
if (typeof window !== "undefined") {
    window.CodeSage = window.CodeSage || {};
    window.CodeSage.toast = toast;
}
