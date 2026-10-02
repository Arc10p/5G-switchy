package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RootShellSessionTest {
    private class Session(val uid: Int, var alive: Boolean = true, var closed: Boolean = false)

    private fun sessions() = RootShellSession<Session>(
        { it.alive }, { it.uid }, { it.closed = true; it.alive = false },
    )

    @Test fun `已验证的活会话复用时不再次发起授权`() {
        val sessions = sessions()
        val first = sessions.acquire { Session(0) }
        assertSame(first, sessions.acquire { error("不能重复启动 su") })
        assertEquals(true, sessions.rootAvailable)
        assertFalse(first.closed)
    }

    @Test fun `实际普通 UID 不得执行启动任务且授权后可重试`() {
        val sessions = sessions()
        val denied = Session(10234)
        val error = assertThrows(IllegalStateException::class.java) { sessions.acquire { denied } }
        assertTrue(error.message!!.contains("UID=10234"))
        assertTrue(denied.closed)
        assertEquals(false, sessions.rootAvailable)
        assertEquals(0, sessions.acquire { Session(0) }.uid)
        assertEquals(true, sessions.rootAvailable)
    }

    @Test fun `su 初始化异常不污染下一次重试也不等于未授权`() {
        val sessions = sessions()
        val original = IOException("Shell check timeout")
        assertSame(original, assertThrows(IOException::class.java) { sessions.acquire { throw original } })
        assertNull(sessions.rootAvailable)
        assertEquals(0, sessions.acquire { Session(0) }.uid)
        assertEquals(true, sessions.rootAvailable)
    }

    @Test fun `UID 验证失败保留原始错误并释放坏会话`() {
        val original = IOException("UID 输出不可解析")
        val cleanup = IOException("关闭失败")
        var closed = false
        val sessions = RootShellSession<Session>({ it.alive }, { throw original }, {
            closed = true
            throw cleanup
        })
        val error = assertThrows(IOException::class.java) { sessions.acquire { Session(0) } }
        assertSame(original, error)
        assertArrayEquals(arrayOf(cleanup), error.suppressed)
        assertTrue(closed)
        assertNull(sessions.rootAvailable)
    }

    @Test fun `旧 root 会话死亡后重新验证不能沿用之前授权结果`() {
        val sessions = sessions()
        val first = sessions.acquire { Session(0) }
        first.alive = false
        assertThrows(IllegalStateException::class.java) { sessions.acquire { Session(10345) } }
        assertTrue(first.closed)
        assertEquals(false, sessions.rootAvailable)
    }
}
