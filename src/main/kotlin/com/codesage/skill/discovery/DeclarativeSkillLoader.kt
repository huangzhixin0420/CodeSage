package com.codesage.skill.discovery

import com.codesage.skill.*
import com.codesage.skill.registry.SkillRegistry
import com.codesage.shared.utils.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream

/**
 * 声明式技能加载器
 *
 * 从 YAML/JSON 配置文件加载技能定义，并注册到 SkillRegistry。
 * 支持热加载和文件监控（通过外部触发 reload）。
 */
class DeclarativeSkillLoader(
    private val registry: SkillRegistry,
    private val yaml: Yaml = Yaml()
) {
    private val logger = Logger.getLogger<DeclarativeSkillLoader>()
    private val jsonParser = Json { ignoreUnknownKeys = true }

    /**
     * 从文件路径加载技能配置
     */
    fun loadFromFile(filePath: String): Int {
        val file = File(filePath)
        if (!file.exists()) {
            logger.warn("Skill config file not found: $filePath")
            return 0
        }
        return file.inputStream().use { loadFromStream(it, file.extension) }
    }

    /**
     * 从资源路径加载技能配置
     */
    fun loadFromResource(resourcePath: String): Int {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
        if (stream == null) {
            logger.warn("Skill config resource not found: $resourcePath")
            return 0
        }
        return stream.use {
            val ext = resourcePath.substringAfterLast('.', "yaml")
            loadFromStream(it, ext)
        }
    }

    /**
     * 从输入流加载技能配置
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun loadFromStream(stream: InputStream, format: String): Int {
        return try {
            val definitions = when (format.lowercase()) {
                "json" -> loadFromJson(stream)
                "yaml", "yml" -> loadFromYaml(stream)
                else -> {
                    logger.warn("Unsupported skill config format: $format")
                    return 0
                }
            }

            var count = 0
            definitions.forEach { def ->
                try {
                    val skill = createDeclarativeSkill(def)
                    registry.register(skill)
                    count++
                    logger.info("Loaded declarative skill: ${def.id} (${def.name})")
                } catch (e: Exception) {
                    logger.error("Failed to load skill ${def.id}: ${e.message}", e)
                }
            }
            logger.info("Loaded $count declarative skills from $format")
            count
        } catch (e: Exception) {
            logger.error("Failed to load skill config", e)
            0
        }
    }

    /**
     * 加载所有默认的声明式技能配置
     */
    fun loadDefaultConfigs(): Int {
        var total = 0
        total += loadFromResource("skills/builtin-skills.yaml")
        total += loadFromResource("skills/custom-skills.yaml")
        total += loadFromResource("skills/external-skills.yaml")

        // 也检查用户自定义目录
        val userSkillsDir = File(System.getProperty("user.home"), ".codesage/skills")
        if (userSkillsDir.exists() && userSkillsDir.isDirectory) {
            userSkillsDir.listFiles { f ->
                f.extension in setOf("yaml", "yml", "json")
            }?.forEach { file ->
                total += loadFromFile(file.absolutePath)
            }
        }
        return total
    }

    private fun loadFromJson(stream: InputStream): List<SkillDefinition> {
        return jsonParser.decodeFromStream<List<SkillDefinition>>(stream)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromYaml(stream: InputStream): List<SkillDefinition> {
        val map = yaml.load<Map<String, Any>>(stream)
        val skillsList = map["skills"] as? List<Map<String, Any>> ?: return emptyList()

        return skillsList.map { skillMap ->
            parseSkillDefinition(skillMap)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSkillDefinition(map: Map<String, Any>): SkillDefinition {
        val implementationMap = map["implementation"] as? Map<String, Any>
        val implementation = when (implementationMap?.get("type") as? String) {
            "builtin" -> SkillImplementationType.BuiltIn(
                className = implementationMap["className"] as? String ?: ""
            )

            "external" -> SkillImplementationType.External(
                type = implementationMap["externalType"] as? String ?: "command",
                command = implementationMap["command"] as? String,
                url = implementationMap["url"] as? String,
                script = implementationMap["script"] as? String
            )

            else -> SkillImplementationType.BuiltIn("")
        }

        return SkillDefinition(
            id = map["id"] as String,
            name = map["name"] as String,
            description = map["description"] as String,
            version = map["version"] as? String ?: "1.0.0",
            category = parseCategory(map["category"] as? String),
            tags = (map["tags"] as? List<String>)?.toSet() ?: emptySet(),
            inputSchema = (map["inputSchema"] as? Map<String, Any>) ?: emptyMap(),
            outputSchema = (map["outputSchema"] as? Map<String, Any>) ?: emptyMap(),
            implementation = implementation,
            config = (map["config"] as? Map<String, Any>) ?: emptyMap()
        )
    }

    private fun parseCategory(category: String?): SkillCategory {
        return try {
            SkillCategory.valueOf(category?.uppercase() ?: "CUSTOM")
        } catch (_: IllegalArgumentException) {
            SkillCategory.CUSTOM
        }
    }

    private fun createDeclarativeSkill(def: SkillDefinition): Skill {
        return when (def.implementation) {
            is SkillImplementationType.BuiltIn -> {
                // 尝试通过反射加载内置类
                try {
                    val clazz = Class.forName(def.implementation.className)
                    clazz.getDeclaredConstructor().newInstance() as Skill
                } catch (e: Exception) {
                    logger.warn("Built-in class ${def.implementation.className} not found, falling back to declarative execution")
                    DeclarativeSkill(
                        id = def.id, name = def.name, description = def.description,
                        version = def.version, category = def.category, tags = def.tags,
                        inputSchema = def.inputSchema, outputSchema = def.outputSchema,
                        implementation = def.implementation
                    )
                }
            }

            is SkillImplementationType.External -> DeclarativeSkill(
                id = def.id, name = def.name, description = def.description,
                version = def.version, category = def.category, tags = def.tags,
                inputSchema = def.inputSchema, outputSchema = def.outputSchema,
                implementation = def.implementation
            )
        }
    }
}
