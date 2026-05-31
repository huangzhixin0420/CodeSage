# 目标 D：前端 JCEF 离线化（CDN 依赖消除）

## 角色定义

你是一位**资深 Kotlin 工程专家 + 前端工程专家**。你需要深入阅读 CodeSage 项目现有代码，识别 `chat.html` 的所有外部 CDN 依赖，设计离线化方案，下载/打包静态资源，修改 HTML 与 Kotlin 代码，确保插件在无网络环境下仍能完整渲染聊天界面。

## 项目背景

**CodeSage** 是一款基于 IntelliJ Platform 的 AI Agent IDE 插件（Kotlin 语言）。前端使用 JCEF（Java Chromium Embedded Framework）嵌入 `chat.html` 提供现代化聊天界面。`JCEFChatPanel` 通过 `loadHTML()` 将 HTML 内容以内联方式加载到浏览器中。

## 当前代码状态与 Gap 分析

### `chat.html` 的 CDN 依赖现状（`src/main/resources/webui/chat.html`）

当前 `chat.html` 的 `<head>` 中引用了 **20+ 个外部 CDN**：

- **TailwindCSS JIT**：`https://cdn.tailwindcss.com`（运行时 JIT 编译器，无法简单下载）
- **Highlight.js**：核心库 + 15 个语言包（kotlin, java, python, javascript, typescript, go, rust, xml, css, json, yaml, bash, sql）
- **Marked.js**：`https://cdnjs.cloudflare.com/ajax/libs/marked/9.1.6/marked.min.js`
- **Font Awesome**：`https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css`
- **Highlight.js 主题**：`github-dark.min.css` / `github.min.css`（通过 JS 动态切换）

### `JCEFChatPanel` 加载机制

`JCEFChatPanel.initializeBrowser()`（`src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt`）：
- 通过 `javaClass.classLoader.getResourceAsStream("webui/chat.html")` 读取 HTML 内容为字符串
- 调用 `newBrowser.loadHTML(htmlContent, "http://codesage.local/chat.html")` 加载
- JS Bridge 在 `onLoadingStateChange`（`!isLoading`）时注入
- **关键问题**：由于使用 `loadHTML()` 内联加载，HTML 中所有相对路径（如 `./lib/xxx.js`）默认无法解析到 `resources` 目录，需要通过 `loadURL()` 或 `cefBrowser.loadResource()` 加载本地文件，或在 HTML 中内联所有资源。

### 当前 Gap

1. **完全依赖 CDN**：用户离线或网络受限时，JCEF 页面无法渲染，插件完全不可用。
2. **`loadHTML()` 的相对路径问题**：即使下载了静态资源到 `resources/webui/lib/`，`loadHTML()` 加载的虚拟 URL 无法解析相对路径。
3. **TailwindCSS CDN 是 JIT 编译器**：无法像普通 JS 库一样下载 `.min.js` 就能离线使用，需要预编译或替换方案。
4. **主题动态切换**：`toggleTheme()` 和 `setTheme()` 函数通过 JS 修改 `hljs-theme` 的 `href` 为 CDN URL，离线环境下会 404。
5. **无离线检测/降级**：当前没有任何加载超时检测或离线提示机制。

## 具体任务要求

### D1. 关键库本地打包

将以下静态资源下载到 `src/main/resources/webui/lib/`：

| 资源 | 目标路径 | 说明 |
|------|---------|------|
| marked.min.js | `webui/lib/marked.min.js` | Markdown 解析器 |
| highlight.min.js | `webui/lib/highlight.min.js` | 代码高亮核心 |
| kotlin.min.js | `webui/lib/languages/kotlin.min.js` | Kotlin 语言包 |
| java.min.js | `webui/lib/languages/java.min.js` | Java 语言包 |
| python.min.js | `webui/lib/languages/python.min.js` | Python 语言包 |
| javascript.min.js | `webui/lib/languages/javascript.min.js` | JS 语言包 |
| typescript.min.js | `webui/lib/languages/typescript.min.js` | TS 语言包 |
| go.min.js | `webui/lib/languages/go.min.js` | Go 语言包 |
| rust.min.js | `webui/lib/languages/rust.min.js` | Rust 语言包 |
| xml.min.js | `webui/lib/languages/xml.min.js` | XML/HTML 语言包 |
| css.min.js | `webui/lib/languages/css.min.js` | CSS 语言包 |
| json.min.js | `webui/lib/languages/json.min.js` | JSON 语言包 |
| yaml.min.js | `webui/lib/languages/yaml.min.js` | YAML 语言包 |
| bash.min.js | `webui/lib/languages/bash.min.js` | Bash 语言包 |
| sql.min.js | `webui/lib/languages/sql.min.js` | SQL 语言包 |
| github-dark.min.css | `webui/lib/github-dark.min.css` | 高亮暗色主题 |
| github.min.css | `webui/lib/github.min.css` | 高亮色主题 |
| all.min.css + webfonts | `webui/lib/font-awesome/` | Font Awesome CSS + 字体文件 |

