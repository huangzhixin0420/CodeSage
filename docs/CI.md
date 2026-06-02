# CodeSage CI Pipeline

本目录配置了 CodeSage 的 GitHub Actions CI 流水线（`.github/workflows/ci.yml`）。

## 触发条件

- **push** 到 `main` 或 `develop`
- **pull_request** 到 `main` 或 `develop`

## 阶段

### 1. Build & Test（每个 push/PR）

- Ubuntu latest
- JDK 17（Temurin）
- Gradle Wrapper
- 步骤：
  1. 编译主代码（`./gradlew compileKotlin`）
  2. 编译测试代码（`./gradlew compileTestKotlin`）
  3. 运行所有单元测试（`./gradlew test`）
  4. 上传 test report 到 artifact（即使失败也会上传）

### 2. Build Plugin（仅 main 分支）

依赖阶段 1 通过。

- 步骤：构建插件 zip（`./gradlew buildPlugin`）
- 产物：上传到 artifact `codesage-plugin`

## 本地运行等价命令

```bash
# 编译
./gradlew compileKotlin

# 编译 + 单元测试
./gradlew test

# 完整构建
./gradlew buildPlugin
```

## 注意事项

- IntelliJ 测试 sandbox 在 headless 环境下依赖 `buildSearchableOptions` 任务。
  本项目已在 `build.gradle.kts` 显式禁用该任务（参考 `intellijPlatform` 已知问题 IDEA-332952）。
- 网络访问：在 CI 沙箱内，Maven Central / JetBrains 仓库访问可能受限。
  失败时检查是否使用了公司代理或私有 mirror。

## 测试覆盖范围

| 阶段 | 测试类数 | 关键覆盖 |
|------|---------|----------|
| T0 基础设施 | ~6 | 并发竞态、资源泄漏、retry 计数 |
| T1 模型层 | ~4 | Anthropic / Gemini / SmartRouter / Capabilities |
| T1.5 ChatMode | 1 | keyword 路由 + 用户显式优先 |
| T2 MCP | ~3 | WebSocket / Health / Marketplace |
| T4 多 Agent | 3 | Bus / Scratchpad / RoleSelector |
| T5 Code Insight | 2 | CyclomaticComplexity / LocalCodeReviewer |
| T7 可观测性 | 1 | EventHistory ring buffer |
| 工具 | ~6 | 工具系统、guardrails、IDE 文件 |
| 分析 | ~4 | PSI / Symbol / 元素分类 |
| 其它 | ~20+ | 各模块 |

## 添加新测试

1. 在 `src/test/kotlin/com/codesage/xxx/` 下创建 `XxxTest.kt`
2. 用 JUnit 5：`@Test` + `org.junit.jupiter.api.Assertions.*`
3. 对耗时测试加 `@Timeout(value = N, unit = TimeUnit.SECONDS)`
4. 对并发测试用 `kotlinx.coroutines.runBlocking` + `withTimeout`
