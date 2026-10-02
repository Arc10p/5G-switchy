package io.github.arc10p.fivegswitch

import java.util.concurrent.atomic.AtomicLong

// 新操作可跨线程取消旧清理；释放请求和延迟回调均在同一工作队列执行。
internal class IdleReleaseScheduler(
    private val closeIfIdle: (ignoreClients: Boolean) -> Unit,
    private val afterDelay: (() -> Unit) -> Unit,
    private val enqueue: (() -> Unit) -> Unit,
) {
    private val generation = AtomicLong()

    fun invalidate() { generation.incrementAndGet() }

    fun request() {
        closeIfIdle(false)
        // 任一客户端退出都重新安排宽限释放，避免剩余客户端让 Root 永久驻留。
        val expected = generation.incrementAndGet()
        afterDelay {
            enqueue {
                // 回到工作队列后再检查代次，排队期间开始的新操作同样能取消清理。
                if (expected == generation.get()) closeIfIdle(true)
            }
        }
    }
}
