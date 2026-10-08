package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeTelephonyAccessTest {
    private val decoder = object : ReplyDecoder {
        override fun longValue(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(4)
        override fun booleanValue(bytes: ByteArray): Boolean =
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == 1
    }

    @Test fun `四参数原生请求发送动态编号及完整有符号 Long 和身份`() {
        var command = ""
        val access = NativeTelephonyAccess({ PhoneContract(731, 912, true, "设备签名") }, NativeRootCommands {
            command = it; CommandResult(0, "0\n31337\nResult: Parcel(00000000 00000001 '........')", "")
        }, "io.github.arc10p.fivegswitch", decoder)
        val mask = Long.MIN_VALUE or (1L shl 40) or (1L shl 18)
        assertTrue(access.setUserMask(83, mask))
        assertTrue(command.contains("'phone' '912' 'i32' '83' 'i32' '0' 'i64' '${mask}' 's16' 'io.github.arc10p.fivegswitch'"))
    }
    @Test fun `三参数原生请求没有多加字符串`() {
        var command = ""
        val access = NativeTelephonyAccess({ PhoneContract(731, 912, false, "设备签名") }, NativeRootCommands {
            command = it; CommandResult(0, "0\n31337\nResult: Parcel(00000000 00000001 '........')", "")
        }, "测试身份", decoder)
        assertTrue(access.setUserMask(27, 392191))
        assertFalse(command.contains("'s16'"))
    }
    @Test fun `读取使用相同动态编号并恢复完整 Long`() {
        var command = ""
        val access = NativeTelephonyAccess({ PhoneContract(731, 912, true, "设备签名") }, NativeRootCommands {
            command = it; CommandResult(0, "0\n31337\nResult: Parcel(00000000 00000001 00000100 '............')", "")
        }, "测试身份", decoder)
        assertEquals((1L shl 40) or 1, access.getUserMask(83))
        assertTrue(command.contains("'phone' '731' 'i32' '83' 'i32' '0'"))
    }
    @Test fun `写入回复截断只执行一次并报告未知结果`() {
        var count = 0
        val access = NativeTelephonyAccess({ PhoneContract(731, 912, true, "设备签名") }, NativeRootCommands {
            count++; CommandResult(0, "0\n31337\nResult: Parcel(00000000 0000000)", "")
        }, "测试身份", decoder)
        assertThrows(IllegalArgumentException::class.java) { access.setUserMask(83, 392191) }
        assertEquals(1, count)
    }
}
