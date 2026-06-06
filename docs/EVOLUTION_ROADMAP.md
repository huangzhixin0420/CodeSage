# CodeSage 演进路线图（2026-2027）

> 本文档将高价值演进方向细化为可执行的技术方案，供后续迭代参考。

---

## 总览

| 方向 | 代号 | 优先级 | 预估工作量 | 依赖 |
|------|------|--------|-----------|------|
| 模型智能路由 | ROUTER | P0 | 2周 | 无 |
| Inline Chat / Editor集成 | INLINE | P0 | 3周 | 无 |
| RAG项目知识库 | RAG | P0 | 3周 | 无 |
| 自主Agent模式 | AUTO | P1 | 3周 | INLINE（推荐） |
| 本地模型支持 | LOCAL | P1 | 1周 | 无 |
| 安全与合规 | SECURITY | P1 | 2周 | 无 |
| 多模态 | MULTIMODAL | P2 | 3周 | 无 |
| 实时代码补全 | INLINE_COMPLETION | P2 | 4周 | 大量IDE API |
| 测试生成与验证 | TESTGEN | P2 | 2周 | AUTO（推荐） |
| 团队知识共享 | TEAM | P2 | 2周 | 无 |
| 语音交互 | VOICE | P2 | 2周 | macOS Speech |
| CI/CD集成 | CICD | P2 | 2周 | 无 |

---

## 方向11：模型智能路由（Model Router）

### 目标
根据任务复杂度、模型能力、成本和可用性，自动选择最优模型执行请求，实现降本增效和故障自动切换。

### 现有架构融合点
- `ModelGateway`：统一的模型调用入口
- `ModelRegistry`：模型注册和适配器管理
- `PluginConfig`：多提供商配置已支持
- `AgentErrorRecovery`：错误恢复和fallback机制

### 详细设计

#### 1. 路由策略引擎
```kotlin
interface RoutingStrategy {
    fun selectModel(request: RoutingRequest): RoutingDecision
}

class SmartRouter(
    private val costAware: Boolean = true,
    private val latencyAware: Boolean = true,
    private val qualityAware: Boolean = true
) : RoutingStrategy
```

**路由维度**：
- **任务复杂度**：基于消息长度、工具调用数量、历史轮次评估
- **模型能力**：function calling、vision、long context、reasoning
- **成本**：每1K token输入/输出价格
- **延迟**：RTT历史统计
- **可用性**：健康检查状态

#### 2. 模型能力标签体系
```kotlin
enum class ModelCapability {
    FUNCTION_CALLING, VISION, LONG_CONTEXT, REASONING, CODE, STREAMING
}

data class ModelProfile(
    val modelId: String,
    val provider: String,
    val capabilities: Set<ModelCapability>,
    val costPer1KInput: Double,
    val costPer1KOutput: Double,
    val maxContextLength: Int,
    val avgLatencyMs: Long
)
```

#### 3. 健康检查与熔断
- 每30秒ping各提供商端点
- 连续3次失败触发熔断，自动路由到备用提供商
- 熔断后每60秒尝试恢复

#### 4. 成本追踪
- 按session统计token消耗和费用
- 月度预算上限告警
- 成本明细导出

### 执行步骤

| 步骤 | 任务 | 文件 | 测试 |
|------|------|------|------|
| 1 | 定义ModelProfile和路由接口 | `model/router/RoutingStrategy.kt` | 单元测试路由决策逻辑 |
| 2 | 实现SmartRouter（基于规则） | `model/router/SmartRouter.kt` | 测试各维度评分 |
| 3 | 实现健康检查与熔断 | `model/router/HealthChecker.kt` | 模拟故障恢复测试 |
| 4 | 集成到ModelGateway | 修改`ModelGateway.kt` | 端到端路由测试 |
| 5 | 成本追踪面板（UI） | `ide/ui/components/router/CostPanel.kt` | UI测试 |
| 6 | 配置持久化 | 扩展`PluginConfig.kt` | 序列化测试 |

