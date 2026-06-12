/**
 * run-log.js — 对话过程结构化数据层（Run Log）
 * ==============================================
 *
 * 设计目标:
 *   1. 把 Kotlin → JS 的事件流转换为结构化的、可观测的运行记录。
 *   2. 让渲染层（chat.js / components）从“直接操作 DOM”升级为“读取 RunLog 后同步视图”。
 *   3. 为历史回放、对话分支、重新生成对比、Agent 仪表盘提供统一数据基础。
 *
 * 核心概念:
 *   - RunLog: 一次 assistant turn 的完整运行记录。
 *   - Stage: turn 内的阶段（thinking / tool_call / plan / text / confirmation）。
 *   - ToolCallRecord: 工具调用的结构化记录，聚合 start/delta/complete/error 事件。
 *   - PlanRecord: 计划记录，聚合 plan_generated / approved / rejected / modified 事件。
 *
 * AG-UI 对齐（可选）:
 *   事件命名保留 CodeSage 当前约定，但内部同时识别 AG-UI 风格别名，例如:
 *   - REASONING_START/MESSAGE_CONTENT/END -> thinking_start/update/complete
 *   - TOOL_CALL_START/ARGS/END/RESULT -> tool_call_start/delta/complete
 *   - TEXT_MESSAGE_CONTENT -> text_delta
 *   - RUN_ERROR -> error
 */

import { genId } from "./utils.js";

export const STAGE_TYPES = Object.freeze([
  "thinking",
  "tool_call",
  "plan",
  "text",
  "confirmation",
]);

export const STAGE_STATUSES = Object.freeze([
  "running",
  "completed",
  "failed",
  "stopped",
]);

/** 估算 token 数（粗略: 1 token ≈ 3 字符，兼顾中英文） */
function estimateTokens(text) {
  if (!text) return 0;
  return Math.ceil(String(text).length / 3);
}

/**
 * Stage — 一次运行阶段
 */
export class Stage {
  constructor(data = {}) {
    this.id = data.id || genId("stage");
    this.type = data.type || "text";
    this.status = data.status || "running";
    this.startTime = data.startTime || Date.now();
    this.endTime = data.endTime || null;
    // 关联到具体组件数据
    this.thinkingId = data.thinkingId || null;
    this.toolCallId = data.toolCallId || null;
    this.planId = data.planId || null;
    this.textSegmentId = data.textSegmentId || null;
    // 额外元数据
    this.meta = data.meta ? { ...data.meta } : {};
  }

  get elapsedMs() {
    return (this.endTime || Date.now()) - this.startTime;
  }

  complete(endTime) {
    this.status = "completed";
    this.endTime = endTime || Date.now();
  }

  fail(endTime) {
    this.status = "failed";
    this.endTime = endTime || Date.now();
  }

  stop(endTime) {
    this.status = "stopped";
    this.endTime = endTime || Date.now();
  }

  toJSON() {
    return {
      id: this.id,
      type: this.type,
      status: this.status,
      startTime: this.startTime,
      endTime: this.endTime,
      thinkingId: this.thinkingId,
      toolCallId: this.toolCallId,
      planId: this.planId,
      textSegmentId: this.textSegmentId,
      meta: this.meta,
    };
  }

  static fromJSON(json) {
    return new Stage(json);
  }
}

/**
 * ToolCallRecord — 工具调用记录
 */
export class ToolCallRecord {
  constructor(data = {}) {
    this.toolCallId = data.toolCallId || data.id || genId("tool");
    this.turnId = data.turnId || "";
    this.name = data.name || data.toolName || "tool";
    this.summary = data.summary || "";
    this.arguments = data.arguments || {};
    this.icon = data.icon || null;
    this.status = data.status || "running";
    this.startTime = data.startTime || Date.now();
    this.endTime = data.endTime || null;
    this.result = data.result != null ? data.result : null;
    this.success = data.success != null ? data.success : null;
    this.stream = data.stream || "";
    this.error = data.error || null;
    // 关联的 plan step
    this.stepIds = Array.isArray(data.stepIds) ? [...data.stepIds] : [];
  }

  get elapsedMs() {
    return (this.endTime || Date.now()) - this.startTime;
  }

  appendDelta(delta) {
    this.stream += delta;
  }

  complete(success, result) {
    this.success = !!success;
    this.status = success ? "completed" : "failed";
    this.endTime = Date.now();
    this.result = result;
  }

  fail(error) {
    this.success = false;
    this.status = "failed";
    this.endTime = Date.now();
    this.error = error;
  }

