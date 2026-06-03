# Settings 迁移指南

> 从旧的 `CodeSagePlugin.xml` 配置迁移到新的 `~/codesage/settings.json`。

---

## 为什么迁移

**旧方案的痛点**:
- 配置藏在 IDE 内部,无法直接编辑 / 备份 / 跨机器同步
- 三个分散 Tab(Providers & General / Budget & Rounds / Model Selector)
- API Key 走 PasswordSafe,无法 export
- IDE 升级 / 重装会丢配置

**新方案**:
- 配置存在 `~/codesage/settings.json`(人类可读、JSON5 兼容、git 友好)
- WebView 内 7 大分组统一管理
- API Key 仍走 PasswordSafe(安全),`settings.json` 只存 `keychain:<id>` 引用
- 手动编辑 IDE 菜单 / 改完 IDE 自动 reload

---

## 自动迁移(已实现)

CodeSage 启动时会自动检测旧配置:

1. 检查 `~/codesage/settings.json` 是否存在
2. 如果不存在 → 创建默认文件
3. 如果存在 → 直接用

**手动触发迁移** (旧用户):
1. 打开 IDE → Tools → CodeSage → 打开设置文件夹
2. 如果有 `~/codesage/`,但 `.codesage/` 是空的
3. 旧配置已自动保留在 IDE 状态中,新文件用默认 Provider
4. 在 WebView 设置 → Models 重新添加 Provider
5. 或:从 IDE 菜单 Tools → CodeSage → 打开 IDE 设置 → 把 Provider 配好

---

## 字段映射表

| 旧 (`CodeSagePlugin.xml`) | 新 (`settings.json`) | 备注 |
|---|---|---|
| `<providers>` 列表 | `providers[]` | 完整迁移,`apiKeyRef = "keychain:<id>"` |
| `<defaultProviderId>` | `defaults.providerId` | 直接 |
| `<defaultModel>` | `defaults.model` | 直接 |
| `<codingModel>` | `defaults.codingModel` | 直接 |
| `<reasoningModel>` | `defaults.reasoningModel` | 直接 |
| `<maxIterationsPerTask>` | `agent.maxIterations` | 直接 |
| `<maxDurationSecondsPerTask>` | `agent.maxDurationSeconds` | 直接 |
| `<maxTokensPerTask>` | `agent.maxTokens` | 直接 |
| `<budgetWarningThreshold>` | `agent.budgetWarningThreshold` | 直接 |
| `<subAgentBudgetRatio>` | `agent.subAgentBudgetRatio` | 直接 |
| `<allowContinueOnExhaustion>` | `agent.allowContinueOnExhaustion` | 直接 |
| `<enableStreaming>` | `agent.enableStreaming` | 直接 |
| `<maxContextMessages>` | `agent.maxContextMessages` | 直接 |
| `<truncationStrategy>` | `agent.truncationStrategy` | 直接 |
| `<promptRole>` | `agent.promptRole` | 直接 |
| `<mcpServers>` | `mcp.servers` | 直接 |
| PasswordSafe `provider:<id>:apikey` | PasswordSafe `provider:<id>:apikey` | **不变**,仍走 PasswordSafe |
| (无) | `ui.theme` | 新增:auto / light / dark |
| (无) | `ui.showThinking` | 新增 |
| (无) | `shortcuts.*` | 新增 |
| (无) | `advanced.logLevel` | 新增 |
| (无) | `advanced.customCss` | 新增 |

---

## 手动编辑示例

### 添加新 Provider

```json
{
  "providers": [
    {
      "id": "my-openai",
      "name": "My OpenAI",
      "type": "openai",
      "baseUrl": "https://api.openai.com",
      "enabled": true,
      "apiKeyRef": "keychain:my-openai",
      "models": [
        {
          "id": "gpt-4o",
          "label": "GPT-4o",
          "contextSize": 128000,
          "supportsTools": true,
          "isDefault": true
        }
      ]
    }
  ],
  "defaults": {
    "providerId": "my-openai",
    "model": "gpt-4o"
  }
}
```

### 修改预算

```json
{
  "agent": {
    "maxIterations": 50,
    "maxDurationSeconds": 900,
    "budgetWarningThreshold": 80
  }
}
```

### 切换主题

```json
{
  "ui": {
    "theme": "dark"
  }
}
```

---

## 故障排除

### 设置改了不生效?

- 重载:Tools → CodeSage → 重载设置
- 或 Cmd/Ctrl + R 重载整个 WebView
- 或改 `index.html` 里的 `body[data-theme-pref]`

### 配置文件损坏?

CodeSage 检测到损坏 JSON 会:
1. 自动备份:`settings.json.bak.<timestamp>`
2. 回退到默认配置
3. 通过 Tools → CodeSage → 重载设置 触发检测

### API Key 怎么改?

API Key 仍走 IntelliJ PasswordSafe,不在 settings.json 里。
**当前 UI 不能改 Key**(待 Phase 4 后续补),改用:
1. 旧 IDE 设置(Tools → CodeSage → Providers & General)仍可访问
2. 或:卸载 Provider 重新添加(下次启动会引导)

### 跨机器同步

```bash
# 在机器 A
scp ~/.codesage/settings.json user@machine-b:.codesage/

# 机器 B
# PasswordSafe 仍需单独配 API Key(每个 IDE 独立)
```

API Key 是 IDE 级别的,不会跨机器同步(安全考虑)。Provider 配置和默认模型可以同步。
