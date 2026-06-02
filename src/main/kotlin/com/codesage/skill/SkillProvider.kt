package com.codesage.skill

import com.intellij.openapi.extensions.ExtensionPointName

/**
 * 技能提供器接口
 *
 * 其他 IntelliJ 插件可通过 plugin.xml 注册此扩展点，向 CodeSage 动态贡献 Skill。
 *
 * 使用示例（在其他插件的 plugin.xml 中）：
 * ```xml
 * <extensions defaultExtensionNs="com.codesage.plugin">
 *     <skillProvider implementation="com.example.MySkillProvider"/>
 * </extensions>
 * ```
 */
interface SkillProvider {
    /**
     * 返回此提供器贡献的所有 Skill
     */
    fun getSkills(): List<Skill>

    /**
     * 提供器名称（用于日志和调试）
     */
    val providerName: String get() = javaClass.simpleName

    companion object {
        val EP_NAME = ExtensionPointName<SkillProvider>("com.codesage.plugin.skillProvider")
    }
}
