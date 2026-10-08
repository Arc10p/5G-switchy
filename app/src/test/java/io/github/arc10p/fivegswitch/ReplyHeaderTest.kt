package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReplyHeaderTest {
    private fun bytes(vararg words: Int): ByteArray = ByteBuffer.allocate(words.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { words.forEach { putInt(it) } }.array()

    @Test fun `普通成功头只允许准确的载荷大小`() {
        assertEquals(4, ReplyHeader.payloadOffset(bytes(0, 1), 4))
        assertEquals(4, ReplyHeader.payloadOffset(bytes(0, 392191, 0), 8))
        assertThrows(IllegalArgumentException::class.java) { ReplyHeader.payloadOffset(bytes(0), 4) }
    }
    @Test fun `StrictMode 零大小头不能被系统宽松处理成成功`() {
        assertThrows(IllegalArgumentException::class.java) { ReplyHeader.payloadOffset(bytes(-128, 0, 1), 4) }
        assertThrows(IllegalArgumentException::class.java) { ReplyHeader.payloadOffset(bytes(-128, 0, 392191, 0), 8) }
    }
    @Test fun `额外头长度越界负数或不对齐全部拒绝`() {
        listOf(-1, 4, 9, Int.MAX_VALUE).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) { ReplyHeader.payloadOffset(bytes(-128, size, 0, 1), 4) }
        }
    }
    @Test fun `合法额外头计算边界并交给系统验证内容`() {
        assertEquals(12, ReplyHeader.payloadOffset(bytes(-128, 8, 0, 1), 4))
        assertEquals(16, ReplyHeader.payloadOffset(bytes(-127, 8, 0, 0, 1), 4))
        assertEquals(24, ReplyHeader.payloadOffset(bytes(-127, 8, 0, -128, 8, 0, 1), 4))
    }
    @Test fun `额外头记录数和尾部载荷必须完整`() {
        assertThrows(IllegalArgumentException::class.java) { ReplyHeader.payloadOffset(bytes(-127, 8, 1, 0, 1), 4) }
        assertThrows(IllegalArgumentException::class.java) { ReplyHeader.payloadOffset(bytes(-128, 8, -1, 1), 4) }
        assertThrows(IllegalArgumentException::class.java) { ReplyHeader.payloadOffset(bytes(-127, 8, 0), 4) }
    }
    @Test fun `真实远端异常交给系统抛出不能解为成功`() {
        assertNull(ReplyHeader.payloadOffset(bytes(-1, 0, 0), 4))
    }
}
