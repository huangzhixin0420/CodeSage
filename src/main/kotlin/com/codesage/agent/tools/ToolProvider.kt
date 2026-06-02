package com.codesage.agent.tools

import com.intellij.openapi.extensions.ExtensionPointName

/**
 * 工具提供器接口
 *
 * 其他 IntelliJ 插件可通过 plugin.xml 注册此扩展点，向 CodeSage 动态贡献 ToolHandler。
 *
 * 使用示例（在其他插件的 plugin.xml 中）：
 * ```xml
 * <extensions defaultExtensionNs="com.codesage.plugin">
 *     <toolProvider implementation="com.example.MyToolProvider"/>
 * </extensions>
 * ```
 */
interface ToolProvider {
    /**
     * 返回此提供器贡献的所有 ToolHandler
     */
    fun getToolHandlers(): List<ToolHandler>

    /**
     * 提供器名称（用于日志和调试）
     */
    val providerName: String get() = javaClass.simpleName

    companion object {
        val EP_NAME = ExtensionPointName<ToolProvider>("com.codesage.plugin.toolProvider")
    }
}
