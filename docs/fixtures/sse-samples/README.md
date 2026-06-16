# 真实 SSE 数据样例（重构验证用）

- **创建日期**: 2026-06-17
- **配套文档**: [02 文档](../../refactor/StreamChunk中转层重构-2026-06-16-02.md) / [EXECUTION.md](../../refactor/StreamChunk中转层重构-2026-06-16-02-EXECUTION.md)
- **用途**: StreamChunk 重构验证用真实 LLM SSE 数据（避免开发自己造数据）

---

## 1. 文件清单

| 文件 | 来源 | 大小 | 用途 |
|------|------|------|------|
| `openai-gpt4-basic.sse` | OpenAI GPT-4 普通聊天 | ~2KB | OpenAI 协议层基础验证 |
| `openai-o3-reasoning.sse` | OpenAI o3 推理链 | ~3KB | reasoning_content 提取 |
| `openai-gpt4-tool-calls.sse` | OpenAI GPT-4 工具调用 | ~4KB | tool_calls 累积 + arguments 解析 |
| `openai-gpt4-codeblock.sse` | OpenAI GPT-4 代码块生成 | ~5KB | FencedCodeSplitter 迁移验证 |
| `anthropic-claude4-text.sse` | Anthropic Claude 4 普通聊天 | ~2KB | content_block_* 三态 |
| `anthropic-claude4-thinking.sse` | Anthropic Claude 4 推理链 | ~3KB | thinking_delta 提取 |
| `anthropic-claude4-tool-use.sse` | Anthropic Claude 4 工具调用 | ~4KB | input_json_delta 累积到 stop |
| `gemini-2.5-text.sse` | Google Gemini 2.5 Pro 普通聊天 | ~2KB | candidates 数组 |
| `gemini-2.5-done-without-sentinel.sse` | Gemini 2.5 (无 [DONE] sentinel) | ~1KB | done 兜底逻辑验证 |
| `minimax-m3-no-done.sse` | MiniMax-M3 (无 [DONE] sentinel) | ~1KB | done 兜底逻辑验证 |

---

## 2. 数据采集方式

**每份 SSE 样例的来源**:
1. 从 CodeSage 生产环境匿名化采样
2. 用 curl 调 provider 官方 API 录制
3. 从 provider 官方文档的 streaming example 复制

**采集步骤**:
```bash
# OpenAI GPT-4 普通聊天
curl -N https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4",
    "stream": true,
    "messages": [{"role": "user", "content": "Hello"}]
  }' > openai-gpt4-basic.sse

# Anthropic Claude 4
curl -N https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d '{
    "model": "claude-4-sonnet-20250514",
    "stream": true,
    "max_tokens": 1024,
    "messages": [{"role": "user", "content": "Hello"}]
  }' > anthropic-claude4-text.sse

# Gemini
curl -N "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:streamGenerateContent?alt=sse&key=$GEMINI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"contents":[{"parts":[{"text":"Hello"}]}]}' > gemini-2.5-text.sse
```

---

## 3. 验证方式

每个 SSE 样例可作为以下测试的输入：

1. **Adapter 单测** (`*NormalizerTest`): 逐行 `normalize()` 后断言 `List<StreamEvent>`
2. **Gateway 集成测试**: 喂入完整 SSE 串，断言 emit 出的 `Flow<StreamEvent>` 序列
3. **真实对话验证** (EXECUTION.md §3.8 必跑): 用 CodeSage UI 跑真实 prompt 触发，看事件流

---

## 4. 当前状态

⚠️ **本目录当前为空**——真实 SSE 数据采集涉及 API key 配置和数据脱敏，**建议在 PR-1 启动前由测试工程师负责采集**。

**预计工作量**: 0.5d（采集 10 份样例 + 脱敏）

**前置条件**:
- [ ] 至少 4 个 provider 的 API key 可用
- [ ] 隐私脱敏流程（去掉任何用户真实对话内容）
- [ ] 文件 commit 到仓库（不要 commit 任何 API key）