**体积约束**：所有资源文件总大小 < 5MB。

**下载脚本**：在 `scripts/download-webui-libs.sh`（或 Gradle Task）中编写自动化下载脚本，使用 `curl` 从 cdnjs 下载指定版本，便于后续更新。

### D2. `chat.html` 引用切换 + `loadHTML()` 兼容

由于 `JCEFChatPanel` 使用 `loadHTML()` 加载内联 HTML，相对路径 `./lib/xxx` **不会自动解析到 `resources`**。提供两种兼容方案：

**方案 A（推荐：Data URI 内联）**：
- 修改 `JCEFChatPanel.initializeBrowser()`：在读取 `chat.html` 后、调用 `loadHTML()` 之前，将外部 CDN URL 替换为 **Data URI（Base64 编码的本地资源内容）**。
- 新增 `ResourceInliner` 工具类，负责：
  1. 读取 `resources/webui/lib/` 下各文件内容
  2. 根据文件类型生成 `data:text/javascript;base64,xxx` 或 `data:text/css;base64,xxx`
  3. 替换 `chat.html` 中的 `<script src="https://cdn...">` 和 `<link href="https://cdn...">` 为对应的 Data URI
- 优势：无需修改 `chat.html` 中的引用路径，兼容 `loadHTML()`；所有资源打包在 jar 内，无外部文件系统依赖。

**方案 B（本地文件服务器）**：
- 若方案 A 导致 HTML 过大（Base64 膨胀约 33%），可改为使用 `JBCefBrowser.loadURL("file:///absolute/path/to/resources/webui/chat.html")`。
- 需要确保 `resources` 目录在运行时解压到磁盘临时目录（IntelliJ 插件的 `getPluginPath()` 可提供路径）。
- 相对路径 `./lib/xxx` 自然生效。

**采用方案 A**，因为插件分发为 jar，`loadHTML()` + Data URI 更稳定，不依赖运行时文件系统布局。

具体修改点：
- `JCEFChatPanel.kt`：在 `initializeBrowser()` 中新增 `inlineResources(htmlContent)` 步骤。
- `chat.html` 中的 `<script src>` / `<link rel="stylesheet">` 保留原 CDN URL 作为标记，由 `ResourceInliner` 在运行时替换。
- 主题切换函数 `toggleTheme()` / `setTheme()` 中的 `hljsTheme.href` 赋值改为切换 Data URI（预先生成两套主题的 Data URI，存储在 JS 变量中）。

### D3. TailwindCSS 处理

TailwindCSS CDN (`cdn.tailwindcss.com`) 是 **JIT 编译器**，无法在离线环境下直接下载使用。处理方式：

- **分析 `chat.html` 实际使用的 Tailwind 类名**：搜索所有 `class="..."` 中出现的 Tailwind utility classes（如 `flex`, `h-screen`, `bg-primary`, `rounded-lg`, `px-4`, `py-3`, `border-b`, `text-sm`, `font-semibold` 等）。
- **生成预编译 CSS**：使用 Tailwind CLI 或手动提取，生成仅包含已使用类名的 `tailwind.generated.css`，放入 `src/main/resources/webui/lib/`。
- **替换 CDN 引用**：移除 `<script src="https://cdn.tailwindcss.com"></script>`，改为 `<link rel="stylesheet" href="./lib/tailwind.generated.css">`（若采用方案 A，则同样内联为 Data URI）。
- **自定义 CSS 变量保留**：`chat.html` 中大量使用 CSS 变量（`:root` 和 `[data-theme="dark"]`），这些定义在 `<style>` 标签内，不受 Tailwind CDN移除影响，无需修改。

