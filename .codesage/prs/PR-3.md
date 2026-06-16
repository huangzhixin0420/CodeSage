# PR-3: EnhancedAgentLoop 接入 Reducer + Hook 拆分 + 验证

## 包含 Commits
- 24e2a8e commit 5: EnhancedAgentLoop 接入 Reducer + Hook 拆分
- 2633949 commit 6: 验证清理 + 文档落位

## 范围
- EnhancedAgentLoop 切换到新 StreamEvent + TurnReducer 管道
- 5 个并行 mutable 变量(assistantContent / streamingToolCalls / hasToolCalls / finishReason / responseUsage)统一迁入 TurnState
- 5 个 TurnLifecycleHook 入口落地:
  - `ToolConfirmationHook` (工具确认)
  - `SubAgentDispatchHook` (子 Agent 派发)
  - `ContextCompressionHook` (上下文压缩)
  - `ModeSuggestionHook` (ChatMode 建议)
  - `SessionMigrationHook` (Session 迁移)
- 测试 mock gateway 加 chatStream override + StreamChunk→StreamEvent 转换 helper
- 跨 chunk tool call id 关联用 `STREAM_TEST_TOOL_IDS` map 跟踪
- 4 份主设计文档 + 4 份 future-tasks + fixtures README 落位
- 01 草稿标记为 ARCHIVED

## 设计文档
- [02 文档](../../../docs/refactor/StreamChunk中转层重构-2026-06-16-02.md) §2.5 (Reducer) + §2.6 (Hook)
- [EXECUTION](../../../docs/refactor/StreamChunk中转层重构-2026-06-16-02-EXECUTION.md) §3.6 + §3.7 + §3.8

## 验收
- [x] 1384 tests PASS(全测试套件 100%)
- [x] 5+13 EnhancedAgentLoop/*Test 全部 PASS
- [x] 5 个 Hook 类就位
- [x] 文档全部落位(02 + EXECUTION + 测试清单 + 协议调研 + 4 future-tasks + fixtures README)
- [x] §3.8 启动检查清单全部 ✅
