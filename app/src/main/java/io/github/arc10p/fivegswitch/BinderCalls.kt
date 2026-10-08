package io.github.arc10p.fivegswitch

internal object BinderCalls {
    inline fun <T> call(operation: String, onFailure: (Throwable) -> Unit, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        // 元数据访问的非致命链接错误转为可见诊断，致命虚拟机错误仍向外传播。
        if (e is VirtualMachineError || e is ThreadDeath) throw e
        onFailure(e)
        throw IllegalStateException("$operation: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
