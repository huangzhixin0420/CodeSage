/**
 * cs-agent-dashboard.js — Agent 状态仪表盘
 * =========================================
 *
 * 目标：让用户实时感知 Agent 当前阶段、耗时、tokens。
 *
 * 数据来源：RunLog 聚合（由 chat.js 传入当前 RunLog）。
 */

import { escapeHtml } from "../utils.js";
import { icon } from "../icons.js";

const STAGE_LABELS = {
  thinking: "思考中",
  tool_call: "执行工具",
  plan: "处理计划",
  text: "生成回答",
  confirmation: "等待确认",
};

function fmtTokens(n) {
  if (n == null || n === 0) return "0";
  if (n < 1000) return String(n);
  return (n / 1000).toFixed(1) + "k";
}

function fmtDuration(ms) {
  if (ms == null || ms < 0) return "0.0s";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  return `${m}m ${s}s`;
}

export class AgentDashboard {
  constructor(opts = {}) {
    this.container = opts.container;
    this.runLog = opts.runLog || null;
    this._timer = null;

    this.el = document.createElement("div");
    this.el.className = "agent-dashboard";
    this._render();
  }

  setRunLog(runLog) {
    this.runLog = runLog;
    this._render();
  }

  start() {
    this.stop();
    this._timer = setInterval(() => this._render(), 200);
  }

  stop() {
    if (this._timer) {
      clearInterval(this._timer);
      this._timer = null;
    }
  }

  _render() {
    const summary = this.runLog?.getSummary?.();
    if (
      !summary ||
      summary.status === "completed" ||
      summary.status === "failed" ||
      summary.status === "stopped"
    ) {
      this.el.style.display = "none";
      return;
    }
    this.el.style.display = "";

    const stageType = summary.currentStage?.type || "text";
    const stageLabel = STAGE_LABELS[stageType] || "运行中";
    const isSpinning = summary.status === "running";
    const metrics = summary.metrics || {};
    const elapsedMs = summary.elapsedMs || 0;

    // 进度：基于 completedTools / toolCount + 当前 stage
    let percent = 0;
    if (summary.toolCount > 0) {
      const toolProgress = (summary.completedTools / summary.toolCount) * 60;
      const stageProgress =
        { thinking: 10, text: 20, tool_call: 10, plan: 5, confirmation: 0 }[
          stageType
        ] || 0;
      percent = Math.min(95, toolProgress + stageProgress);
    } else {
      percent = stageType === "thinking" ? 30 : stageType === "text" ? 60 : 50;
    }

    this.el.innerHTML = `
            <span class="agent-stage">
                ${isSpinning ? icon("spinner", "agent-stage-icon spin") : icon("check", "agent-stage-icon")}
                ${escapeHtml(stageLabel)}
            </span>
            <span class="agent-metric" title="输入 tokens">↑ ${fmtTokens(metrics.tokensIn)}</span>
            <span class="agent-metric" title="输出 tokens">↓ ${fmtTokens(metrics.tokensOut)}</span>
            <span class="agent-metric" title="总耗时">⏱ ${fmtDuration(elapsedMs)}</span>
            <span class="agent-progress" title="预估进度">
                <span class="agent-progress-fill" style="width: ${percent}%"></span>
            </span>
        `;
  }

  destroy() {
    this.stop();
    this.el.remove();
  }
}