  toJSON() {
    return {
      toolCallId: this.toolCallId,
      turnId: this.turnId,
      name: this.name,
      summary: this.summary,
      arguments: this.arguments,
      icon: this.icon,
      status: this.status,
      startTime: this.startTime,
      endTime: this.endTime,
      result: this.result,
      success: this.success,
      stream: this.stream,
      error: this.error,
      stepIds: this.stepIds,
    };
  }

  static fromJSON(json) {
    return new ToolCallRecord(json);
  }
}

/**
 * PlanRecord — 计划记录
 */
export class PlanRecord {
  constructor(data = {}) {
    this.planId = data.planId || data.id || genId("plan");
    this.turnId = data.turnId || "";
    this.description = data.description || "";
    this.overall = data.overall || data.status || "pending";
    this.steps = (data.steps || []).map((s) => ({
      id: s.id || genId("step"),
      description: s.description || s.text || "",
      status: s.status || "pending",
      dependsOn: Array.isArray(s.dependsOn) ? [...s.dependsOn] : [],
      toolCallIds: Array.isArray(s.toolCallIds) ? [...s.toolCallIds] : [],
    }));
  }

  setStepStatus(stepId, status) {
    const step = this.steps.find((s) => s.id === stepId);
    if (step) step.status = status;
    this._recalcOverall();
  }

  setOverall(overall) {
    this.overall = overall;
  }

  updateSteps(steps) {
    this.steps = (steps || []).map((s) => ({
      id: s.id || genId("step"),
      description: s.description || s.text || "",
      status: s.status || "pending",
      dependsOn: Array.isArray(s.dependsOn) ? [...s.dependsOn] : [],
      toolCallIds: Array.isArray(s.toolCallIds) ? [...s.toolCallIds] : [],
    }));
    this._recalcOverall();
  }

  _recalcOverall() {
    if (this.overall === "rejected" || this.overall === "approved") return;
    const total = this.steps.length;
    if (total === 0) return;
    const completed = this.steps.filter((s) => s.status === "completed").length;
    const failed = this.steps.filter((s) => s.status === "failed").length;
    if (completed === total) this.overall = "completed";
    else if (failed > 0) this.overall = "failed";
    else if (completed > 0) this.overall = "running";
    else this.overall = "pending";
  }

  /**
   * 根据 dependsOn 构建层级树，返回 [{ step, depth, children }]。
   * 目前只支持单根或多根的简单层级（无环）。
   */
  buildTree() {
    const map = new Map(this.steps.map((s) => [s.id, { ...s, children: [] }]));
    const roots = [];
    for (const s of this.steps) {
      if (!s.dependsOn || s.dependsOn.length === 0) {
        roots.push(map.get(s.id));
      } else {
        // 把当前 step 挂到最后一个依赖下（简化层级）
        const parentId = s.dependsOn[s.dependsOn.length - 1];
        const parent = map.get(parentId);
        if (parent) {
          parent.children.push(map.get(s.id));
        } else {
          roots.push(map.get(s.id));
        }
      }
    }
    return roots;
  }

  toJSON() {
    return {
      planId: this.planId,
      turnId: this.turnId,
      description: this.description,
      overall: this.overall,
      steps: this.steps.map((s) => ({ ...s })),
    };
  }

  static fromJSON(json) {
    return new PlanRecord(json);
  }
}

/**
 * TextSegment — 文本流片段
 */
export class TextSegment {
  constructor(data = {}) {
    this.id = data.id || genId("text");
    this.turnId = data.turnId || "";
    this.content = data.content || "";
    this.startTime = data.startTime || Date.now();
    this.endTime = data.endTime || null;
  }

  append(delta) {
    this.content += delta;
  }

  get tokens() {
    return estimateTokens(this.content);
  }

  toJSON() {
    return {
      id: this.id,
      turnId: this.turnId,
      content: this.content,
      startTime: this.startTime,
      endTime: this.endTime,
    };
  }

  static fromJSON(json) {
    return new TextSegment(json);
  }
}

/**
 * RunLog — 一次 assistant turn 的完整运行记录
 */
