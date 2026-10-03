package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ShortRootProcessTest {
    private class FakeProcess(private val text: String, private val timeout: Boolean = false) : Process() {
        var alive = true
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(text.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { alive = false; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (this.timeout && !destroyed) return false
            alive = false
            return true
        }
        override fun exitValue(): Int = 0
        override fun isAlive(): Boolean = alive
        override fun destroy() { destroyed = true; alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun `输出排空后命令退出不保留客户端`() {
        val fake = FakeProcess("0\n12345\n")
        val runner = ShortRootProcess({ fake })
        assertEquals("0\n12345\n", runner.run("测试").stdout)
        assertFalse(runner.active)
    }
    @Test fun `命令超时会结束客户端且不重新启动`() {
        val fake = FakeProcess("", timeout = true)
        var starts = 0
        val runner = ShortRootProcess({ starts++; fake }, 0)
        assertThrows(IllegalStateException::class.java) { runner.run("测试写入") }
        assertEquals(1, starts)
        assertTrue(fake.destroyed)
        assertFalse(runner.active)
    }
    @Test fun `过量输出拒绝截断伪成功并释放客户端`() {
        val runner = ShortRootProcess({ FakeProcess("a".repeat(32769)) })
        assertThrows(Exception::class.java) { runner.run("测试") }
        assertFalse(runner.active)
    }
}
