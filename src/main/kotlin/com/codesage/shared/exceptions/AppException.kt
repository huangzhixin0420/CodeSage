package com.codesage.shared.exceptions

/**
 * 应用异常基类
 */
open class AppException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 模型相关异常
 */
class ModelNotFoundException(message: String) : AppException(message)

/**
 * 网络相关异常
 */
class NetworkException(message: String, cause: Throwable? = null) : AppException(message, cause)

/**
 * 配置异常
 */
class ConfigurationException(message: String) : AppException(message)

/**
 * 技能执行异常
 */
class SkillExecutionException(
    message: String,
    val skillId: String? = null,
    cause: Throwable? = null
) : AppException(message, cause)

/**
 * 规则执行异常
 */
class RuleExecutionException(
    message: String,
    val ruleId: String? = null,
    cause: Throwable? = null
) : AppException(message, cause)

// 类型别名保持向后兼容
typealias ModelNotFound = ModelNotFoundException
typealias NetworkError = NetworkException
typealias UnsupportedFeature = UnsupportedFeatureException
typealias SkillNotFound = SkillNotFoundException
typealias InvalidConfig = ConfigurationException

class UnsupportedFeatureException(message: String) : AppException(message)
class SkillNotFoundException(message: String) : AppException(message)

/**
 * 速率限制异常
 */
class RateLimitException(
    message: String,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null
) : AppException(message, cause)

/**
 * 认证过期异常
 */
class AuthExpiredException(message: String, cause: Throwable? = null) : AppException(message, cause)

/**
 * 上下文长度超限异常
 */
class ContextTooLongException(
    message: String,
    val approxTokens: Int? = null,
    val maxTokens: Int? = null,
    cause: Throwable? = null
) : AppException(message, cause)

/**
 * 空响应异常
 */
class EmptyResponseException(message: String, cause: Throwable? = null) : AppException(message, cause)

/**
 * 无效工具调用异常
 */
class InvalidToolCallException(
    message: String,
    val toolName: String? = null,
    cause: Throwable? = null
) : AppException(message, cause)

/**
 * 提供者不可用异常
 */
class ProviderUnavailableException(message: String, cause: Throwable? = null) : AppException(message, cause)