### 关键决策
1. **路由策略**：先做基于规则的（简单可预测），再考虑ML-based（需要数据积累）
2. **成本控制**：默认关闭成本限制，让用户自行开启

### 预期收益
- 成本降低30-50%（简单任务用轻量模型）
- 可用性提升（自动故障切换）
- 延迟优化（就近/快速模型优先）

---

## 方向12：Inline Chat / Editor深度集成

### 目标
在代码编辑器内提供AI交互能力，让用户无需离开编辑上下文即可获取AI辅助。

### 现有架构融合点
- `AgentCore.chatWithTools()`：已有对话能力
- `IDETools`：已有文件读写能力
- `ToolGuardrails`：已有安全控制
- `ChatPanel`：已有消息渲染

### 详细设计

#### 1. 内联聊天组件（InlineChatComponent）
```
[用户选中代码]
    ↓
[按下快捷键 / 右键菜单]
    ↓
[InlineChatBubble 出现在选区下方]
    ├─ 输入框
    ├─ 快捷操作：Explain / Refactor / Fix / Test
    └─ 发送按钮
    ↓
[AI回复以Diff形式展示]
    ↓
[Accept / Reject / Modify 按钮]
```

#### 2. 编辑器Action体系
| Action | 触发方式 | 功能 |
|--------|---------|------|
| Explain Code | 右键/Alt+Enter | 解释选中代码 |
| Generate Doc | 右键 | 为选中方法生成文档注释 |
| Refactor Selection | 右键/Cmd+Shift+R | 重构选中代码 |
| Fix Error | 灯泡提示 | 修复当前行错误 |
| Generate Test | 右键 | 为选中类/方法生成测试 |

#### 3. Diff预览与一键应用
- 使用IntelliJ `DiffViewer`组件展示变更
- 支持单文件内多个修改点的批量接受/拒绝
- 修改标记为`UndoableAction`，支持Ctrl+Z撤销

#### 4. 代码Lens集成
- 在方法/类上方显示AI生成的摘要（可选开启）
- 点击Lens展开Inline Chat

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | InlineChatBubble UI组件 | `ide/ui/components/inline/InlineChatBubble.kt` |
| 2 | 编辑器Action注册 | `ide/actions/InlineChatActions.kt` |
| 3 | Diff预览面板 | `ide/ui/components/inline/DiffPreviewPanel.kt` |
| 4 | 代码修改应用器 | `ide/editor/CodeModifier.kt` |
| 5 | 代码Lens提供器 | `ide/editor/CodeLensProvider.kt` |
| 6 | 快捷键配置 | `resources/META-INF/plugin.xml` |

### 关键决策
1. **Diff展示**：先用内联高亮（绿色/红色背景），复杂场景再用独立Diff窗口
2. **上下文范围**：Inline Chat默认只发送当前文件+选区，避免上下文过长

### 预期收益
- 用户留存率提升（降低上下文切换成本）
- 操作效率提升（Explain/Refactor触手可及）

---

## 方向13：RAG项目知识库

### 目标
构建项目级向量知识库，实现代码库问答和自动上下文检索，让AI"理解"整个项目。

### 现有架构融合点
- `SymbolIndex`：已有符号索引
- `SemanticSearch`：已有语义搜索框架
- `MemoryProvider`：可扩展为向量存储后端
- `ContextCompressor`：已有上下文管理

### 详细设计

#### 1. 文档分块与向量化
```kotlin
class DocumentChunker {
    fun chunk(file: VirtualFile): List<Chunk>  // 按类/方法/段落分块
}

class EmbeddingProvider {
    suspend fun embed(text: String): FloatArray  // 调用embedding API
}
```

**分块策略**：
- 代码文件：按类/方法级别分块，保留签名和文档注释
- 文档：按段落分块，重叠窗口保证连续性
- 配置文件：整体作为一个chunk

