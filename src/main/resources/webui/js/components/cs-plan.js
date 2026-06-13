/**
 * cs-plan v2.0 — 计划/任务列表
 * ==============================
 *
 * 设计核心:
 *   - 独立卡片,顶栏显示进度 chip + 进度条
 *   - 默认展开(计划需要审阅)
 *   - 当前步骤:左侧 2px accent 边 + 浅色背景
 *   - 完成步骤:灰色 + 勾
 *   - 步骤状态: pending / running / completed / failed / blocked
 *   - 整体可折叠
 *   - 完成后 1.5s 自动折叠
 *
 * 事件契约 (与后端对齐):
 *   plan_generated  { turnId, planId, description, steps: [{id, description, dependsOn}] }
 *   plan_approved   { turnId, planId }
 *   plan_rejected   { turnId, planId, reason }
 *   plan_modified   { turnId, planId, steps: [...] }
 */

import { escapeHtml } from "../utils.js";

const STEP_STATUS_META = {
  pending: { icon: "○", cls: "pending", label: "等待" },
  running: { icon: "●", cls: "running", label: "进行中" },
  completed: { icon: "✓", cls: "completed", label: "完成" },
  failed: { icon: "✕", cls: "failed", label: "失败" },
  blocked: { icon: "⏸", cls: "blocked", label: "阻塞" },
};

const OVERALL_STATUS_META = {
  pending: { icon: "fa-list-check", label: "计划待审批" },
  approved: { icon: "fa-thumbs-up", label: "已批准" },
  rejected: { icon: "fa-thumbs-down", label: "已拒绝" },
  modified: { icon: "fa-pen-to-square", label: "已修改" },
  running: { icon: "fa-spinner spin", label: "执行中" },
  completed: { icon: "fa-check-circle", label: "已完成" },
};

export class Plan {
  constructor(opts = {}) {
    this.planId = opts.planId;
    this.description = opts.description || "";
    this.steps = (opts.steps || []).map((s) => ({
      id: s.id,
      description: s.description || s.text || "",
      status: "pending",
      dependsOn: s.dependsOn || [],
    }));
    this.overall = "pending";
    this.collapsed = false;
    this.onApprove = opts.onApprove;
    this.onReject = opts.onReject;
    this.onModify = opts.onModify;
    this._autoCollapseTimer = null;

    this.el = document.createElement("div");
    this.el.className = "plan-card";
    this.el.setAttribute("data-cs-plan", this.planId);
    this._render();
  }

