# CodeSage AGENTS.md / CLAUDE.md 支持

## 设计目标

让项目级配置能够影响 CodeSage 的行为，例如：

- 指定项目使用的编码规范、框架版本、测试命令。
- 定义必须遵守的安全或流程约束。
- 提供项目背景信息（架构决策、重要文件位置）。

## 发现顺序

CodeSage 按以下顺序查找项目级配置，命中第一个即停止：

1. `{projectRoot}/AGENTS.md`
2. `{projectRoot}/CLAUDE.md`
3. `~/.codesage/AGENTS.md`

`CLAUDE.md` 是为了兼容 Anthropic Claude Code 生态的命名习惯。项目级配置优先于用户级配置（`~/.codesage/AGENTS.md`）。

## 注入位置

找到的配置内容会作为 `## Project Agent Configuration` 段注入 system prompt，位于角色定义之后、通用指导之前。该段：

- 不参与上下文压缩，始终保留在对话顶部。
- 不会被 AGENTS.md 自身覆盖或重复注入。

## 格式建议

```markdown
# Project Instructions

## 项目背景
- 项目类型：Kotlin/JVM IntelliJ 插件
- 构建工具：Gradle
- 主要模块：agent、model、ide、prompt

## 编码规范
- 使用 Kotlin 协程处理异步逻辑
- 所有 public API 必须有 KDoc
- 优先使用不可变数据类

## 测试要求
- 每次修改后运行 `./gradlew test`
- 新增功能必须附带单元测试

## 受限操作
- 不要自动提交代码
- 不要修改 .github/workflows 除非用户明确要求
```

## 与 system prompt 的关系

AGENTS.md 提供**项目特定**约束，而 `PromptAssembler` 提供**通用**约束（ReAct、权限策略、上下文预算等）。两者互补：

```
system prompt = 角色定义 + AGENTS.md + 项目上下文 + 通用指导 + 工具定义
```

## 实现位置

- 加载逻辑：`src/main/kotlin/com/codesage/prompt/engine/PromptAssembler.kt` 中的 `loadAgentsMd()`
- 测试：`src/test/kotlin/com/codesage/prompt/engine/PromptAssemblerTest.kt`
