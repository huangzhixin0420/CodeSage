/**
 * cs-walkthrough 组件 — 首次启动引导
 *
 * 设计文档 P5.7:
 *   - 4 步走完核心功能介绍
 *   - localStorage 记录已展示,不再骚扰
 *   - 任意时候可以从命令面板重启
 *   - 键盘可达:→/←/Esc/Space
 */

import { t } from "../i18n.js";
import { Modal } from "./cs-modal.js";

const STORAGE_KEY = "codesage_walkthrough_v1";

const STEPS = [
  {
    title: () => t("walkthrough.step1Title"),
    desc: () => t("walkthrough.step1Desc"),
    icon: "fa-comments",
    color: "var(--accent)",
  },
  {
    title: () => t("walkthrough.step2Title"),
    desc: () => t("walkthrough.step2Desc"),
    icon: "fa-toolbox",
    color: "var(--success)",
  },
  {
    title: () => t("walkthrough.step3Title"),
    desc: () => t("walkthrough.step3Desc"),
    icon: "fa-keyboard",
    color: "var(--info)",
  },
  {
    title: () => t("walkthrough.step4Title"),
    desc: () => t("walkthrough.step4Desc"),
    icon: "fa-cog",
    color: "var(--warning)",
  },
];

export class Walkthrough {
  /**
   * @param {object} opts
   * @param {boolean} [opts.forceShow] - 强制展示(用于命令面板重启)
   */
  static show(opts = {}) {
    const content = document.createElement("div");
    content.className = "cs-walkthrough";
    content.setAttribute("role", "region");
    content.setAttribute("aria-label", "walkthrough");

    let current = 0;
    const render = () => {
      const step = STEPS[current];
      const total = STEPS.length;
      const dots = STEPS.map(
        (_, i) => `<span class="cs-walkthrough-dot ${i === current ? "active" : ""}"></span>`,
      ).join("");
      const isLast = current === STEPS.length - 1;
      content.innerHTML = `
        <div class="cs-walkthrough-icon" style="background:${step.color}-soft;color:${step.color};" aria-hidden="true">
          <i class="fas ${step.icon}"></i>
        </div>
        <h2 class="cs-walkthrough-title">${escapeHtml(step.title())}</h2>
        <p class="cs-walkthrough-desc">${escapeHtml(step.desc())}</p>
        <div class="cs-walkthrough-dots" aria-hidden="true">${dots}</div>
        <div class="cs-walkthrough-actions">
          <button class="cs-button variant-ghost size-md" data-cs-action="skip">${escapeHtml(t("walkthrough.skip"))}</button>
          <div class="cs-walkthrough-actions-right">
            ${current > 0 ? `<button class="cs-button variant-secondary size-md" data-cs-action="prev">←</button>` : ""}
            <button class="cs-button variant-primary size-md" data-cs-action="next">${escapeHtml(isLast ? t("walkthrough.done") : t("walkthrough.next"))}</button>
          </div>
        </div>
        <div class="cs-walkthrough-counter" aria-live="polite">${current + 1} / ${total}</div>
      `;
      bindActions();
    };

    const cleanup = (markDone) => {
      if (markDone) {
        try {
          localStorage.setItem(STORAGE_KEY, "1");
        } catch (e) {}
      }
      modal.close();
    };

    const bindActions = () => {
      content.querySelector('[data-cs-action="skip"]')?.addEventListener("click", () => cleanup(false));
      content.querySelector('[data-cs-action="next"]')?.addEventListener("click", () => {
        if (current < STEPS.length - 1) {
          current++;
          render();
        } else {
          cleanup(true);
        }
      });
      content.querySelector('[data-cs-action="prev"]')?.addEventListener("click", () => {
        if (current > 0) {
          current--;
          render();
        }
      });
    };

    const modal = new Modal({
      title: t("walkthrough.title"),
      content,
      size: "md",
      dismissible: false,
    });
    // 键盘
    const keyHandler = (e) => {
      if (e.key === "ArrowRight" || e.key === " ") {
        e.preventDefault();
        if (current < STEPS.length - 1) {
          current++;
          render();
        } else {
          cleanup(true);
        }
      } else if (e.key === "ArrowLeft") {
        e.preventDefault();
        if (current > 0) {
          current--;
          render();
        }
      }
    };
    content.addEventListener("keydown", keyHandler);
    modal.onClose = () => {
      content.removeEventListener("keydown", keyHandler);
    };

    render();
    modal.open();
    // 自动 focus 第一个按钮
    setTimeout(() => {
      content.querySelector('[data-cs-action="next"]')?.focus();
    }, 100);
    return modal;
  }

  /** 首次启动检测:返回 true 表示应该展示 */
  static shouldShow() {
    try {
      return !localStorage.getItem(STORAGE_KEY);
    } catch (e) {
      return false;
    }
  }
}

function escapeHtml(s) {
  if (s == null) return "";
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}
