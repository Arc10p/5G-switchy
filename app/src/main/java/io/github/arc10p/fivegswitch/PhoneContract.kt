package io.github.arc10p.fivegswitch

import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// 只读取当前系统元数据；事务编号不得写死，也不尝试扫描编号。
internal data class PhoneContract(
    val getterCode: Int,
    val setterCode: Int,
    val setterNeedsIdentity: Boolean,
    val signature: String,
) {
    companion object {
        private const val GETTER = "getAllowedNetworkTypesForReason"
        private const val SETTER = "setAllowedNetworkTypesForReason"

        fun from(methods: List<Method>, transaction: (String) -> Int): PhoneContract {
            val intType = Int::class.javaPrimitiveType!!
            val longType = Long::class.javaPrimitiveType!!
            val getters = methods.filter { it.name == GETTER }
            val getter = getters.singleOrNull { it.returnType == longType &&
                it.parameterTypes.contentEquals(arrayOf(intType, intType)) }
                ?: throw NoSuchMethodException("不支持或含混的电话读取接口：${getters.joinToString()}")
            val setters = methods.filter { it.name == SETTER }
            val base = arrayOf(intType, intType, longType)
            val matches = setters.filter { it.returnType == Boolean::class.javaPrimitiveType &&
                (it.parameterTypes.contentEquals(base) ||
                    it.parameterTypes.contentEquals(base + String::class.java)) }
            val setter = matches.singleOrNull()
                ?: throw NoSuchMethodException("不支持或含混的电话写入接口：${setters.joinToString()}")
            val getCode = transaction("TRANSACTION_$GETTER")
            val setCode = transaction("TRANSACTION_$SETTER")
            require(getCode in 1..0x00ffffff && setCode in 1..0x00ffffff && getCode != setCode) {
                "系统电话事务编号无效或重复：get=$getCode set=$setCode"
            }
            return PhoneContract(getCode, setCode, setter.parameterCount == 4, "$getter; $setter")
        }

        fun resolveSystem(): PhoneContract {
            check(HiddenApiBypass.addHiddenApiExemptions("Lcom/android/internal/telephony/ITelephony")) {
                "无法读取系统电话接口元数据"
            }
            // 从引导类加载器读取框架，不使用应用自带的接口占位类。
            val loader = String::class.java.classLoader
            val phone = Class.forName("com.android.internal.telephony.ITelephony", false, loader)
            val stub = Class.forName("com.android.internal.telephony.ITelephony\$Stub", false, loader)
            // 精确读取所需成员，避免枚举整套 ITelephony 并解析数百个无关方法的类型。
            val intType = Int::class.javaPrimitiveType!!
            val base = arrayOf(intType, intType, Long::class.javaPrimitiveType!!)
            val methods = mutableListOf(phone.getDeclaredMethod(GETTER, intType, intType))
            for (parameters in listOf(base, base + String::class.java)) {
                try { methods += phone.getDeclaredMethod(SETTER, *parameters) }
                catch (_: NoSuchMethodException) { /* 已知的另一种签名由下一项检查。 */ }
            }
            return from(methods) { name ->
                val field = stub.getDeclaredField(name)
                check(field.type == Int::class.javaPrimitiveType && Modifier.isStatic(field.modifiers)) {
                    "系统电话事务常量类型无效：$name"
                }
                field.isAccessible = true
                field.getInt(null).also { code ->
                    require(code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION)
                }
            }
        }
    }
}
