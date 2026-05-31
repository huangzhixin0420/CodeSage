package com.codesage.prompt.engine

/**
 * 提示模板引擎
 * 支持变量插值 {{varName}}、条件块 {{#if condition}}...{{/if}} 和循环块 {{#each list}}...{{/each}}
 */
class PromptTemplate(
    private val template: String
) {
    companion object {
        private val VAR_PATTERN = Regex("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\}\\}")
        private val IF_PATTERN = Regex(
            "\\{\\{\\s*#if\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\}\\}(.*?)\\{\\{\\s*/if\\s*\\}\\}",
            RegexOption.DOT_MATCHES_ALL
        )
        private val EACH_PATTERN = Regex(
            "\\{\\{\\s*#each\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\}\\}(.*?)\\{\\{\\s*/each\\s*\\}\\}",
            RegexOption.DOT_MATCHES_ALL
        )
    }

    /**
     * 渲染模板，替换所有变量
     */
    fun render(variables: Map<String, Any>): String {
        var result = template

        // 处理条件块
        result = processConditionals(result, variables)

        // 处理循环块
        result = processLoops(result, variables)

        // 处理简单变量替换
        result = processVariables(result, variables)

        return result.trim()
    }

    private fun processConditionals(text: String, variables: Map<String, Any>): String {
        return IF_PATTERN.replace(text) { matchResult ->
            val conditionName = matchResult.groupValues[1]
            val blockContent = matchResult.groupValues[2]
            val value = variables[conditionName]
            val isTrue = when (value) {
                is Boolean -> value
                is String -> value.isNotBlank() && value != "false"
                is Number -> value.toDouble() != 0.0
                is Collection<*> -> value.isNotEmpty()
                null -> false
                else -> true
            }
            if (isTrue) blockContent else ""
        }
    }

    private fun processLoops(text: String, variables: Map<String, Any>): String {
        return EACH_PATTERN.replace(text) { matchResult ->
            val listName = matchResult.groupValues[1]
            val itemTemplate = matchResult.groupValues[2]
            val list = variables[listName]

            when (list) {
                is Collection<*> -> {
                    list.filterNotNull().joinToString("\n") { item ->
                        renderItemTemplate(itemTemplate, item)
                    }
                }

                is Array<*> -> {
                    list.filterNotNull().joinToString("\n") { item ->
                        renderItemTemplate(itemTemplate, item)
                    }
                }

                else -> ""
            }
        }
    }

    private fun renderItemTemplate(itemTemplate: String, item: Any): String {
        val itemVars = when (item) {
            is Map<*, *> -> item.mapKeys { it.key.toString() }.mapValues { it.value ?: "" }
            else -> mapOf("this" to item.toString(), "item" to item.toString())
        }
        return PromptTemplate(itemTemplate).render(itemVars)
    }

    private fun processVariables(text: String, variables: Map<String, Any>): String {
        return VAR_PATTERN.replace(text) { matchResult ->
            val varName = matchResult.groupValues[1]
            variables[varName]?.toString() ?: "{{$varName}}"
        }
    }

    /**
     * 提取模板中所有变量名
     */
    fun extractVariables(): Set<String> {
        val vars = VAR_PATTERN.findAll(template).map { it.groupValues[1] }.toMutableSet()
        IF_PATTERN.findAll(template).forEach { vars.add(it.groupValues[1]) }
        EACH_PATTERN.findAll(template).forEach { vars.add(it.groupValues[1]) }
        return vars
    }
}

/**
 * 模板构建器，用于链式构建复杂模板
 */
class PromptTemplateBuilder {
    private val sections = mutableListOf<String>()
    private val variables = mutableMapOf<String, Any>()

    fun section(title: String, content: String): PromptTemplateBuilder {
        sections.add("## $title\n$content")
        return this
    }

    fun raw(text: String): PromptTemplateBuilder {
        sections.add(text)
        return this
    }

    fun withVar(name: String, value: Any): PromptTemplateBuilder {
        variables[name] = value
        return this
    }

    fun withVars(vars: Map<String, Any>): PromptTemplateBuilder {
        variables.putAll(vars)
        return this
    }

    fun build(): Pair<PromptTemplate, Map<String, Any>> {
        val template = sections.joinToString("\n\n")
        return PromptTemplate(template) to variables.toMap()
    }

    fun render(): String {
        val (template, vars) = build()
        return template.render(vars)
    }
}