export class RunLog {
  constructor(data = {}) {
    this.runId = data.runId || genId("run");
    this.turnId = data.turnId || this.runId;
    this.status = data.status || "running";
    this.stages = (data.stages || []).map((s) =>
      s instanceof Stage ? s : Stage.fromJSON(s),
    );
    this.textSegments = (data.textSegments || []).map((t) =>
      t instanceof TextSegment ? t : TextSegment.fromJSON(t),
    );
    this.toolCalls = (data.toolCalls || []).map((t) =>
      t instanceof ToolCallRecord ? t : ToolCallRecord.fromJSON(t),
    );
    this.plan = data.plan
      ? data.plan instanceof PlanRecord
        ? data.plan
        : PlanRecord.fromJSON(data.plan)
      : undefined;
    this.metrics = {
      thinkingMs: data.metrics?.thinkingMs || 0,
      toolMs: data.metrics?.toolMs || 0,
      generationMs: data.metrics?.generationMs || 0,
      tokensIn: data.metrics?.tokensIn || 0,
      tokensOut: data.metrics?.tokensOut || 0,
    };
    this.createdAt = data.createdAt || Date.now();
    this.updatedAt = data.updatedAt || Date.now();
  }

  _touch() {
    this.updatedAt = Date.now();
  }

  // ============ Stage 操作 ============

  addStage(stage) {
    this.stages.push(stage);
    this._touch();
    return stage;
  }

  findStage(filter) {
    return this.stages.find((s) => {
      for (const [k, v] of Object.entries(filter)) {
        if (s[k] !== v) return false;
      }
      return true;
    });
  }

  findRunningStage(type, key, value) {
    return this.stages.find(
      (s) => s.status === "running" && s.type === type && s[key] === value,
    );
  }

  get currentStage() {
    // 取最后一个 running stage；没有则取最后一个 stage
    const running = this.stages.filter((s) => s.status === "running");
    if (running.length) return running[running.length - 1];
    return this.stages[this.stages.length - 1] || null;
  }

  get runningStages() {
    return this.stages.filter((s) => s.status === "running");
  }

  get completedStages() {
    return this.stages.filter((s) => s.status === "completed");
  }

  // ============ Thinking 操作 ============

  startThinking(thinkingId) {
    const id = thinkingId || genId("thinking");
    const stage = new Stage({
      type: "thinking",
      status: "running",
      thinkingId: id,
      startTime: Date.now(),
    });
    this.addStage(stage);
    return stage;
  }

  updateThinking(thinkingId, _delta) {
    // thinking 内容不保存在 RunLog，由组件自己维护；这里只更新时间戳
    this._touch();
  }

  completeThinking(thinkingId) {
    const stage = this.findRunningStage("thinking", "thinkingId", thinkingId);
    if (stage) {
      stage.complete();
      this.metrics.thinkingMs += stage.elapsedMs;
    }
    this._touch();
  }

  // ============ Text 操作 ============

  ensureTextSegment(segmentId) {
    let seg = this.textSegments.find((t) => t.id === segmentId);
    if (!seg) {
      seg = new TextSegment({ id: segmentId, turnId: this.turnId });
      this.textSegments.push(seg);
    }
    return seg;
  }

  appendText(segmentId, delta) {
    const seg = this.ensureTextSegment(segmentId);
    seg.append(delta);
    this.metrics.tokensOut += estimateTokens(delta);
    this._touch();
    return seg;
  }

  // ============ Tool Call 操作 ============

  startToolCall(toolCallId, data) {
    let rec = this.toolCalls.find((t) => t.toolCallId === toolCallId);
    if (!rec) {
      rec = new ToolCallRecord({ ...data, toolCallId, turnId: this.turnId });
      this.toolCalls.push(rec);
    }
    const stage = new Stage({
      type: "tool_call",
      status: "running",
      toolCallId,
      startTime: rec.startTime,
      meta: { name: rec.name, summary: rec.summary },
    });
    this.addStage(stage);
    return rec;
  }

  updateToolCall(toolCallId, delta) {
    const rec = this.toolCalls.find((t) => t.toolCallId === toolCallId);
    if (rec) rec.appendDelta(delta);
    this._touch();
    return rec;
  }

  completeToolCall(toolCallId, success, result) {
    const rec = this.toolCalls.find((t) => t.toolCallId === toolCallId);
    if (rec) {
      rec.complete(success, result);
      this.metrics.toolMs += rec.elapsedMs;
      const stage = this.findRunningStage(
        "tool_call",
        "toolCallId",
        toolCallId,
      );
      if (stage) rec.success ? stage.complete() : stage.fail();
    }
    this._touch();
    return rec;
  }

  failToolCall(toolCallId, error) {
    const rec = this.toolCalls.find((t) => t.toolCallId === toolCallId);
    if (rec) {
      rec.fail(error);
      this.metrics.toolMs += rec.elapsedMs;
      const stage = this.findRunningStage(
        "tool_call",
        "toolCallId",
        toolCallId,
      );
      if (stage) stage.fail();
    }
    this._touch();
    return rec;
  }

