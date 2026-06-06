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
  const action = opts.action || null; // { label, onClick }
  const container = ensureContainer();
  const el = document.createElement("div");
  el.className = `cs-toast ${variant}`;
  el.setAttribute("role", "status");
  el.innerHTML = `${VARIANT_ICONS[variant] || VARIANT_ICONS.info}<span>${message}</span>`;
  if (action && action.label) {
    const btn = document.createElement("button");
    btn.className = "cs-toast-action";
    btn.type = "button";
    btn.textContent = action.label;
    el.appendChild(btn);
    // 点 action 不触发 toast 隐藏(避免与 button 自身的 click 互相冲)
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      try {
        if (typeof action.onClick === "function") action.onClick();
      } catch (err) {
        console.warn("[toast action] failed:", err);
      }
    });
  }
  container.appendChild(el);
  const hide = () => {
    el.classList.add("leaving");
    setTimeout(() => el.remove(), 200);
  };
  // 只有点击 message 文本部分才隐藏,点 action 按钮不隐藏(见上)
  el.querySelector("span")?.addEventListener("click", hide);
  setTimeout(hide, duration);
  return hide;
}

export const toast = Object.assign((msg, opts) => show(msg, opts), {
  info: (msg, optsOrDuration) =>
    show(
      msg,
      typeof optsOrDuration === "number"
        ? { variant: "info", duration: optsOrDuration }
        : { variant: "info", ...(optsOrDuration || {}) },
    ),
  success: (msg, optsOrDuration) =>
    show(
      msg,
      typeof optsOrDuration === "number"
        ? { variant: "success", duration: optsOrDuration }
        : { variant: "success", ...(optsOrDuration || {}) },
    ),
  warning: (msg, optsOrDuration) =>
    show(
      msg,
      typeof optsOrDuration === "number"
        ? { variant: "warning", duration: optsOrDuration }
        : { variant: "warning", ...(optsOrDuration || {}) },
    ),
  error: (msg, optsOrDuration) =>
    show(
      msg,
      typeof optsOrDuration === "number"
        ? { variant: "error", duration: optsOrDuration }
        : { variant: "error", ...(optsOrDuration || {}) },
    ),
});
