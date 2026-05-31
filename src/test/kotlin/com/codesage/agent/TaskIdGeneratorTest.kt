package com.codesage.agent

import com.codesage.agent.planner.TaskIdGenerator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TaskIdGeneratorTest {

    @Test
    fun `should generate unique IDs in multi-threaded environment`() {
        val generator = TaskIdGenerator()
        val ids = ConcurrentHashMap.newKeySet<String>()
        val executor = Executors.newFixedThreadPool(10)

        repeat(100) {
            executor.submit {
                repeat(50) {
                    ids.add(generator.generate())
                }
            }
        }

        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        // 所有 5000 个 ID 都应该是唯一的
        assertEquals(5000, ids.size)
    }

    @Test
    fun `should generate sequential subtask IDs`() {
        val generator = TaskIdGenerator()
        assertEquals("subtask_0", generator.generateSubTaskId(0))
        assertEquals("subtask_1", generator.generateSubTaskId(1))
        assertEquals("subtask_99", generator.generateSubTaskId(99))
    }
}
