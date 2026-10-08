package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test

class NativeRootCommandsTest {
    @Test fun `UID 零和进程成功不替代 Binder 回复验证`() {
        val commands = NativeRootCommands { CommandResult(0, "0\n12345\nResult: Parcel(NULL)", "") }
        val output = commands.call(listOf("/system/bin/service", "call", "phone", "902"))
        assertEquals(true, commands.rootAvailable)
        assertThrows(IllegalArgumentException::class.java) { ServiceParcelDump.decode(output) }
    }
    @Test fun `普通 UID 明确拒绝执行`() {
        val commands = NativeRootCommands { CommandResult(77, "2000\n12345\n", "") }
        assertThrows(IllegalStateException::class.java) { commands.verifyUid() }
        assertEquals(false, commands.rootAvailable)
    }
    @Test fun `未知授权结果不误报用户没有 Root`() {
        val commands = NativeRootCommands { CommandResult(1, "", "Permission denied") }
        assertThrows(IllegalStateException::class.java) { commands.verifyUid() }
        assertNull(commands.rootAvailable)
    }
    @Test fun `Root 退出非零或有 stderr 不能报告调用成功`() {
        listOf(CommandResult(1, "0\n12345\n", ""), CommandResult(0, "0\n12345\n", "tool failed")).forEach { reply ->
            assertThrows(IllegalStateException::class.java) { NativeRootCommands { reply }.verifyUid() }
        }
    }
    @Test fun `命令参数以单引号隔离并保留嵌入引号`() {
        assertEquals("'a'\\''b; c'", NativeRootCommands.quote("a'b; c"))
    }
    @Test fun `超时不重试或重放任何命令`() {
        var count = 0
        val commands = NativeRootCommands { count++; error("命令超时") }
        assertThrows(IllegalStateException::class.java) { commands.call(listOf("写入")) }
        assertEquals(1, count)
        assertTrue(commands.lastStatus.contains("结果未知"))
    }
}
