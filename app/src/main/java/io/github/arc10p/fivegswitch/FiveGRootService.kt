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

class FiveGRootService : RootService() {
    private val controller = object : IFiveGService.Stub() {
        override fun getRootUid(): Int = Process.myUid()
        override fun getRootPid(): Int = Process.myPid()

        override fun checkCompatibility(subId: Int): Boolean = call("checkCompatibility(subId=$subId)") {
            // 在 root 进程核对系统真实方法，不能只验证 getter 就认定 setter 可链接。
            val setter = ITelephony::class.java.getMethod("setAllowedNetworkTypesForReason",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            check(setter.returnType == Boolean::class.javaPrimitiveType) {
                "ITelephony setter 返回类型不兼容：${setter.returnType.name}"
            }
            getUserMask(subId) >= 0L
        }

        override fun getUserMask(subId: Int): Long = call("getAllowedNetworkTypesForReason(subId=$subId, reason=USER)") {
            require(SubscriptionManager.isValidSubscriptionId(subId)) { "无效 subscriptionId：$subId" }
            phone().getAllowedNetworkTypesForReason(subId, TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
        }

        override fun setUserMask(subId: Int, mask: Long): Boolean =
            call("setAllowedNetworkTypesForReason(subId=$subId, reason=USER, mask=$mask)") {
                require(SubscriptionManager.isValidSubscriptionId(subId) && mask >= 0L) { "无效写入参数" }
                phone().setAllowedNetworkTypesForReason(subId,
                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, mask)
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
        BinderCalls.call(operation, { Log.e("5GSwitch", operation, it) }, block)
            .also { Log.i("5GSwitch", "$operation → $it") }
}
