package io.github.arc10p.fivegswitch

import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.android.internal.telephony.ITelephony
import com.topjohnwu.superuser.ipc.RootService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

class FiveGRootService : RootService() {
    private var hiddenApiStatus = "电话隐藏接口豁免尚未设置"
    private val writer by lazy {
        AllowedNetworkTypesWriter(HiddenApiBypass.getDeclaredMethods(ITelephony::class.java)
            .filterIsInstance<Method>())
    }

    override fun onCreate() {
        super.onCreate()
        // 只在独立 root 进程开放电话接口，普通应用进程不访问隐藏 API。
        hiddenApiStatus = try {
            if (HiddenApiBypass.addHiddenApiExemptions("Lcom/android/internal/telephony/ITelephony"))
                "电话隐藏接口豁免已设置" else "电话隐藏接口豁免设置返回 false"
        } catch (e: Throwable) {
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            "电话隐藏接口豁免设置失败：${e.javaClass.simpleName}: ${e.message}"
        }
        if (BuildConfig.DEBUG) Log.i("5GSwitch", hiddenApiStatus)
    }

    private val controller = object : IFiveGService.Stub() {
        override fun getRootUid(): Int = Process.myUid()
        override fun getRootPid(): Int = Process.myPid()
        override fun getMemoryStats(): IntArray = call("getMemoryStats()") { MemorySnapshot.capture() }

        override fun checkCompatibility(subId: Int): Boolean = call("checkCompatibility(subId=$subId)") {
            // 与 LuckyTool 使用相同的直接调用链；刷新只读取，写入结果在实际切换时检查。
            getUserMask(subId) >= 0L
        }

        override fun getUserMask(subId: Int): Long = call("getAllowedNetworkTypesForReason(subId=$subId, reason=USER)") {
            require(SubscriptionManager.isValidSubscriptionId(subId)) { "无效 subscriptionId：$subId" }
            phone().getAllowedNetworkTypesForReason(subId, TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
        }

        override fun setUserMask(subId: Int, mask: Long): Boolean =
            call("setAllowedNetworkTypesForReason(subId=$subId, reason=USER, mask=$mask)") {
                require(SubscriptionManager.isValidSubscriptionId(subId) && mask >= 0L) { "无效写入参数" }
                val receiver = phone()
                // 四参数接口传入本应用自己的包身份，不冒用系统设置或其他应用。
                val identity = packageName
                if (BuildConfig.DEBUG) Log.i("5GSwitch", "${writer.signature} packageIdentity=$identity subId=$subId mask=$mask")
                writer.write(receiver, subId, TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, mask, identity)
            }
    }

    override fun onBind(intent: Intent): IBinder = controller

    private fun phone(): ITelephony {
        check(Process.myUid() == 0) { "电话隐藏 API 只能在 Root 进程调用" }
        val binder = ServiceManager.getService("phone") ?: error("phone Binder 不可用")
        check(binder.isBinderAlive) { "phone Binder 已断开" }
        return ITelephony.Stub.asInterface(binder) ?: error("ITelephony 不可用")
    }

    private inline fun <T> call(operation: String, block: () -> T): T =
        BinderCalls.call(operation, { Log.e("5GSwitch", operation, it) }) {
            try {
                block()
            } catch (e: NoSuchMethodError) {
                // 只有真实调用无法链接时才列出接口，不能把反射预检作为开关门槛。
                throw IllegalStateException("${e.javaClass.simpleName}: ${e.message}\n" +
                    "$hiddenApiStatus\n系统电话接口：${networkTypeSignatures()}", e)
            }
        }
            .also { if (BuildConfig.DEBUG) Log.i("5GSwitch", "$operation → $it") }

    private fun networkTypeSignatures(): String = try {
        HiddenApiBypass.getDeclaredMethods(ITelephony::class.java).filterIsInstance<Method>()
            .filter { it.name == "getAllowedNetworkTypesForReason" || it.name == "setAllowedNetworkTypesForReason" }
            .joinToString("; ") { method ->
                "${method.returnType.simpleName} ${method.name}(" +
                    method.parameterTypes.joinToString(",") { it.simpleName } + ")"
            }.ifEmpty { "未枚举到对应方法" }
    } catch (e: Throwable) {
        if (e is VirtualMachineError || e is ThreadDeath) throw e
        "枚举失败：${e.javaClass.simpleName}: ${e.message}"
    }
}
