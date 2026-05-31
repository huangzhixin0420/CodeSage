package com.codesage.skill.curator

/**
 * Skill 来源追踪
 *
 * 参考 Hermes 的 skill_provenance.py ContextVar 设计：
 * 区分用户主动创建 vs Agent 自主创建，用于策展时保留用户创建的技能。
 */
object SkillProvenance {

    private val writeOrigin = ThreadLocal.withInitial { FOREGROUND }

    const val BACKGROUND_REVIEW = "background_review"
    const val FOREGROUND = "foreground"
    const val USER_CREATED = "user_created"
    const val AGENT_CREATED = "agent_created"

    fun set(origin: String) = writeOrigin.set(origin)
    fun get(): String = writeOrigin.get()
    fun isBackgroundReview(): Boolean = get() == BACKGROUND_REVIEW
    fun isUserCreated(): Boolean = get() == USER_CREATED
    fun isAgentCreated(): Boolean = get() == AGENT_CREATED
    fun reset() = writeOrigin.set(FOREGROUND)
}