#### 2. 向量存储
```kotlin
interface VectorStore {
    fun add(chunks: List<ChunkWithEmbedding>)
    fun search(query: FloatArray, topK: Int): List<SearchResult>
    fun delete(filePath: String)  // 文件删除时同步
}

class HnswVectorStore : VectorStore  // 基于HNSW算法
class SqliteVectorStore : VectorStore  // 基于SQLite-VSS（无额外依赖）
```

**存储选型**：
- 第一阶段：SQLite + 自定义向量相似度（余弦距离，无额外依赖）
- 第二阶段：可选接入 Pinecone/Milvus/Chroma（云端）

#### 3. 混合检索
```
用户提问
    ↓
[关键词检索] ─┐
              ├→ [结果融合] → [重排序] → TopK chunks
[向量检索] ───┘
```

**融合公式**：`score = 0.4 * bm25_score + 0.6 * vector_score`

#### 4. 增量索引
- 文件保存时触发增量更新
- 使用IntelliJ `VirtualFileListener`监听文件变更
- 后台线程执行embedding，不阻塞IDE

#### 5. 自动上下文注入
在`AgentCore.chatWithTools()`中，提问前自动检索相关代码片段并注入系统提示。

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | 文档分块器 | `rag/DocumentChunker.kt` |
| 2 | Embedding接口 + 实现 | `rag/EmbeddingProvider.kt` |
| 3 | 向量存储（SQLite实现） | `rag/vector/SqliteVectorStore.kt` |
| 4 | 混合检索引擎 | `rag/HybridRetriever.kt` |
| 5 | 增量索引监听器 | `rag/IndexUpdater.kt` |
| 6 | 集成到AgentCore | 修改`AgentCore.kt` |
| 7 | 知识库管理UI | `ide/ui/components/rag/KnowledgeBasePanel.kt` |

### 关键决策
1. **Embedding模型**：默认使用提供商的embedding API（如OpenAI text-embedding-3-small），可选本地模型
2. **索引时机**：首次打开项目时后台全量索引，后续增量更新
3. **隐私**：代码片段本地存储，embedding可配置本地/云端

### 预期收益
- 代码库问答准确率大幅提升（从文本搜索的~40%到RAG的~80%）
- AI回复更具项目上下文感

---

## 方向14：自主Agent模式（Auto Mode）

### 目标
Agent能够自主规划、执行、验证多步任务，人类仅在关键决策点介入。

### 现有架构融合点
- ~~`KanbanOrchestrator`~~（2026-06 移除）：任务分解和调度
- `TaskPlanner`：已有任务规划
- `ToolGuardrails`：已有安全控制
- `ExecutionTracer`：已有执行追踪
- `AgentErrorRecovery`：已有错误恢复

### 详细设计

#### 1. 自主执行循环
```
用户输入任务
    ↓
[Planner] 分解为子任务序列
    ↓
for each 子任务:
    [Executor] 执行工具调用
    [Validator] 验证结果（编译/测试/检查）
    if 失败:
        [ErrorAnalyzer] 分析错误原因
        [RePlanner] 调整计划重试
    [Reporter] 汇报进度
    ↓
[Summarizer] 汇总最终结果
```

#### 2. 验证器体系
```kotlin
interface Validator {
    suspend fun validate(context: ValidationContext): ValidationResult
}

class CompileValidator : Validator      // 编译检查
class TestValidator : Validator         // 单元测试运行
class LintValidator : Validator         // 代码规范检查
class ReviewValidator : Validator       // AI自我审查
```

#### 3. 人类在环（Human-in-the-loop）
- **高置信度操作**（读文件、搜索）：直接执行
- **中置信度操作**（写文件、编辑）：记录日志，批量确认
- **低置信度操作**（删除、执行命令）：必须等待用户确认

**确认方式**：
- 侧边栏显示待确认操作列表
- 支持"全部接受"/"全部拒绝"/"逐条审查"