  // ============ Plan 操作 ============

  setPlan(data) {
    this.plan = new PlanRecord({ ...data, turnId: this.turnId });
    const stage = new Stage({
      type: "plan",
      status: "running",
      planId: this.plan.planId,
      meta: { description: this.plan.description },
    });
    this.addStage(stage);
    this._touch();
    return this.plan;
  }

  updatePlan(data) {
    if (!this.plan) return null;
    if (data.steps) this.plan.updateSteps(data.steps);
    if (data.overall) this.plan.setOverall(data.overall);
    if (data.description) this.plan.description = data.description;
    this._touch();
    return this.plan;
  }

  approvePlan() {
    if (!this.plan) return;
    this.plan.setOverall("approved");
    const stage = this.findRunningStage("plan", "planId", this.plan.planId);
    if (stage) stage.complete();
    this._touch();
  }

  rejectPlan() {
    if (!this.plan) return;
    this.plan.setOverall("rejected");
    const stage = this.findRunningStage("plan", "planId", this.plan.planId);
    if (stage) stage.fail();
    this._touch();
  }

  // ============ 生命周期 ============

  complete() {
    this.status = "completed";
    // 把所有仍在 running 的 stage 标记为 completed
    this.stages.forEach((s) => {
      if (s.status === "running") s.complete();
    });
    this._updateMetrics();
    this._touch();
  }

  fail(error) {
    this.status = "failed";
    this.error = error;
    this.stages.forEach((s) => {
      if (s.status === "running") s.fail();
    });
    this._updateMetrics();
    this._touch();
  }

  stop() {
    this.status = "stopped";
    this.stages.forEach((s) => {
      if (s.status === "running") s.stop();
    });
    this._updateMetrics();
    this._touch();
  }

  _updateMetrics() {
    this.metrics.thinkingMs = this.stages
      .filter((s) => s.type === "thinking" && s.endTime)
      .reduce((sum, s) => sum + s.elapsedMs, 0);
    this.metrics.toolMs = this.toolCalls
      .filter((t) => t.endTime)
      .reduce((sum, t) => sum + t.elapsedMs, 0);
    this.metrics.generationMs = Date.now() - this.createdAt;
  }

  // ============ 聚合 API ============

  getSummary() {
    return {
      runId: this.runId,
      turnId: this.turnId,
      status: this.status,
      stageCount: this.stages.length,
      currentStage: this.currentStage
        ? { type: this.currentStage.type, status: this.currentStage.status }
        : null,
      toolCount: this.toolCalls.length,
      completedTools: this.toolCalls.filter((t) => t.status === "completed")
        .length,
      failedTools: this.toolCalls.filter((t) => t.status === "failed").length,
      hasPlan: !!this.plan,
      planOverall: this.plan?.overall || null,
      metrics: { ...this.metrics },
      elapsedMs: Date.now() - this.createdAt,
    };
  }

  // ============ 序列化 ============

  toJSON() {
    return {
      runId: this.runId,
      turnId: this.turnId,
      status: this.status,
      stages: this.stages.map((s) => s.toJSON()),
      textSegments: this.textSegments.map((t) => t.toJSON()),
      toolCalls: this.toolCalls.map((t) => t.toJSON()),
      plan: this.plan ? this.plan.toJSON() : undefined,
      metrics: { ...this.metrics },
      createdAt: this.createdAt,
      updatedAt: this.updatedAt,
    };
  }

  static fromJSON(json) {
    return new RunLog(json);
  }
}

/**
 * RunLogBuilder — 按 turn/run 累积事件，生成结构化 RunLog
 */
export class RunLogBuilder {
  constructor() {
    /** turnId -> RunLog */
    this.runLogs = new Map();
  }

