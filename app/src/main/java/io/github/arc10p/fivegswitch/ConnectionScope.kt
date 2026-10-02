package io.github.arc10p.fivegswitch

// 客户端计数可跨线程调用；连接的使用与关闭只在同一工作线程执行。
internal class ConnectionScope<T>(
    private val isAlive: (T) -> Boolean,
    private val close: (T) -> Unit,
) {
    private var clients = 0
    private var operations = 0
    private var cached: T? = null

    @Synchronized fun retainClient() { clients++ }

    @Synchronized fun releaseClient() {
        check(clients > 0) { "客户端引用已释放" }
        clients--
    }

    @Synchronized fun beginOperation() { operations++ }

    @Synchronized fun endOperation() {
        check(operations > 0) { "操作引用已释放" }
        operations--
    }

    fun connection(create: () -> T): T {
        cached?.let {
            if (isAlive(it)) return it
            // 必须确认旧连接已关闭，才能建立新连接。
            close(it)
            cached = null
        }
        return create().also { cached = it }
    }

    val currentConnection: T? get() = cached

    fun closeIfIdle(ignoreClients: Boolean = false): Boolean {
        val idle = synchronized(this) { operations == 0 && (ignoreClients || clients == 0) }
        if (idle) cached?.let {
            // 关闭失败时保留记录，下一次连接会先完成清理。
            close(it)
            cached = null
        }
        return idle
    }
}