  _render() {
    const meta =
      OVERALL_STATUS_META[this.overall] || OVERALL_STATUS_META.pending;
    const done = this.steps.filter((s) => s.status === "completed").length;
    const total = this.steps.length;
    const percent = total > 0 ? Math.round((done / total) * 100) : 0;
    const isOpen = !this.collapsed;

    this.el.classList.toggle("approved", this.overall === "approved");
    this.el.classList.toggle("rejected", this.overall === "rejected");

    this.el.innerHTML = `
            <div class="plan-header" data-cs-role="header" role="button" tabindex="0" aria-expanded="${isOpen}">
                <div class="plan-icon"><i class="fas ${meta.icon}"></i></div>
                <div class="plan-title">
                    ${escapeHtml(meta.label)}
                    <span class="plan-progress-badge">${done}/${total}</span>
                </div>
                <div class="plan-progress-bar" aria-hidden="true">
                    <div class="plan-progress-fill" style="width:${percent}%"></div>
                </div>
                <i class="fas fa-chevron-down plan-chevron${isOpen ? " open" : ""}" data-cs-role="chevron"></i>
            </div>
            ${this.description ? `<div class="plan-description">${escapeHtml(this.description)}</div>` : ""}
            <div class="plan-steps${isOpen ? " open" : ""}" data-cs-role="steps">
                <ul class="plan-steps-inner">
                    ${this.steps.map((s, i) => this._renderStep(s, i)).join("")}
                </ul>
            </div>
            <div class="plan-actions${isOpen && this.overall === "pending" ? " open" : ""}" data-cs-role="actions">
                <div class="plan-actions-inner">
                    <button class="plan-action-btn primary" data-cs-action="approve">
                        <i class="fas fa-check"></i> 批准
                    </button>
                    <button class="plan-action-btn" data-cs-action="modify">
                        <i class="fas fa-pen"></i> 修改
                    </button>
                    <button class="plan-action-btn danger" data-cs-action="reject">
                        <i class="fas fa-xmark"></i> 拒绝
                    </button>
                </div>
            </div>
        `;

    this.el
      .querySelector('[data-cs-role="header"]')
      .addEventListener("click", (e) => {
        if (e.target.closest(".plan-action-btn")) return;
        this.toggle();
      });
    this.el
      .querySelector('[data-cs-role="header"]')
      .addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          this.toggle();
        }
      });
    this.el.querySelectorAll(".plan-action-btn").forEach((btn) => {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        const a = btn.dataset.csAction;
        if (a === "approve") this.onApprove?.(this);
        else if (a === "modify") this.onModify?.(this);
        else if (a === "reject") this.onReject?.(this);
      });
    });
  }

  _renderStep(step, index) {
    const meta = STEP_STATUS_META[step.status] || STEP_STATUS_META.pending;
    const active = step.status === "running";
    return `
            <li class="plan-step ${meta.cls}${active ? " active" : ""}" data-cs-step-id="${escapeHtml(step.id)}">
                <span class="plan-step-icon">${meta.icon}</span>
                <span class="plan-step-text">
                    <span class="plan-step-num">${index + 1}.</span>${escapeHtml(step.description)}
                </span>
            </li>
        `;
  }

  setStepStatus(stepId, status) {
    const step = this.steps.find((s) => s.id === stepId);
    if (!step) return;
    step.status = status;
    this._render();
  }

  setOverallStatus(overall) {
    this.overall = overall;
    if (overall === "running" || overall === "approved") {
      this.collapsed = false;
    }
    this._render();
  }

  markStepRunning(stepId) {
    this.setStepStatus(stepId, "running");
  }

  markStepCompleted(stepId) {
    this.setStepStatus(stepId, "completed");
    // 检查是否所有 step 都完成
    if (this.steps.every((s) => s.status === "completed")) {
      this.overall = "completed";
      this._render();
      // 1.5s 后自动折叠
      if (this._autoCollapseTimer) clearTimeout(this._autoCollapseTimer);
      this._autoCollapseTimer = setTimeout(() => this.collapse(), 1500);
    }
  }

  markAllCompleted() {
    this.steps.forEach((s) => (s.status = "completed"));
    this.overall = "completed";
    this._render();
    if (this._autoCollapseTimer) clearTimeout(this._autoCollapseTimer);
    this._autoCollapseTimer = setTimeout(() => this.collapse(), 1500);
  }

  toggle() {
    this.collapsed = !this.collapsed;
    const steps = this.el.querySelector('[data-cs-role="steps"]');
    const actions = this.el.querySelector('[data-cs-role="actions"]');
    const chevron = this.el.querySelector('[data-cs-role="chevron"]');
    const header = this.el.querySelector('[data-cs-role="header"]');
    if (this.collapsed) {
      steps?.classList.remove("open");
      actions?.classList.remove("open");
      chevron?.classList.remove("open");
      header?.setAttribute("aria-expanded", "false");
    } else {
      steps?.classList.add("open");
      if (this.overall === "pending") actions?.classList.add("open");
      chevron?.classList.add("open");
      header?.setAttribute("aria-expanded", "true");
    }
  }

  collapse() {
    this.collapsed = true;
    this.el.querySelector('[data-cs-role="steps"]')?.classList.remove("open");
    this.el.querySelector('[data-cs-role="actions"]')?.classList.remove("open");
    this.el.querySelector('[data-cs-role="chevron"]')?.classList.remove("open");
    this.el
      .querySelector('[data-cs-role="header"]')
      ?.setAttribute("aria-expanded", "false");
  }

  expand() {
    this.collapsed = false;
    this.el.querySelector('[data-cs-role="steps"]')?.classList.add("open");
    if (this.overall === "pending") {
      this.el.querySelector('[data-cs-role="actions"]')?.classList.add("open");
    }
    this.el.querySelector('[data-cs-role="chevron"]')?.classList.add("open");
    this.el
      .querySelector('[data-cs-role="header"]')
      ?.setAttribute("aria-expanded", "true");
  }

  destroy() {
    if (this._autoCollapseTimer) clearTimeout(this._autoCollapseTimer);
    this.el.remove();
  }
}
