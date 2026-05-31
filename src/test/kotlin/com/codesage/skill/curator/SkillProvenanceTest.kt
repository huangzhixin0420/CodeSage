package com.codesage.skill.curator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SkillProvenanceTest {

    @Test
    fun `should default to foreground`() {
        assertEquals(SkillProvenance.FOREGROUND, SkillProvenance.get())
    }

    @Test
    fun `should set and get origin`() {
        SkillProvenance.set(SkillProvenance.BACKGROUND_REVIEW)
        assertEquals(SkillProvenance.BACKGROUND_REVIEW, SkillProvenance.get())
        assertTrue(SkillProvenance.isBackgroundReview())

        SkillProvenance.set(SkillProvenance.USER_CREATED)
        assertEquals(SkillProvenance.USER_CREATED, SkillProvenance.get())
        assertTrue(SkillProvenance.isUserCreated())

        SkillProvenance.set(SkillProvenance.AGENT_CREATED)
        assertEquals(SkillProvenance.AGENT_CREATED, SkillProvenance.get())
        assertTrue(SkillProvenance.isAgentCreated())
    }

    @Test
    fun `should reset to foreground`() {
        SkillProvenance.set(SkillProvenance.AGENT_CREATED)
        SkillProvenance.reset()
        assertEquals(SkillProvenance.FOREGROUND, SkillProvenance.get())
        assertFalse(SkillProvenance.isAgentCreated())
    }

    @Test
    fun `should be thread local`() {
        // 在当前线程设置
        SkillProvenance.set(SkillProvenance.USER_CREATED)

        // 在另一个线程检查默认值
        val otherThreadValue = mutableListOf<String>()
        val thread = Thread {
            otherThreadValue.add(SkillProvenance.get())
        }
        thread.start()
        thread.join()

        assertEquals(SkillProvenance.FOREGROUND, otherThreadValue[0])
        assertEquals(SkillProvenance.USER_CREATED, SkillProvenance.get())
    }
}
