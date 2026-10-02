package io.github.arc10p.fivegswitch

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

// 精确适配已知的两种 Binder 签名，不经 TelephonyManager 转换 LTE_CA 或其他位。
internal class AllowedNetworkTypesWriter(methods: List<Method>) {
    private val baseParameters = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!)
    private val candidates = methods.filter { it.name == "setAllowedNetworkTypesForReason" }
    private val setter = candidates.firstOrNull {
        it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.contentEquals(baseParameters)
    } ?: candidates.firstOrNull {
        it.returnType == Boolean::class.javaPrimitiveType &&
            it.parameterTypes.contentEquals(baseParameters + String::class.java)
    } ?: throw NoSuchMethodException("不支持的电话写入接口：" + candidates.joinToString("; "))

    val signature: String get() = setter.toString()

    fun write(receiver: Any, subId: Int, reason: Int, mask: Long, packageIdentity: String): Boolean {
        val arguments = if (setter.parameterCount == 3) arrayOf<Any>(subId, reason, mask)
            else arrayOf<Any>(subId, reason, mask, packageIdentity)
        return try {
            setter.isAccessible = true
            setter.invoke(receiver, *arguments) as Boolean
        } catch (e: InvocationTargetException) {
            // 保留系统实际异常，不重试写入，避免响应丢失时重复操作。
            throw e.targetException
        }
    }
}