**简化方案**：由于项目中 Tailwind 仅用于 utility classes（无 `@apply` 自定义规则），可使用 `tailwindcss` npm 包预编译：
```bash
npx tailwindcss -i ./src/main/resources/webui/tailwind-input.css -o ./src/main/resources/webui/lib/tailwind.generated.css --minify --content ./src/main/resources/webui/chat.html
```
输入文件 `tailwind-input.css` 只需包含 `@tailwind base; @tailwind components; @tailwind utilities;`。

若环境中无 Node.js，可改用**手动提取**：通过正则提取 `chat.html` 中所有 `class="..."` 的类名，生成最小化 CSS。但推荐优先尝试 Tailwind CLI。

### D4. 离线可用性自检

在 `JCEFChatPanel.initializeBrowser()` 中增加加载超时检测：

- 在注入 JS Bridge 的脚本中，增加一个**关键库加载检测**函数：
  ```javascript
  window.checkCriticalLibraries = function() {
      return typeof marked !== 'undefined' 
          && typeof hljs !== 'undefined'
          && typeof window.javaBridge !== 'undefined';
  };
  ```
- `JCEFChatPanel` 在 `injectJSBridge()` 完成后（或页面加载完成后 5 秒内），通过 `cefBrowser.executeJavaScript("window.checkCriticalLibraries()", ...)` 检查返回值。
- 若 5 秒内关键库未加载完成（由于 Data URI 内联，理论上应瞬间加载；此检测主要防御内联失败或资源缺失）：
  - 执行 JS 显示离线提示：注入一个覆盖全屏的 `div`，显示 "CodeSage 界面加载异常，部分资源可能缺失。" 和 [重载] 按钮。
  - 重载按钮调用 Kotlin 方法触发 `browser.reload()` 或重新执行 `initializeBrowser()`。
- 新增 `showOfflineWarning()` 和 `reloadBrowser()` 私有方法。

## 验收标准

- [ ] 断开网络后，重新打开 CodeSage Tool Window，`chat.html` 仍能完整渲染（包括代码高亮、Markdown 解析、图标）。
- [ ] `src/main/resources/webui/lib/` 目录下包含所有必要静态资源（可通过 `scripts/download-webui-libs.sh` 一键下载）。
- [ ] 资源文件总大小 < 5MB（压缩后的 jar 内）。
- [ ] `./gradlew test` 全部通过，无回归（允许已存在的 `ConversationPersistenceTest` flaky 失败）。
- [ ] 新增 `JCEFOfflineTest` 或类似测试：验证 `ResourceInliner` 能将至少 3 个 CDN URL 正确替换为 Data URI 格式。

## 通用约束与规范

1. **最小侵入性**：不修改 `chat.html` 的 DOM 结构和业务逻辑（`sendMessage`、`handleJSMessage` 等），仅修改 `<head>` 中的资源引用和尾部的一小段主题切换逻辑。
2. **资源完整性**：下载的静态资源必须包含对应版本的完整文件，不可截断（Font Awesome 需包含 woff2 字体文件，否则图标显示为方框）。
3. **性能约束**：Data URI 内联后的 HTML 字符串大小应 < 2MB，避免 `loadHTML()` 内存开销过大。若超标，改用 `loadURL()` 方案。
4. **版权合规**：所有下载的库均为 MIT/BSD/Apache 开源许可，需在 `src/main/resources/webui/lib/LICENSES.md` 中列出各库名称、版本、许可证链接。
5. **向后兼容**：保留 CDN URL 作为注释或 fallback（在 Data URI 生成失败时，可临时回退到原始 CDN 加载）。

## 输出格式要求

完成代码修改后，请按以下格式回复：

```markdown
## 完成报告：目标 D

### 修改文件清单
1. `src/main/kotlin/...` - 修改说明
2. ...

### 关键设计决策
- 决策 A：原因...
- 决策 B：原因...

### 测试验证
```bash
./gradlew test
# 结果：BUILD SUCCESSFUL / X tests completed, Y failed
```
```
