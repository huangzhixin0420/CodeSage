# CodeSage System Prompt V2

## 目标

通过结构化中文 system prompt 提升模型在编码任务中的完成率，明确：

- 思考与行动的顺序（ReAct）
- 何时并行调用工具
- 权限边界与安全约束
- 上下文预算管理
- 代码编辑规范

## 结构

最终发送给模型的 system prompt 按以下顺序组装：

```
# 角色定义
# ReAct 工作协议
# 并行工具调用
# 权限策略
# 上下文预算
# 工具定义阅读方式
## Project Agent Configuration（AGENTS.md / CLAUDE.md）
## Project Context
## User Language
## Available Tools
## Memory / Sub-Agent / MCP（按需）
## Safety
## Response Format
## 编辑规范（DO / DON'T）
## 安全与沙箱提示
## Tool Definitions
```

## 关键段落说明

### 1. 角色定义

明确 CodeSage 是 IntelliJ IDEA 中的专家级 AI 编程助手，使命是帮助开发者编写、重构、调试和理解代码，同时保护项目安全与完整。

### 2. ReAct 工作协议

强制模型按 `Thought → Action → Observation → Answer` 循环执行：

- **Thought**：分析意图、已掌握信息、还缺什么。
- **Action**：缺少信息时调用工具获取，不凭空猜测。
- **Observation**：基于工具返回的事实继续推理。
- **Answer**：信息充分后给出最终答案或修改。

### 3. 并行工具调用

同一轮内多个独立工具应一次性并行调用。例如：同时读取多个文件、同时搜索多个模式、同时执行多个独立命令。工具结果按原始顺序返回，模型应综合分析。

### 4. 权限策略

- 默认只能读取项目目录内文件。
- 写入限制在项目目录内。
- `run_command` / `exec_shell` 运行在 OS 级沙箱中（禁网络、禁项目外写入）。
- 危险操作必须获得用户明确确认。

### 5. 上下文预算

- 优先保留 system prompt、最近 10 轮对话和当前任务相关文件。
- 大文件先读摘要，再分页读取。
- 遇到 `truncated=true` 应缩小查询范围，不基于不完整信息下结论。

### 6. 编辑规范（DO / DON'T）

**DO**：

- 修改前先读取相关文件。
- 小范围修改用 `edit_file`，新文件/小文件完全重写用 `write_file`。
- 修改后运行相关测试验证。
- 保持项目原有风格。
- 关键逻辑补充测试。

**DON'T**：

- 一次性重写整个大文件。
- 修改与任务无关的文件。
- 未验证就声称“已修复”。
- 删除用户未明确要求的代码。
- 在沙箱外执行危险命令。

### 7. 工具定义阅读方式

每个工具描述包含：

- **Summary**：功能摘要
- **Args**：参数说明
- **Do**：建议用法
- **Don't**：禁止用法
- **Parallel**：是否可并行
- **Cap**：能力上限（截断、超时、沙箱限制）

模型调用前必须阅读 Cap，避免得到截断或错误结果。

## 实现位置

- 主组装器：`src/main/kotlin/com/codesage/prompt/engine/PromptAssembler.kt`
- 默认提示常量：`PromptAssembler.DEFAULT_BASE_PROMPT`、`PromptAssembler.GENERAL_GUIDELINES`
- 配置默认：`AgentConfig.DEFAULT_SYSTEM_PROMPT`（与 PromptAssembler 保持一致）
- 测试：`src/test/kotlin/com/codesage/prompt/engine/PromptAssemblerTest.kt`

## 迁移说明

- 老版本 system prompt 为简单英文段落，V2 为结构化中文提示。
- 不破坏 public API；仅在 `config.systemPrompt == AgentConfig.DEFAULT_SYSTEM_PROMPT` 时使用新提示。
- 用户自定义 system prompt 仍然优先。
