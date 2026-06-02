package com.codesage.shared.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T0.6 修复验证测试：Null-safety 工具方法
 */
class NullSafetyTest {

    @Test
    fun `orElse returns value when not null`() {
        val result = NullSafety.orElse("hello", "default")
        assertEquals("hello", result)
    }

    @Test
    fun `orElse returns fallback when null`() {
        val result = NullSafety.orElse(null, "default")
        assertEquals("default", result)
    }

    @Test
    fun `orElseGet invokes supplier only when null`() {
        var callCount = 0
        val result1 = NullSafety.orElseGet("value") {
            callCount++
            "computed"
        }
        assertEquals("value", result1)
        assertEquals(0, callCount, "supplier should not be called when value is non-null")

        val result2 = NullSafety.orElseGet(null) {
            callCount++
            "computed"
        }
        assertEquals("computed", result2)
        assertEquals(1, callCount)
    }

    @Test
    fun `requireNonNull returns value when non-null`() {
        val result = NullSafety.requireNonNull("hello", "test")
        assertEquals("hello", result)
    }

    @Test
    fun `requireNonNull throws default exception when null`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            NullSafety.requireNonNull(null, "myField")
        }
        assertTrue(ex.message!!.contains("myField"), "Error message should mention field name: ${ex.message}")
    }

    @Test
    fun `requireNonNull uses custom exception factory`() {
        class CustomException(msg: String) : RuntimeException(msg)

        val ex = assertThrows(CustomException::class.java) {
            NullSafety.requireNonNull<String>(null, "myField") { CustomException("custom: myField") }
        }
        assertEquals("custom: myField", ex.message)
    }

    @Test
    fun `orElse handles different types generically`() {
        val str: String? = null
        val result: String = NullSafety.orElse(str, "<none>")
        assertEquals("<none>", result)

        val list: List<Int>? = listOf(1, 2, 3)
        val result2: List<Int> = NullSafety.orElse(list, emptyList())
        assertEquals(listOf(1, 2, 3), result2)
    }
}
