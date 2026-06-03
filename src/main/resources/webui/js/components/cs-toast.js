/**
 * cs-toast 组件
 *
 * 用法:
 *   import { toast } from "./components/cs-toast.js";
 *   toast.success("已保存");
 *   toast.error("出错了", 5000);
 *
 * 也支持 command palette 形式:
 *   toast("普通消息");
 *   toast("错误", { variant: "error", duration: 4000 });
 */

const VARIANT_ICONS = {
    info: '<i class="fas fa-info-circle"></i>',
    success: '<i class="fas fa-check-circle"></i>',
    warning: '<i class="fas fa-exclamation-triangle"></i>',
    error: '<i class="fas fa-times-circle"></i>',
};

function ensureContainer() {
    let c = document.getElementById("cs-toast-container");
    if (!c) {
        c = document.createElement("div");
        c.id = "cs-toast-container";
        c.className = "cs-toast-container";
        document.body.appendChild(c);
    }
    return c;
}

function show(message, opts = {}) {
    const variant = opts.variant || "info";
    const duration = opts.duration || 3000;
    const container = ensureContainer();
    const el = document.createElement("div");
    el.className = `cs-toast ${variant}`;
    el.setAttribute("role", "status");
    el.innerHTML = `${VARIANT_ICONS[variant] || VARIANT_ICONS.info}<span>${message}</span>`;
    container.appendChild(el);
    const hide = () => {
        el.classList.add("leaving");
        setTimeout(() => el.remove(), 200);
    };
    el.addEventListener("click", hide);
    setTimeout(hide, duration);
    return hide;
}

export const toast = Object.assign(
    (msg, opts) => show(msg, opts),
    {
        info: (msg, duration) => show(msg, { variant: "info", duration }),
        success: (msg, duration) => show(msg, { variant: "success", duration }),
        warning: (msg, duration) => show(msg, { variant: "warning", duration }),
        error: (msg, duration) => show(msg, { variant: "error", duration }),
    },
);
