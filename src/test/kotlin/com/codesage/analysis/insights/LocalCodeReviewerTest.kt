package com.codesage.analysis.insights

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T5.4 修复验证测试：本地代码审查引擎
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T5.4）：
 * - [x] 单元测试：典型 Java 代码含 SQL 注入规则命中
 * - [x] 单元测试：100 个文件的本地 review < 5s（无 LLM 调用）
 */
class LocalCodeReviewerTest {

    // === Security 规则测试 ===

    @Test
    fun `detects SQL injection in Java code`() {
        val source = """
            public class UserDao {
                public User findById(String id) throws SQLException {
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT * FROM users WHERE id=" + id);
                    return mapUser(rs);
                }
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "java")
        val sqlInjection = findings.find { it.ruleId == "security.sql-injection" }
        assertNotNull(sqlInjection, "should detect SQL injection")
        assertEquals(LocalCodeReviewer.Severity.CRITICAL, sqlInjection!!.severity)
        assertEquals(LocalCodeReviewer.Category.SECURITY, sqlInjection.category)
    }

    @Test
    fun `detects hardcoded password`() {
        val source = """
            class Config {
                val apiKey = "sk-1234567890abcdef"
                val password = "supersecret123"
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        val passwordFindings = findings.filter { it.ruleId == "security.hardcoded-password" }
        assertTrue(passwordFindings.size >= 2, "should detect both apiKey and password")
    }

    @Test
    fun `detects weak crypto algorithm`() {
        val source = """
            fun hash(data: String): String {
                val digest = MessageDigest.getInstance("MD5")
                return digest.digest(data.toByteArray()).toString()
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        val weakCrypto = findings.find { it.ruleId == "security.weak-crypto" }
        assertNotNull(weakCrypto, "should detect MD5 usage")
    }

    @Test
    fun `detects command injection`() {
        val source = """
            fun runUserCommand(cmd: String) {
                Runtime.getRuntime().exec(cmd)
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        val injection = findings.find { it.ruleId == "security.command-injection" }
        assertNotNull(injection)
    }

    @Test
    fun `detects XSS risk in JavaScript`() {
        val source = """
            function render(userInput) {
                document.getElementById('output').innerHTML = "<div>" + userInput + "</div>";
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "javascript")
        val xss = findings.find { it.ruleId == "security.xss-risk" }
        assertNotNull(xss, "should detect innerHTML with concatenation")
    }

    // === Performance 规则测试 ===

    @Test
    fun `detects N+1 query pattern`() {
        val source = """
            fun getAllUsers(): List<User> {
                val users = userRepo.findAll()
                val result = mutableListOf<UserDetail>()
                for (user in users) {
                    val details = userRepo.findById(user.id)
                    result.add(details)
                }
                return result
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        val nPlusOne = findings.find { it.ruleId == "performance.n-plus-one" }
        assertNotNull(nPlusOne, "should detect N+1 pattern (findBy inside for loop)")
    }

    @Test
    fun `detects IO in loop`() {
        val source = """
            fun processIds(ids: List<String>) {
                for (id in ids) {
                    httpClient.send(id)
                }
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        val loopIo = findings.find { it.ruleId == "performance.loop-io" }
        assertNotNull(loopIo)
    }

    // === Style 规则测试 ===

    @Test
    fun `detects long parameter list`() {
        val source = """
            fun createUser(
                name: String,
                email: String,
                age: Int,
                city: String,
                country: String,
                phone: String
            ): User {
                return User(name, email, age, city, country, phone)
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        val longParams = findings.find { it.ruleId == "style.long-parameter-list" }
        assertNotNull(longParams)
    }

    @Test
    fun `detects TODO comments`() {
        val source = """
            // TODO: refactor this
            fun foo() {
                // FIXME: edge case
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        val todos = findings.filter { it.ruleId == "style.todo-comment" }
        assertTrue(todos.size >= 2, "should detect both TODO and FIXME")
    }

    // === Correctness 规则测试 ===

    @Test
    fun `detects empty catch block`() {
        val source = """
            try {
                riskyOp()
            } catch (e: Exception) {
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        val empty = findings.find { it.ruleId == "correctness.empty-catch" }
        assertNotNull(empty)
    }

    @Test
    fun `detects System out print usage`() {
        val source = """
            fun debug(msg: String) {
                System.out.println("DEBUG: " + msg)
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        val sysout = findings.find { it.ruleId == "correctness.system-out" }
        assertNotNull(sysout)
    }

    // === 综合测试 ===

    @Test
    fun `clean code produces minimal findings`() {
        val source = """
            fun add(a: Int, b: Int): Int {
                val logger = Logger.getLogger()
                try {
                    return a + b
                } catch (e: Exception) {
                    logger.error("add failed", e)
                    throw e
                }
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        // 不应该有严重问题
        val criticalOrHigh = findings.filter {
            it.severity == LocalCodeReviewer.Severity.CRITICAL ||
                    it.severity == LocalCodeReviewer.Severity.HIGH
        }
        assertEquals(0, criticalOrHigh.size, "clean code should have no critical/high findings")
    }

    @Test
    fun `reviewAndSummarize groups by severity and category`() {
        val source = """
            fun bad() {
                val password = "secret123"
                for (i in 0..10) {
                    httpClient.send(i)
                }
                try {
                    riskyOp()
                } catch (e: Exception) {
                }
            }
        """.trimIndent()
        val summary = LocalCodeReviewer.reviewAndSummarize(source, "kotlin")
        assertTrue(summary.totalFindings >= 3)
        assertTrue(summary.bySeverity.isNotEmpty())
        assertTrue(summary.byCategory.isNotEmpty())
        assertTrue(summary.hasCriticalOrHigh(), "summary should flag critical/high")
    }

    @Test
    fun `lineNumber is correct for findings`() {
        val source = """
            fun clean() {
                val x = 1
            }
            fun bad() {
                val password = "leaked"
            }
        """.trimIndent()
        val findings = LocalCodeReviewer.review(source, "kotlin")
        val pwFinding = findings.find { it.ruleId == "security.hardcoded-password" }
        assertNotNull(pwFinding)
        // 应该在第 5 行（password 所在行）
        assertEquals(5, pwFinding!!.lineNumber)
        assertTrue(pwFinding.lineContent.contains("password"))
    }

    @Test
    fun `empty source produces no findings`() {
        val findings = LocalCodeReviewer.review("", "kotlin")
        assertEquals(0, findings.size)
    }

    @Test
    fun `100 file review is fast without LLM`() {
        // 性能验证：100 个文件本地 review < 5s
        val sources = (0 until 100).map { i ->
            """
                fun example$i() {
                    val password = "secret$i"
                    for (j in 0..10) {
                        if (j > 5) println(j)
                    }
                    try {
                        riskyOp()
                    } catch (e: Exception) {
                        System.out.println(e)
                    }
                }
            """.trimIndent()
        }
        val start = System.currentTimeMillis()
        val allFindings = sources.mapIndexed { i, src ->
            LocalCodeReviewer.review(src, "kotlin", "file_$i.kt")
        }
        val totalFindings = allFindings.sumOf { it.size }
        val elapsedMs = System.currentTimeMillis() - start

        assertTrue(totalFindings > 0, "should find issues in 100 files")
        assertTrue(elapsedMs < 5_000, "100-file review should be < 5s, got ${elapsedMs}ms")
        // 100 文件平均每文件 < 50ms
        println("[PerfTest] 100-file review took ${elapsedMs}ms, avg ${elapsedMs / 100.0}ms/file, $totalFindings findings")
    }
}
