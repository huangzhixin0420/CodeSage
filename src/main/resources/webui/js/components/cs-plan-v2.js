/**
 * cs-plan-v2.js — 可交互执行图
 * =============================
 *
 * 目标：把 Plan 从 checklist 升级为可交互执行图。
 *
 * 功能：
 *   - 根据 dependsOn 渲染层级/树状结构。
 *   - step 支持 toolCallIds 关联，点击 step 高亮对应 tool badge/card。
 *   - 提供 inline 编辑（modify 按钮进入编辑模式）。
 *   - 保留 approve / reject / modify 回调。
 */

import { escapeHtml } from "../utils.js";
import { icon } from "../icons.js";

const STEP_STATUS_META = {
  pending: { icon: "fa-circle", cls: "pending", label: "等待" },
  running: { icon: "fa-spinner spin", cls: "running", label: "进行中" },
  completed: { icon: "fa-check", cls: "completed", label: "完成" },
  failed: { icon: "fa-xmark", cls: "failed", label: "失败" },
  blocked: { icon: "fa-pause", cls: "blocked", label: "阻塞" },
};

const OVERALL_STATUS_META = {
  pending: { icon: "fa-list-check", label: "计划待审批" },
  approved: { icon: "fa-thumbs-up", label: "已批准" },
  rejected: { icon: "fa-thumbs-down", label: "已拒绝" },
  modified: { icon: "fa-pen-to-square", label: "已修改" },
  running: { icon: "fa-spinner spin", label: "执行中" },
  completed: { icon: "fa-check-circle", label: "已完成" },
  failed: { icon: "fa-circle-exclamation", label: "失败" },
};

function buildTree(steps) {
  const map = new Map(
    steps.map((s) => [s.id, { ...s, children: [], depth: 0 }]),
  );
  const roots = [];
  for (const s of steps) {
    const node = map.get(s.id);
    if (!s.dependsOn || s.dependsOn.length === 0) {
      node.depth = 0;
      roots.push(node);
    } else {
      const parentId = s.dependsOn[s.dependsOn.length - 1];
      const parent = map.get(parentId);
      if (parent) {
        node.depth = parent.depth + 1;
        parent.children.push(node);
      } else {
        node.depth = 0;
        roots.push(node);
      }
    }
  }
  return roots;
}

export class PlanV2 {
  constructor(opts = {}) {
    this.planId = opts.planId;
    this.description = opts.description || "";
    this.steps = (opts.steps || []).map((s) => ({
      id: s.id,
      description: s.description || s.text || "",
      status: s.status || "pending",
      dependsOn: Array.isArray(s.dependsOn) ? [...s.dependsOn] : [],
      toolCallIds: Array.isArray(s.toolCallIds) ? [...s.toolCallIds] : [],
    }));
    this.overall = opts.overall || "pending";
    this.collapsed = false;
    this.editing = false;
    this.onApprove = opts.onApprove;
    this.onReject = opts.onReject;
    this.onModify = opts.onModify;
    this._autoCollapseTimer = null;

    this.el = document.createElement("div");
    this.el.className = "plan-card plan-v2";
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
    this.el.classList.toggle("editing", this.editing);

    const stepsHtml = this.editing
      ? this._renderEditor()
      : this._renderStepsTree();

    this.el.innerHTML = `
            <div class="plan-header" data-cs-role="header" role="button" tabindex="0" aria-expanded="${isOpen}">
                <div class="plan-icon">${icon(meta.icon === "fa-list-check" ? "plan" : "check", "plan-header-icon")}</div>
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
                ${stepsHtml}
            </div>
            <div class="plan-actions${isOpen && this.overall === "pending" && !this.editing ? " open" : ""}" data-cs-role="actions">
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
            ${
              this.editing
                ? `
                <div class="plan-editor-actions">
                    <button class="plan-action-btn primary" data-cs-action="save">保存</button>
                    <button class="plan-action-btn" data-cs-action="cancel">取消</button>
                </div>
            `
                : ""
            }
        `;

    this._bindEvents();
  }

  _renderStepsTree() {
    const roots = buildTree(this.steps);
    if (roots.length === 0) {
      return `<div class="plan-empty">暂无步骤</div>`;
    }
    return `<ul class="plan-tree">${roots.map((n) => this._renderTreeNode(n)).join("")}</ul>`;
  }

  _renderTreeNode(node, isLast = true) {
    const meta = STEP_STATUS_META[node.status] || STEP_STATUS_META.pending;
    const active = node.status === "running";
    const hasChildren = node.children && node.children.length > 0;
    const toolTip =
      node.toolCallIds && node.toolCallIds.length
        ? `关联工具: ${node.toolCallIds.join(", ")}`
        : "";

    const childrenHtml = hasChildren
      ? `<ul class="plan-tree-children">${node.children
          .map((child, idx) =>
            this._renderTreeNode(child, idx === node.children.length - 1),
          )
          .join("")}</ul>`
      : "";

    return `
            <li class="plan-tree-node ${isLast ? "last" : ""}" data-cs-step-id="${escapeHtml(node.id)}" title="${escapeHtml(toolTip)}">
                <div class="plan-step ${meta.cls}${active ? " active" : ""}" data-cs-step-id="${escapeHtml(node.id)}">
                    <span class="plan-step-icon"><i class="fas ${meta.icon}"></i></span>
                    <span class="plan-step-text">
                        <span class="plan-step-num">${node.depth + 1}.</span>${escapeHtml(node.description)}
                    </span>
                    ${
                      node.toolCallIds && node.toolCallIds.length
                        ? `
                        <span class="plan-step-tool-count" title="${escapeHtml(toolTip)}">
                            <i class="fas fa-gear"></i> ${node.toolCallIds.length}
                        </span>
                    `
                        : ""
                    }
                </div>
                ${childrenHtml}
            </li>
        `;
  }

