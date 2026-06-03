/**
 * cs-plan 组件 — 计划/任务列表
 *
 * 设计文档 §9:
 *   - Plan 步骤 5 态: pending / running / completed / failed / blocked
 *   - 整体可折叠,默认展开
 *   - 进度 1/4 显示
 *   - Approve / Edit / Reject 按钮(可选)
 */

import { escapeHtml } from "../utils.js";

const STEP_STATUS_META = {
    pending:   { icon: "○", cls: "pending", label: "等待" },
    running:   { icon: "●", cls: "running", label: "进行中" },
    completed: { icon: "✓", cls: "completed", label: "完成" },
    failed:    { icon: "✕", cls: "failed", label: "失败" },
    blocked:   { icon: "⏸", cls: "blocked", label: "阻塞" },
};

const OVERALL_STATUS_META = {
    pending:   { icon: "fa-list-check", label: "计划已生成" },
    approved:  { icon: "fa-thumbs-up",  label: "计划已批准" },
    rejected:  { icon: "fa-thumbs-down", label: "计划被拒绝" },
    modified:  { icon: "fa-pen-to-square", label: "计划已修改" },
    running:   { icon: "fa-spinner spin", label: "执行中" },
    completed: { icon: "fa-check-circle", label: "已完成" },
};

export class Plan {
    /**
     * @param {object} opts
     * @param {string} opts.planId
     * @param {string} [opts.description]
     * @param {Array<{id,description,dependsOn?}>} opts.steps
     */
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

        this.el = document.createElement("div");
        this.el.className = "plan-card";
        this.el.setAttribute("data-cs-plan", this.planId);
        this._render();
    }

    _render() {
        const meta = OVERALL_STATUS_META[this.overall];
        const done = this.steps.filter((s) => s.status === "completed").length;
        const total = this.steps.length;
        const percent = total > 0 ? Math.round((done / total) * 100) : 0;

        this.el.innerHTML = `
            <div class="plan-card-header" data-cs-role="header" style="cursor:pointer;user-select:none;">
                <i class="${meta.icon}" style="color:var(--accent);"></i>
                <span class="plan-card-title">Plan</span>
                <span class="plan-card-progress" data-cs-role="progress">${done}/${total} · ${percent}%</span>
                <span class="plan-card-summary" data-cs-role="summary">${escapeHtml(this.description)}</span>
                <i class="fas fa-chevron-down" style="font-size:10px;color:var(--fg-2);margin-left:auto;transition:transform 200ms;" data-cs-role="chevron"></i>
            </div>
            <ul class="plan-steps" data-cs-role="steps">
                ${this.steps.map((s, i) => this._renderStep(s, i)).join("")}
            </ul>
            ${this._renderActions()}
        `;
        this.el.querySelector('[data-cs-role="header"]')
            .addEventListener("click", () => this.toggle());
    }

    _renderStep(step, index) {
        const meta = STEP_STATUS_META[step.status] || STEP_STATUS_META.pending;
        return `
            <li class="plan-step ${meta.cls}" data-cs-step-id="${escapeHtml(step.id)}">
                <span class="plan-step-icon">${meta.icon}</span>
                <span class="plan-step-text">
                    <span style="color:var(--fg-2);margin-right:6px;">${index + 1}.</span>
                    ${escapeHtml(step.description)}
                </span>
            </li>
        `;
    }

    _renderActions() {
        if (this.overall !== "pending") return "";
        return `
            <div class="turn-actions visible" style="padding:var(--space-1) var(--space-3) var(--space-3);">
                <button class="turn-action-btn" data-cs-action="approve"><i class="fas fa-thumbs-up"></i>&nbsp;批准</button>
                <button class="turn-action-btn" data-cs-action="modify"><i class="fas fa-pen"></i>&nbsp;编辑</button>
                <button class="turn-action-btn" data-cs-action="reject" style="color:var(--error);"><i class="fas fa-times"></i>&nbsp;拒绝</button>
            </div>
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
        this._render();
    }

    /** 整步骤完成 */
    markAllCompleted() {
        this.steps.forEach((s) => (s.status = "completed"));
        this.overall = "completed";
        this._render();
        // 1.5s 后折叠
        setTimeout(() => this.collapse(), 1500);
    }

    toggle() {
        const steps = this.el.querySelector('[data-cs-role="steps"]');
        const chevron = this.el.querySelector('[data-cs-role="chevron"]');
        if (!steps) return;
        const isOpen = steps.style.display !== "none";
        if (isOpen) {
            this.collapse();
        } else {
            steps.style.display = "";
            chevron.style.transform = "rotate(180deg)";
            this.collapsed = false;
        }
    }

    collapse() {
        const steps = this.el.querySelector('[data-cs-role="steps"]');
        const chevron = this.el.querySelector('[data-cs-role="chevron"]');
        const actions = this.el.querySelector('.turn-actions');
        if (steps) steps.style.display = "none";
        if (actions) actions.style.display = "none";
        if (chevron) chevron.style.transform = "";
        this.collapsed = true;
    }

    destroy() {
        this.el.remove();
    }
}
