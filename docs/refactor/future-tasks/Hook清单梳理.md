# Hook 清单梳理（未来任务）

- **创建日期**: 2026-06-17
- **状态**: 待启动（依赖 StreamChunk 重构落地后跑一段时间）
- **触发来源**: StreamChunk 重构（[02 文档](../StreamChunk中转层重构-2026-06-16-02.md)）设计阶段识别
- **影响范围**: EnhancedAgentLoop 业务事件处理 + `TurnLifecycleHook` 接口扩展

---

## 1. 目标

把 EnhancedAgentLoop 内的业务编排事件处理**完整**拆分为 `TurnLifecycleHook` 列表，确保：
- 每个业务事件有独立 Hook 类
- Hook 可独立测试
- Hook 可插拔

## 2. 当前 02 文档已规划的 5 个 Hook

| Hook 类 | 触发时机 | 业务事件 |
|---------|---------|---------|
| `ToolConfirmationHook` | `onToolExecuted` | `ToolConfirmationNeeded` |
| `SubAgentDispatchHook` | `onTurnEnd` | `SubAgentStart/Progress/Complete` |
| `ContextCompressionHook` | `onTurnEnd` | `ContextCompressed` |
| `ModeSuggestionHook` | `onTurnEnd` | `ModeSuggestion` |
| `SessionMigrationHook` | `onTurnStart/End` | `SessionMigrated` |

## 3. 待梳理项

`AgentStreamEvent` 共 30+ case，本任务要全量扫描：
- 区分"模型流事件"（归 Reducer）vs"业务编排事件"（归 Hook）
- 找出 5 个 Hook 之外的遗漏业务事件
- 给每个业务事件创建对应 Hook 类（如 `ErrorRecoveryHook` / `MetricsCollectionHook` / `AutoSaveDraftHook` 等）

## 4. 实现范围

1. 全量扫描 `AgentStreamEvent` 30+ case
2. 分类矩阵：模型流 vs 业务编排
3. 缺失的 Hook 类补全
4. Hook 接口按需扩展（`onSubAgentEvent` / `onContextCompress` 等）
5. 每个 Hook 独立单测
6. 文档化 Hook 清单

## 5. 工作量

~1-1.5d

## 6. 前置条件

- [ ] StreamChunk 重构（[02 文档](../StreamChunk中转层重构-2026-06-16-02.md)）落地后跑 ≥ 1 个月
- [ ] 收集新增业务事件诉求
- [ ] EnhancedAgentLoop 当前 if/else 链已迁移到 5 个初始 Hook
