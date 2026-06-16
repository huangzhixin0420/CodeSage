# PlanStep 跨协议适配（未来任务）

- **创建日期**: 2026-06-17
- **状态**: 待启动（依赖 StreamChunk 中转层重构完成且稳定运行 ≥ 1 个月）
- **调研依据**: [docs/research/PlanStep-多候选-多选项-协议调研-2026-06-17-01.md](../../research/PlanStep-多候选-多选项-协议调研-2026-06-17-01.md)
- **触发来源**: StreamChunk 重构（[02 文档](../StreamChunk中转层重构-2026-06-16-02.md)）设计阶段识别
- **影响范围**: Anthropic / OpenAI / Gemini Normalizer + system prompt 引导文本

---

## 1. 目标

让 CodeSage 三大主流 provider 都能在响应中输出**结构化计划步骤**，下游 UI 渲染为"计划卡"，区别于普通文本/推理。

## 2. 协议支持现状

| Provider | 协议层 | 实际产出方式 | CodeSage 现状 |
|----------|--------|------------|--------------|
| Anthropic 4.x | ✅ `content_block.type=plan` (Beta) | 协议层直接 | ❌ 丢弃（只识别 text/tool_use） |
| OpenAI o-series | ❌ 无原生 | system prompt + `<plan>` 标签 | ❌ 未实现 |
| Gemini 2.5 | ❌ 无原生 | system prompt + `<plan>` 标签 | ❌ 未实现 |
| DeepSeek-R1 | ❌ 无原生 | reasoning 即可 | - |

## 3. 实现范围

1. **Anthropic**: Beta header 启用 + `content_block_start(type=plan)` / `content_block_delta(type=plan_delta)` / `content_block_stop` 三态识别
2. **OpenAI / Gemini**: 新增 `PlanStepSplitter`（类似 `FencedCodeSplitter` 287 行）识别 `<plan>...</plan>` XML 标签
3. **System prompt 引导文本**: 强制 LLM 按格式输出
4. **Reducer**: 启用 `Content.PlanStep` when 分支（02 文档已预留占位 case）
5. **UI 协议**: `AgentStreamEvent.PlanStep` 事件 + 前端"计划卡"渲染

## 4. 工作量

~2-3d

## 5. 前置条件

- [ ] StreamChunk 重构（[02 文档](../StreamChunk中转层重构-2026-06-16-02.md)）稳定运行 ≥ 1 个月
- [ ] Anthropic 4.x GA 状态确认（截止 2026-06 仍为 Beta）
- [ ] 实测 OpenAI o-series / Gemini 2.5 在 system prompt 引导下的格式遵循率