#### 4. 自我审查
Agent完成修改后，自动运行：
1. 编译验证
2. 静态分析（如有）
3. AI自我review（"检查是否有bug、遗漏、副作用"）

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | 验证器接口 + 编译验证 | `agent/auto/validator/Validator.kt` |
| 2 | 自主执行循环 | `agent/auto/AutonomousExecutor.kt` |
| 3 | 人类确认面板 | `ide/ui/components/auto/ApprovalPanel.kt` |
| 4 | 自我审查提示 | `agent/auto/SelfReview.kt` |
| 5 | 进度报告器 | `agent/auto/ProgressReporter.kt` |
| 6 | 集成到AgentCore | 新增`executeAutonomous()`方法 |

### 关键决策
1. **默认模式**：默认关闭Auto Mode，用户手动开启
2. **最大迭代**：设置上限（如20轮），防止无限循环
3. **回滚能力**：每次写操作前自动备份，支持一键回滚

### 预期收益
- 复杂任务自动化率提升（从手动多步到一键执行）
- 减少用户操作负担

---

## 方向15：本地模型支持

### 目标
支持Ollama、LM Studio、vLLM等本地模型部署方案，满足隐私敏感场景需求。

### 现有架构融合点
- `OpenAICompatibleAdapter`：已支持OpenAI兼容API格式
- `ProviderConfig`：已支持自定义base URL
- `PluginConfig`：已有提供商管理

### 详细设计

#### 1. 本地模型发现
```kotlin
class LocalModelDiscovery {
    fun discoverOllama(): List<String>  // 探测 http://localhost:11434
    fun discoverLMStudio(): List<String> // 探测 http://localhost:1234
}
```

#### 2. 一键配置模板
在Plugin Settings中新增：
- "添加本地模型"按钮
- 自动检测本地服务
- 预置模板：Ollama / LM Studio / vLLM

#### 3. 隐私模式切换
```kotlin
enum class PrivacyLevel {
    CLOUD_ONLY,      // 仅云端模型
    HYBRID,          // 本地优先，云端fallback
    LOCAL_ONLY       // 仅本地模型
}
```

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | 本地模型发现 | `model/local/LocalModelDiscovery.kt` |
| 2 | 配置模板扩展 | `shared/config/PluginConfig.kt` |
| 3 | 设置面板更新 | `ide/settings/PluginSettingsConfigurable.kt` |
| 4 | 隐私模式 | `model/gateway/PrivacyMode.kt` |

### 预期收益
- 满足企业安全合规要求
- 离线可用
- 零API成本

---

## 方向16：安全与合规

### 目标
在数据发送到LLM前检测和脱敏敏感信息，并提供完整审计追踪。

### 现有架构融合点
- `ToolGuardrails`：已有操作权限控制
- `StructuredLogger`：已有结构化日志
- `OutputTruncator`：已有输出处理

### 详细设计

#### 1. PII检测引擎
```kotlin
class PIIDetector {
    fun scan(text: String): List<PIIFinding>
}

// 检测模式
- API Key: 正则匹配 (sk-[a-zA-Z0-9]{48})
- 密码: 关键字+赋值模式
- 邮箱: 标准正则
- 手机号: 地区特定正则
- 身份证号: 地区特定正则
- 信用卡: Luhn算法验证
```

#### 2. 脱敏策略
```kotlin
interface SanitizationStrategy {
    fun sanitize(text: String, findings: List<PIIFinding>): String
}

class MaskStrategy : SanitizationStrategy      // 替换为 ***
class HashStrategy : SanitizationStrategy      // 替换为哈希值（可逆映射）
class RemoveStrategy : SanitizationStrategy    // 直接删除
```

#### 3. 审计追踪
- 每个工具调用记录：时间、用户、输入、输出、模型、耗时
- 敏感操作（写文件、执行命令）额外记录前后快照
- 审计日志不可删除（ append-only 文件）

