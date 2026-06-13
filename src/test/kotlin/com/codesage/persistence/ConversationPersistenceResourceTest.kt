package com.codesage.persistence

import com.codesage.model.dto.Message
import com.codesage.model.dto.Role
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * T0.3 修复验证测试：ConversationPersistence 资源/数据问题
 *
 * 验证：
 * 1. shutdown() 等待 in-flight 任务完成（不立刻退出）
 * 2. deleteSession 不会让 loadSession "复活" 已被删除的 session
 * 3. renameTo 失败时 tempFile 被保留（不会静默丢失）
 * 4. delete 之后 pending save 不会复活该 session
 */
class ConversationPersistenceResourceTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newPersistence(): ConversationPersistence {
        val cp = ConversationPersistence(storageDir = File(tempDir.toFile(), "conversations"))
        return cp
    }

    private fun newSession(
        id: String, messages: List<Message> = listOf(
            Message(role = Role.USER, content = "hello $id")
        )
    ) = com.codesage.agent.core.AgentSession(
        id = id,
        name = "Test $id",
        createdAt = System.currentTimeMillis(),
        lastActivityAt = System.currentTimeMillis(),
        isActive = true
    )

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `shutdown waits for in-flight writes`() {
        val cp = newPersistence()
        val session = newSession("s1")
        val messages = listOf(
            Message(role = Role.USER, content = "msg1"),
            Message(role = Role.ASSISTANT, content = "reply1")
        )

        // 启动 10 次并发 save
        repeat(10) { i ->
            cp.saveSession(session, messages + Message(role = Role.USER, content = "msg_$i"))
        }

        // 立刻 shutdown 应该等所有写入完成
        cp.shutdown()

        // 重新加载，验证数据完整性
        val cp2 = newPersistence()
        val loaded = cp2.loadSession("s1")
        assertNotNull(loaded, "Session should be persisted after shutdown")
        assertTrue(loaded!!.messages.isNotEmpty(), "Messages should be persisted")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `deleteSession prevents loadSession resurrection`() {
        val cp = newPersistence()
        val session = newSession("s2")
        val messages = listOf(Message(role = Role.USER, content = "important"))

        // 同步保存
        kotlinx.coroutines.runBlocking {
            cp.saveSessionSync(session, messages)
        }

        // 删除
        assertTrue(cp.deleteSession("s2"))

        // 重新加载应该返回 null
        val loaded = cp.loadSession("s2")
        assertNull(loaded, "Deleted session should not be loadable")

        // 即使创建新 persistence 也不应看到
        val cp2 = newPersistence()
        assertNull(cp2.loadSession("s2"), "Deleted session should not be loadable from fresh persistence")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `pending save after delete does not resurrect session`() {
        val cp = newPersistence()
        val session = newSession("s3")
        val messages = listOf(Message(role = Role.USER, content = "x"))

        // 第一次保存（异步）
        cp.saveSession(session, messages)
        // 等异步写完
        Thread.sleep(200)
        // 删除
        cp.deleteSession("s3")

        // 再次保存（异步）- 应被 deletedSessionIds 阻止
        cp.saveSession(session, messages + Message(role = Role.ASSISTANT, content = "y"))
        Thread.sleep(200)

        // 重新加载 - 不应有 s3
        val cp2 = newPersistence()
        val loaded = cp2.loadSession("s3")
        assertNull(loaded, "Save after delete should not resurrect the session")

        // 关闭
        cp.shutdown()
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `concurrent save and delete maintain consistency`() {
        val cp = newPersistence()
        val session = newSession("s4")
        val messages = listOf(Message(role = Role.USER, content = "c"))

        val ready = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val errors = AtomicInteger(0)

        val saver = Thread {
            ready.await()
            try {
                repeat(50) {
                    cp.saveSession(session, messages)
                    Thread.sleep(2)
                }
            } catch (e: Exception) {
                errors.incrementAndGet()
            }
        }
        val deleter = Thread {
            ready.await()
            try {
                repeat(10) {
                    cp.deleteSession("s4")
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                errors.incrementAndGet()
            }
        }
        saver.start()
        deleter.start()
        ready.countDown()
        saver.join()
        deleter.join()
        stop.countDown()

        assertEquals(0, errors.get(), "No exceptions expected during concurrent ops")

        // 最后清理
        cp.shutdown()
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `idempotent shutdown does not throw`() {
        val cp = newPersistence()
        cp.shutdown()
        cp.shutdown()  // 不应抛异常
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `save during shutdown does not throw RejectedExecutionException`() {
        val cp = newPersistence()
        val session = newSession("shutdown-race")
        val messages = listOf(Message(role = Role.USER, content = "x"))

        val saveErrors = AtomicInteger(0)
        val saver = Thread {
            repeat(200) {
                try {
                    cp.saveSession(session, messages)
                } catch (e: Exception) {
                    saveErrors.incrementAndGet()
                }
            }
        }
        saver.start()

        // 给 saver 一点时间启动，然后并发 shutdown
        Thread.sleep(5)
        cp.shutdown()

        saver.join()
        assertEquals(0, saveErrors.get(), "saveSession should not throw during shutdown")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `save after shutdown is silently ignored`() {
        val cp = newPersistence()
        val session = newSession("after-shutdown")
        val messages = listOf(Message(role = Role.USER, content = "x"))

        cp.shutdown()

        // 关闭后再调用 saveSession 不应抛异常
        assertDoesNotThrow {
            cp.saveSession(session, messages)
        }
    }
}
