package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test

class IdleReleaseSchedulerTest {
    private class Fixture {
        var closed = 0
        val scope = ConnectionScope<Any>({ true }, { closed++ })
        val delayed = ArrayDeque<() -> Unit>()
        val queued = ArrayDeque<() -> Unit>()
        val idle = IdleReleaseScheduler(
            { force -> scope.closeIfIdle(force) },
            { delayed.addLast(it) },
            { queued.addLast(it) },
        )

        fun beginOperation() {
            idle.invalidate()
            scope.beginOperation()
            scope.connection { Any() }
        }

        fun finishOperation() { scope.endOperation(); idle.request() }

        fun releaseClient() {
            scope.releaseClient()
            idle.invalidate()
            queued.addLast { idle.request() }
        }

        fun fireTimers() {
            val tasks = delayed.toList()
            delayed.clear()
            tasks.forEach { it() }
        }

        fun runWorker() { while (queued.isNotEmpty()) queued.removeFirst()() }
    }

    @Test fun `两个客户端中一个在宽限内退出仍会释放 Root`() {
        val f = Fixture()
        f.scope.retainClient()
        f.scope.retainClient()
        f.beginOperation()
        f.finishOperation()
        f.releaseClient()
        f.runWorker()
        assertEquals(0, f.closed)
        f.fireTimers()
        f.runWorker()
        assertEquals(1, f.closed)
        assertNull(f.scope.currentConnection)
        f.beginOperation()
        assertNotNull(f.scope.currentConnection)
    }

    @Test fun `定时器已触发但工作尚未执行时新操作也能取消旧清理`() {
        val f = Fixture()
        f.scope.retainClient()
        f.beginOperation()
        f.finishOperation()
        f.fireTimers()
        f.beginOperation()
        f.runWorker()
        assertEquals(0, f.closed)
        assertNotNull(f.scope.currentConnection)
        f.finishOperation()
        f.fireTimers()
        f.runWorker()
        assertEquals(1, f.closed)
    }

    @Test fun `客户端退出重排定时器也不能释放排队操作需要的连接`() {
        val f = Fixture()
        f.scope.retainClient()
        f.scope.retainClient()
        f.beginOperation()
        f.beginOperation()
        f.finishOperation()
        f.releaseClient()
        f.runWorker()
        f.fireTimers()
        f.runWorker()
        assertEquals(0, f.closed)
        assertNotNull(f.scope.currentConnection)
        f.finishOperation()
        f.fireTimers()
        f.runWorker()
        assertEquals(1, f.closed)
    }
}