#### 4. 合规报告
- 按时间范围导出审计日志
- 统计敏感操作频率
- 检测异常行为模式

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | PII检测引擎 | `security/PIIDetector.kt` |
| 2 | 脱敏策略 | `security/SanitizationStrategy.kt` |
| 3 | 请求拦截器 | `security/RequestInterceptor.kt` |
| 4 | 审计日志增强 | `observability/AuditLogger.kt` |
| 5 | 合规报告导出 | `security/ComplianceExporter.kt` |
| 6 | 安全设置面板 | `ide/ui/components/security/SecurityPanel.kt` |

### 预期收益
- 通过企业安全审计
- 防止敏感信息泄露到第三方LLM
- 满足GDPR/等保要求

---

## 方向17：多模态（Multimodal）

### 目标
支持图像理解能力，让AI能够分析截图、UI设计稿、图表等视觉内容。

### 详细设计

#### 1. 图像输入
- 支持粘贴/拖拽图片到聊天框
- 支持截图后直接发送（macOS截图API）
- 图片转为base64嵌入消息

#### 2. 图像理解模型
- 云端：GPT-4o / Claude-3.5-Sonnet / Gemini Pro Vision
- 本地：LLaVA（通过Ollama）

#### 3. 使用场景
- "这个报错截图什么意思？"
- "把这张UI设计稿转成Jetpack Compose代码"
- "解释这个架构图"

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | 图片消息类型 | `model/dto/Message.kt` 扩展 |
| 2 | 图片上传/显示UI | `ide/ui/components/chat/ImageMessage.kt` |
| 3 | 模型适配器扩展 | `model/adapter/*` 支持image_url |
| 4 | 截图快捷键 | `ide/actions/ScreenshotAction.kt` |

---

## 方向18：实时代码补全（Inline Completion）

### 目标
类似GitHub Copilot的实时代码补全，在用户输入时自动建议后续代码。

### 详细设计

#### 1. 触发策略
- 延迟触发：停止输入300ms后触发
- 防抖：连续输入不触发
- 取消：用户继续输入时取消 pending 请求

#### 2. 上下文采集
- 当前文件内容（光标前200行 + 后50行）
- 最近打开的相关文件
- 项目类型和依赖信息

#### 3. 展示方式
- 使用IntelliJ `InlineCompletionRenderer`
- 灰色幽灵文本展示建议
- Tab接受 / Esc拒绝

#### 4. 模型选择
- 专用代码补全模型（如 Codex / CodeLlama）
- 与chat模型分离，降低延迟

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | Inline Completion Provider | `ide/editor/InlineCompletionProvider.kt` |
| 2 | 上下文采集器 | `ide/editor/CompletionContextCollector.kt` |
| 3 | 幽灵文本渲染 | `ide/editor/GhostTextRenderer.kt` |
| 4 | 快捷键绑定 | `resources/META-INF/plugin.xml` |

### 风险评估
- **高**：IntelliJ Inline Completion API 较新且变化快
- **高**：需要大量调优才能达到可用精度

---

## 方向19：测试生成与验证

### 目标
为选中代码自动生成单元测试，自动运行并修复直到通过。

### 现有架构融合点
- `IDETools.runCommand`：可执行测试命令
- `Auto Mode`：自主执行-验证循环
- `PromptPresets`：可添加TEST_WRITER角色

### 详细设计

#### 1. 测试生成流程
```
选中类/方法
    ↓
[分析被测代码] → 提取输入输出模式、边界条件
    ↓
[生成测试代码]
    ↓
[写入测试文件]
    ↓
[运行测试]
    ↓
if 失败:
    [分析失败原因]
    [修复测试或被测代码]
    [重试]
```

#### 2. 测试框架检测
自动检测项目使用的测试框架：
- JVM: JUnit 4/5, TestNG, Spock, Kotest
- JS: Jest, Mocha, Vitest
- Python: pytest, unittest
- Go: testing

