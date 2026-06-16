# PR-2: Gateway 切到 StreamEvent + Reducer 实现

## 包含 Commits
- 31bccdd commit 3: Gateway 切到 StreamEvent
- 94cea07 commit 4: L2 Reducer 纯函数实现

## 范围
- `ModelGateway.chatStream()` 返回 `Flow<StreamEvent>`(新签名)
- `chatStreamLegacy()` 保留为 `Flow<StreamChunk>`(旧 API,委托给新签名)
- `parseStreamChunk(line: String)` 改为 `List<StreamEvent>`(一行 SSE 可产生多个事件)
- 新建 L2 Reducer 状态机:
  - `TurnState` 累积状态数据(assistant text / tool calls / code blocks / plan steps)
  - `TurnReducer` 纯函数 reducer(12+ case 穷举)
  - 派生状态兜底:Flow.Finished 时若 reasoning round 还在 started → 补 RoundEnd
  - 派生状态兜底:Flow.Finished 时若有 open code block → 补 CodeBlockEnd
- 协议层 choiceIndex=0 强制;多候选 Map<choiceIndex, TurnState> 留 future-tasks

## 设计文档
- [02 文档](../../../docs/refactor/StreamChunk中转层重构-2026-06-16-02.md) §2.4 (Gateway) + §2.5 (Reducer)
- [EXECUTION](../../../docs/refactor/StreamChunk中转层重构-2026-06-16-02-EXECUTION.md) §3.4 + §3.5

## 验收
- [x] chatStream: Flow<StreamEvent> 签名落地
- [x] done 兜底保留
- [x] TurnReducerTest 16 个 case 覆盖 100% PASS
