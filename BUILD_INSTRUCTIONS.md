# CodeSage 构建说明

## 环境要求
- JDK 17+
- Kotlin 2.3.20
- Gradle 8.5+ (通过wrapper)

## 构建步骤

### 1. 配置Gradle Wrapper
```bash
gradle wrapper --gradle-version 8.5
```

### 2. 首次构建
```bash
./gradlew build --no-daemon
```

### 3. 运行测试
```bash
./gradlew test --no-daemon
```

### 4. 打包插件
```bash
./gradlew buildPlugin --no-daemon
```

## 常见问题

### 插件解析失败
如果遇到 `Plugin not found` 错误，检查：
1. 网络连接
2. Maven Central 可访问性
3. Gradle 版本

### 替代方案
如果Gradle配置有问题，可以：
1. 直接使用已安装的Gradle版本
2. 或使用 IntelliJ IDEA 打开项目，它会自动处理依赖

## 项目文件结构
```
src/
├── main/kotlin/com/codesage/
│   ├── agent/          # Agent核心
│   ├── ide/            # IDE集成
│   ├── mcp/             # MCP协议
│   ├── model/           # 模型层
│   ├── plugin/          # 插件入口
│   ├── rule/            # 规则引擎
│   ├── shared/          # 共享组件
│   └── skill/           # 技能系统
├── main/resources/
│   ├── META-INF/        # 插件配置
│   ├── rules/           # 规则配置
│   ├── skills/          # 技能配置
│   └── logback.xml      # 日志配置
└── test/kotlin/         # 单元测试
```