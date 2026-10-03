package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ServiceParcelDumpTest {
    private fun words(text: String) = ByteBuffer.wrap(ServiceParcelDump.decode(text)).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun `单行 Long 回复保持低高字顺序`() {
        val buffer = words("Result: Parcel(00000000 12345678 00100000 '............')")
        assertEquals(0, buffer.int)
        assertEquals(0x0010000012345678L, buffer.long)
    }
    @Test fun `负数最高位保持原始六十四位`() {
        val buffer = words("Result: Parcel(00000000 00000001 80000000 '............')")
        buffer.int
        assertEquals(Long.MIN_VALUE or 1L, buffer.long)
    }
    @Test fun `boolean 原始字正确恢复`() {
        val buffer = words("Result: Parcel(00000000 00000001 '........')")
        assertEquals(0, buffer.int)
        assertEquals(1, buffer.int)
    }
    @Test fun `多行地址只作连续性检查不当成数据`() {
        val buffer = words("Result: Parcel(\n0x00000000: ffffff80 00000004 00000000 00000000 '................'\n" +
            "0x00000010: 00000001 '....'\n)")
        assertEquals(20, buffer.remaining())
        assertEquals(-128, buffer.int)
    }
    @Test fun `ASCII 栏中的十六进制内容不解析`() {
        assertEquals(8, ServiceParcelDump.decode("Result: Parcel(00000000 00000001 'deadbeef')").size)
    }
    @Test fun `空回复和 Parcel NULL 都不能代表成功`() {
        listOf("", "Result: Parcel()", "Result: Parcel(NULL)", "Result: Parcel(Error: -32)").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServiceParcelDump.decode(it) }
        }
    }
    @Test fun `地址断裂拒绝恢复`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServiceParcelDump.decode("Result: Parcel(\n0x00000000: 00000000 '....'\n0x00000008: 00000001 '....'\n)")
        }
    }
    @Test fun `截断十六进制或未知前后文本全部拒绝`() {
        listOf("Result: Parcel(00000000 0000000 '........')", "warning\nResult: Parcel(00000000)",
            "Result: Parcel(00000000)\nextra", "Result: Parcel(00000000 '....)",
            "Result: Parcel(\n00000000\n00000001\n)").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServiceParcelDump.decode(it) }
        }
    }
    @Test fun `Binder 对象和额外行拒绝解析`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServiceParcelDump.decode("Result: Parcel(\n0x00000000: 00000000 '....'\nObject #0\n)")
        }
    }
}
