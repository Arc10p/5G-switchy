package io.github.arc10p.fivegswitch

// 仅在控制器工作线程使用；失败的 su 会话不缓存，重试可以重新申请。
internal class RootShellSession<T>(
    private val isAlive: (T) -> Boolean,
    private val readUid: (T) -> Int,
    private val close: (T) -> Unit,
) {
    private var cached: T? = null
    var rootAvailable: Boolean? = null
        private set

    fun release() {
        cached?.let { close(it) }
        cached = null
        rootAvailable = null
    }

    fun acquire(create: () -> T): T {
        cached?.let {
            if (isAlive(it)) return it
            cached = null
            rootAvailable = null
            close(it)
        }
        rootAvailable = null
        val shell = create()
        try {
            // 以实际 UID 为准，不用 libsu 初始化时的状态标签替代验证。
            val uid = readUid(shell)
            rootAvailable = uid == 0
            check(uid == 0) { "su 会话实际 UID=$uid，未取得 UID 0" }
            cached = shell
            return shell
        } catch (e: Exception) {
            try { close(shell) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }
}