  _renderEditor() {
    const rows = this.steps
      .map(
        (s, i) => `
                    <div class="plan-editor-row" data-cs-step-id="${escapeHtml(s.id)}">
                        <span class="plan-editor-num">${i + 1}.</span>
                        <input type="text" class="plan-editor-input" value="${escapeHtml(s.description)}" />
                        <button class="plan-editor-remove" data-cs-action="remove-step" title="删除">
                            <i class="fas fa-trash"></i>
                        </button>
                    </div>
                `,
      )
      .join("");
    return `
            <div class="plan-editor">
                ${rows}
                <button class="plan-action-btn" data-cs-action="add-step">
                    <i class="fas fa-plus"></i> 添加步骤
                </button>
            </div>
        `;
  }

  _bindEvents() {
    const header = this.el.querySelector('[data-cs-role="header"]');
    header?.addEventListener("click", (e) => {
      if (e.target.closest(".plan-action-btn")) return;
      this.toggle();
    });
    header?.addEventListener("keydown", (e) => {
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
        else if (a === "reject") this.onReject?.(this);
        else if (a === "modify") this._enterEditMode();
        else if (a === "save") this._saveEdit();
        else if (a === "cancel") this._cancelEdit();
        else if (a === "add-step") this._addStep();
      });
    });

    this.el.querySelectorAll(".plan-step").forEach((stepEl) => {
      stepEl.addEventListener("click", (e) => {
        e.stopPropagation();
        const stepId = stepEl.dataset.csStepId;
        this._highlightLinkedTools(stepId);
        if (this.onStepClick) this.onStepClick(stepId);
      });
    });

    this.el
      .querySelectorAll("[data-cs-action='remove-step']")
      .forEach((btn) => {
        btn.addEventListener("click", (e) => {
          e.stopPropagation();
          const row = btn.closest(".plan-editor-row");
          const stepId = row?.dataset.csStepId;
          if (stepId) this._removeStep(stepId);
        });
      });
  }

  _highlightLinkedTools(stepId) {
    const step = this.steps.find((s) => s.id === stepId);
    if (!step || !step.toolCallIds || step.toolCallIds.length === 0) return;

    // 先清除高亮
    document.querySelectorAll("[data-cs-tool-call]").forEach((el) => {
      el.classList.remove("linked-to-step");
    });

    // 高亮关联的 tool call
    for (const toolId of step.toolCallIds) {
      const el = document.querySelector(`[data-cs-tool-call="${toolId}"]`);
      if (el) {
        el.classList.add("linked-to-step");
        if (typeof el.scrollIntoView === "function") {
          el.scrollIntoView({ behavior: "smooth", block: "nearest" });
        }
      }
    }
  }

  _enterEditMode() {
    this.editing = true;
    this.collapsed = false;
    this._render();
  }

  _cancelEdit() {
    this.editing = false;
    this._render();
  }

  _saveEdit() {
    const inputs = this.el.querySelectorAll(".plan-editor-input");
    const newSteps = [];
    inputs.forEach((input, idx) => {
      const row = input.closest(".plan-editor-row");
      const oldId = row?.dataset.csStepId;
      const oldStep = this.steps.find((s) => s.id === oldId);
      newSteps.push({
        id: oldId || `step-${Date.now()}-${idx}`,
        description: input.value,
        status: oldStep?.status || "pending",
        dependsOn: oldStep?.dependsOn || [],
        toolCallIds: oldStep?.toolCallIds || [],
      });
    });
    this.steps = newSteps;
    this.editing = false;
    this.overall = "modified";
    this._render();
    this.onModify?.(this, newSteps);
  }

  _addStep() {
    this.steps.push({
      id: `step-${Date.now()}`,
      description: "",
      status: "pending",
      dependsOn:
        this.steps.length > 0 ? [this.steps[this.steps.length - 1].id] : [],
      toolCallIds: [],
    });
    this._render();
    // focus 新输入框
    const inputs = this.el.querySelectorAll(".plan-editor-input");
    inputs[inputs.length - 1]?.focus();
  }

  _removeStep(stepId) {
    this.steps = this.steps.filter((s) => s.id !== stepId);
    this._render();
  }

  setStepStatus(stepId, status) {
    const step = this.steps.find((s) => s.id === stepId);
    if (!step) return;
    step.status = status;
    this._recalcOverall();
    this._render();
  }

  setOverallStatus(overall) {
    this.overall = overall;
    if (overall === "running" || overall === "approved") {
      this.collapsed = false;
    }
    this._render();
  }

  updateSteps(steps) {
    this.steps = (steps || []).map((s) => ({
      id: s.id,
      description: s.description || s.text || "",
      status: s.status || "pending",
      dependsOn: Array.isArray(s.dependsOn) ? [...s.dependsOn] : [],
      toolCallIds: Array.isArray(s.toolCallIds) ? [...s.toolCallIds] : [],
    }));
    this._recalcOverall();
    this._render();
  }

  _recalcOverall() {
    if (["approved", "rejected", "modified"].includes(this.overall)) return;
    const total = this.steps.length;
    if (total === 0) return;
    const completed = this.steps.filter((s) => s.status === "completed").length;
    const failed = this.steps.filter((s) => s.status === "failed").length;
    if (completed === total) this.overall = "completed";
    else if (failed > 0) this.overall = "failed";
    else if (completed > 0) this.overall = "running";
    else this.overall = "pending";
  }

  toggle() {
    if (this.editing) return;
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