#### 3. 覆盖率反馈
- 运行测试后收集覆盖率报告
- 识别未覆盖的分支
- 建议补充测试用例

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | 测试框架检测 | `testgen/FrameworkDetector.kt` |
| 2 | 测试生成器 | `testgen/TestGenerator.kt` |
| 3 | 测试运行器 | `testgen/TestRunner.kt` |
| 4 | 覆盖率解析 | `testgen/CoverageParser.kt` |
| 5 | 修复循环 | `testgen/FixIterateLoop.kt` |

---

## 方向20：团队知识共享

### 目标
支持团队级共享Prompt模板、Skill和对话历史。

### 详细设计

#### 1. 共享内容
- **Prompt模板**：团队统一编码规范提示
- **Skills**：团队定制的常用操作（如"部署到 staging"）
- **知识库**：团队技术文档、API文档索引

#### 2. 同步机制
- Git仓库同步：`.codesage/shared/` 目录
- 云同步（可选）：团队服务器
- 版本控制：Skill和Prompt的版本管理

#### 3. 权限管理
- 读取：所有团队成员
- 写入：团队管理员
- 审核：变更需要PR/审批

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | 共享配置解析 | `team/TeamConfig.kt` |
| 2 | Git同步器 | `team/GitSyncManager.kt` |
| 3 | 团队Skill加载 | `skill/discovery/TeamSkillLoader.kt` |
| 4 | 共享Prompt加载 | `prompt/TeamPromptLoader.kt` |

---

## 方向21：语音交互

### 目标
支持语音输入和语音播报，提升交互效率。

### 详细设计

#### 1. 语音输入
- macOS `SFSpeechRecognizer` API
- 实时转文字，支持中文/英文
- 快捷键触发（如按住Option说话）

#### 2. 语音输出
- macOS `AVSpeechSynthesizer`
- 仅播报AI回复（可配置开关）

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | 语音识别封装 | `voice/SpeechRecognizer.kt` |
| 2 | 语音合成封装 | `voice/SpeechSynthesizer.kt` |
| 3 | UI集成 | `ide/ui/components/chat/VoiceButton.kt` |

### 风险评估
- **中**：macOS私有API，Windows/Linux需要不同实现
- **低**：对核心功能无侵入

---

## 方向22：CI/CD集成

### 目标
分析CI/CD构建失败日志，自动定位问题并建议修复。

### 详细设计

#### 1. 日志获取
- 读取本地构建日志（Gradle/Maven/npm）
- 可选：连接CI平台API（GitHub Actions / GitLab CI / Jenkins）

#### 2. 错误分析
- 提取堆栈跟踪
- 定位到具体文件和行号
- 关联最近修改（git blame）

#### 3. 修复建议
- 根据错误类型匹配修复模式
- 生成代码修改建议
- 一键应用修复

### 执行步骤

| 步骤 | 任务 | 文件 |
|------|------|------|
| 1 | 构建日志解析 | `cicd/BuildLogParser.kt` |
| 2 | 错误分类器 | `cicd/ErrorClassifier.kt` |
| 3 | CI平台连接器 | `cicd/CIPlatformConnector.kt` |
| 4 | 修复建议生成 | `cicd/FixSuggestionGenerator.kt` |

---

## 附录：推荐执行顺序

### 第一波（1-2个月）：核心体验
1. **方向12 Inline Chat** — 用户感知最强，直接提升日活
2. **方向11 Model Router** — 降低成本，提升稳定性
3. **方向15 Local Model** — 低工作量高价值，满足安全需求

### 第二波（2-3个月）：智能化
4. **方向13 RAG知识库** — 大幅提升AI回答质量
5. **方向14 Auto Mode** — 自动化复杂任务
6. **方向16 安全合规** — 企业客户准入门槛

### 第三波（3-6个月）：差异化
7. **方向19 TestGen** — 开发者刚需
8. **方向17 Multimodal** — 差异化亮点
9. **方向20 Team Sharing** — B端增值
10. **方向18 Inline Completion** — 长期投入，对标Copilot

### 可选探索
- **方向21 Voice** — 锦上添花
- **方向22 CI/CD** — 垂直场景
