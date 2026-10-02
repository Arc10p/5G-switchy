package io.github.arc10p.fivegswitch

internal object BinderCalls {
    inline fun <T> call(operation: String, onFailure: (Throwable) -> Unit, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        // 非致命链接错误须转成 Parcel 支持的异常，不能逃出 Binder 回调杀死进程。
        if (e is VirtualMachineError || e is ThreadDeath) throw e
        onFailure(e)
        throw IllegalStateException("$operation: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
