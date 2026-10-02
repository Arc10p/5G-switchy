package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test

class RootConnectionTest {
    private class Connection { var alive = true }

    @Test fun `界面可见时刷新与切换复用连接不触发退出`() {
        var closed = 0
        val scope = ConnectionScope<Connection>({ it.alive }, { it.alive = false; closed++ })
        scope.retainClient()
        scope.beginOperation()
        val first = scope.connection { Connection() }
        scope.endOperation()
        scope.closeIfIdle()
        assertEquals(0, closed)
        scope.beginOperation()
        assertSame(first, scope.connection { error("不应重新绑定即将退出的进程") })
        scope.endOperation()
        scope.closeIfIdle()
        assertEquals(0, closed)
        scope.releaseClient()
        scope.closeIfIdle()
        assertEquals(1, closed)
    }

    @Test fun `界面与磁贴的连接保持到最后一个客户端退出`() {
        var closed = 0
        val scope = ConnectionScope<Connection>({ it.alive }, { closed++ })
        scope.retainClient()
        scope.retainClient()
        scope.beginOperation()
        scope.connection { Connection() }
        scope.endOperation()
        scope.releaseClient()
        scope.closeIfIdle()
        assertEquals(0, closed)
        scope.releaseClient()
        scope.closeIfIdle()
        assertEquals(1, closed)
    }

    @Test fun `磁贴关闭后仍须等所有已排队操作完成才解绑`() {
        var closed = 0
        val scope = ConnectionScope<Connection>({ it.alive }, { closed++ })
        scope.retainClient()
        scope.beginOperation()
        scope.beginOperation()
        val first = scope.connection { Connection() }
        scope.releaseClient()
        scope.endOperation()
        scope.closeIfIdle()
        assertEquals(0, closed)
        assertSame(first, scope.connection { error("仍有操作需要同一连接") })
        scope.endOperation()
        scope.closeIfIdle()
        assertEquals(1, closed)
    }

    @Test fun `死亡连接会清理后重新建立`() {
        var closed = 0
        val scope = ConnectionScope<Connection>({ it.alive }, { closed++ })
        val first = scope.connection { Connection() }
        first.alive = false
        val second = scope.connection { Connection() }
        assertNotSame(first, second)
        assertEquals(1, closed)
    }

    @Test fun `旧进程尚未退出时禁止建立新连接`() {
        var allowClose = false
        var created = 0
        val scope = ConnectionScope<Connection>({ it.alive }, {
            it.alive = false
            check(allowClose) { "旧进程仍在退出" }
        })
        scope.connection { created++; Connection() }
        assertThrows(IllegalStateException::class.java) { scope.closeIfIdle() }
        assertThrows(IllegalStateException::class.java) { scope.connection { created++; Connection() } }
        assertEquals(1, created)
        allowClose = true
        scope.connection { created++; Connection() }
        assertEquals(2, created)
    }

    @Test fun `连接回调未到时保留同一待连接记录供重试`() {
        var bound = false
        var created = 0
        var closed = 0
        val scope = ConnectionScope<Connection>({ it.alive }, {
            check(bound) { "启动尚未确认，不能创建与迟到回调竞争的新连接" }
            it.alive = false
            closed++
        })
        val pending = scope.connection { created++; Connection() }
        assertThrows(IllegalStateException::class.java) { scope.closeIfIdle() }
        assertSame(pending, scope.connection { created++; Connection() })
        assertEquals(1, created)
        bound = true
        scope.closeIfIdle()
        assertEquals(1, closed)
    }

    @Test fun `setter 链接错误转换成可传递诊断而非杀死 root 进程`() {
        val original = NoSuchMethodError("setAllowedNetworkTypesForReason")
        var logged: Throwable? = null
        val error = assertThrows(IllegalStateException::class.java) {
            BinderCalls.call("setAllowedNetworkTypesForReason(subId=1, reason=USER, mask=392191)",
                { logged = it }) { throw original }
        }
        assertSame(original, logged)
        assertSame(original, error.cause)
        assertTrue(error.message!!.contains("NoSuchMethodError"))
        assertTrue(error.message!!.contains("mask=392191"))
    }

    @Test fun `致命虚拟机错误不能作为普通电话异常处理`() {
        val original = OutOfMemoryError("测试错误")
        val error = assertThrows(OutOfMemoryError::class.java) {
            BinderCalls.call("测试", { fail("不应吞掉致命错误") }) { throw original }
        }
        assertSame(original, error)
    }
}
