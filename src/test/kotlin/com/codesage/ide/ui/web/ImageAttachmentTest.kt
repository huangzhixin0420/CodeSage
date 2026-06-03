package com.codesage.ide.ui.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * ImageAttachment 数据类单元测试
 *
 * 简单但关键:验证 data class 字段、equals/hashCode/toString 行为
 */
class ImageAttachmentTest {

    @Test
    fun `data class equality works correctly`() {
        val a = ImageAttachment(
            id = "img-1",
            mime = "image/png",
            dataUrl = "data:image/png;base64,iVBORw0",
            name = "screenshot.png",
        )
        val b = a.copy()
        val c = a.copy(id = "img-2")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `toString includes all fields`() {
        val a = ImageAttachment(
            id = "img-1",
            mime = "image/jpeg",
            dataUrl = "data:image/jpeg;base64,/9j/",
            name = "photo.jpg",
        )
        val s = a.toString()
        assertTrue(s.contains("img-1"))
        assertTrue(s.contains("image/jpeg"))
        assertTrue(s.contains("photo.jpg"))
    }

    @Test
    fun `copy allows field overrides`() {
        val a = ImageAttachment(
            id = "img-1",
            mime = "image/png",
            dataUrl = "data:image/png;base64,xxx",
            name = "a.png",
        )
        val b = a.copy(name = "renamed.png")
        assertEquals("img-1", b.id)
        assertEquals("renamed.png", b.name)
        assertEquals("image/png", b.mime)
        assertEquals("data:image/png;base64,xxx", b.dataUrl)
    }
}
