# PR-1: 引入 StreamEvent 契约 + 3 个 Normalizer

## 包含 Commits
- 89aeb5f commit 1: 契约基础(分形 sealed tree + FinishReason)
- 2e49d44 commit 2: L1 Normalizer(3 normalizer + 25+ test)

## 范围
- 引入分形 sealed tree `StreamEvent` (Content / ToolCall / CodeBlock / Citation / Media / Flow) 替代 8 字段 union bag `StreamChunk`
- 新建 `FinishReason` 枚举(协议层归一,5 个 case)
- 新建 `StreamEventNormalizer` 抽象类 + 3 个 provider 实现:
  - `OpenAIStreamNormalizer` (含 FencedCodeSplitter 迁移)
  - `AnthropicStreamNormalizer`
  - `GeminiStreamNormalizer`
- `StreamChunk` / `StreamToolCallDelta` / `CodeBlockEvent` 标 `@Deprecated` 保留兼容层

## 设计文档
- [02 文档](../../../docs/refactor/StreamChunk中转层重构-2026-06-16-02.md) §2.3 (契约) + §2.4 (Normalizer)
- [EXECUTION](../../../docs/refactor/StreamChunk中转层重构-2026-06-16-02-EXECUTION.md) §3.2 + §3.3

## 验收
- [x] 25+ adapter 测试 PASS
- [x] 新增 3 个 Normalizer 单元测试 PASS
- [x] 旧 StreamChunk 测试继续工作(兼容层)
- [x] 编译零错误,仅 deprecation warning