  /**
   * 处理单个事件，返回对应的 RunLog。
   * @param {object} event
   * @returns {RunLog | null}
   */
  processEvent(event) {
    if (!event || typeof event !== "object") return null;
    const type = this._normalizeType(event.type);
    const turnId = event.turnId || event.runId;
    if (!turnId) return null;

    const runLog = this._ensureRunLog(turnId);

    switch (type) {
      case "start_turn":
        // 如果已存在则复用，避免重复 start
        break;
      case "end_turn":
        runLog.complete();
        break;
      case "text_delta": {
        const segId = event.segmentId || "default";
        if (!runLog.findRunningStage("text", "textSegmentId", segId)) {
          runLog.addStage(
            new Stage({
              type: "text",
              status: "running",
              textSegmentId: segId,
            }),
          );
        }
        runLog.appendText(segId, event.delta || "");
        break;
      }
      case "thinking_start":
        runLog.startThinking(event.thinkingId || genId("thinking"));
        break;
      case "thinking_update":
        runLog.updateThinking(event.thinkingId, event.message || "");
        break;
      case "thinking_complete":
        runLog.completeThinking(event.thinkingId);
        break;
      case "tool_call_start":
        runLog.startToolCall(event.toolId || event.toolCallId, {
          name: event.toolName,
          summary: event.summary,
          arguments: event.arguments,
          icon: event.icon,
        });
        break;
      case "tool_call_delta":
        runLog.updateToolCall(
          event.toolId || event.toolCallId,
          event.delta || "",
        );
        break;
      case "tool_call_complete":
        runLog.completeToolCall(
          event.toolId || event.toolCallId,
          event.success,
          event.result,
        );
        break;
      case "tool_call_error":
        runLog.failToolCall(event.toolId || event.toolCallId, event.error);
        break;
      case "plan_generated":
        runLog.setPlan({
          planId: event.planId,
          description: event.description,
          steps: event.steps,
        });
        break;
      case "plan_approved":
        runLog.approvePlan();
        break;
      case "plan_rejected":
        runLog.rejectPlan();
        break;
      case "plan_modified":
        runLog.updatePlan({ steps: event.steps, overall: event.overall });
        break;
      case "error":
        runLog.fail(event.message || event.error || "未知错误");
        break;
      case "stop_generation":
        runLog.stop();
        break;
      default:
        // 未知事件：忽略，避免污染 RunLog
        return runLog;
    }
    return runLog;
  }

  _ensureRunLog(turnId) {
    let runLog = this.runLogs.get(turnId);
    if (!runLog) {
      runLog = new RunLog({ turnId, runId: turnId });
      this.runLogs.set(turnId, runLog);
    }
    return runLog;
  }

  /**
   * 统一事件类型命名，兼容 AG-UI 风格别名。
   */
  _normalizeType(type) {
    if (!type) return "";
    const aliases = {
      REASONING_START: "thinking_start",
      REASONING_MESSAGE_CONTENT: "thinking_update",
      REASONING_END: "thinking_complete",
      TEXT_MESSAGE_CONTENT: "text_delta",
      TOOL_CALL_START: "tool_call_start",
      TOOL_CALL_ARGS: "tool_call_delta",
      TOOL_CALL_END: "tool_call_complete",
      TOOL_CALL_RESULT: "tool_call_complete",
      TOOL_CALL_ERROR: "tool_call_error",
      RUN_ERROR: "error",
      RUN_END: "end_turn",
      RUN_START: "start_turn",
    };
    return aliases[type] || type;
  }

  getRunLog(turnId) {
    return this.runLogs.get(turnId) || null;
  }

  getAllRunLogs() {
    return Array.from(this.runLogs.values());
  }

  /**
   * 从历史消息批量重建 RunLog（用于 history / session restore）。
   * 注意：这里是离线重建，不重新触发事件流。
   */
  rebuildFromMessages(messages) {
    this.runLogs.clear();
    for (const m of messages || []) {
      if (m.role !== "assistant" || !m.turnId) continue;
      const runLog = new RunLog({ turnId: m.turnId });
      if (m.thinking) {
        const stage = runLog.startThinking();
        stage.complete();
        runLog.metrics.thinkingMs = m.thinkingDurationMs || 0;
      }
      if (m.content) {
        runLog.appendText("default", m.content);
      }
      for (const tc of m.toolCalls || []) {
        runLog.startToolCall(tc.id, {
          name: tc.name,
          summary: tc.summary,
          arguments: tc.arguments,
          icon: tc.icon,
        });
        runLog.completeToolCall(tc.id, tc.success !== false, tc.result);
      }
      if (m.plan) {
        runLog.setPlan(m.plan);
      }
      runLog.complete();
      this.runLogs.set(m.turnId, runLog);
    }
    return this.getAllRunLogs();
  }

  toSnapshot() {
    return {
      runLogs: Array.from(this.runLogs.entries()).map(([turnId, runLog]) => ({
        turnId,
        data: runLog.toJSON(),
      })),
    };
  }

  fromSnapshot(snapshot) {
    this.runLogs.clear();
    if (!snapshot || !snapshot.runLogs) return this;
    for (const { turnId, data } of snapshot.runLogs) {
      this.runLogs.set(turnId, RunLog.fromJSON(data));
    }
    return this;
  }
}

export const runLogBuilder = new RunLogBuilder();
