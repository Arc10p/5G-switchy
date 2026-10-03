package io.github.arc10p.fivegswitch

import android.os.Parcel
import android.telephony.TelephonyManager

internal interface ReplyDecoder {
    fun longValue(bytes: ByteArray): Long
    fun booleanValue(bytes: ByteArray): Boolean
}

internal object AndroidReplyDecoder : ReplyDecoder {
    private fun <T> decode(bytes: ByteArray, payloadSize: Int, read: (Parcel) -> T): T {
        require(bytes.size >= 4 && bytes.size % 4 == 0) { "电话回复缺少异常头或未按四字节对齐" }
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            // 使用设备自身实现处理异常、StrictMode 和 AppOps 回复头。
            parcel.readException()
            require(parcel.dataAvail() == payloadSize) { "电话回复长度不符：剩余 ${parcel.dataAvail()}，预期 $payloadSize" }
            read(parcel).also { require(parcel.dataAvail() == 0) }
        } finally { parcel.recycle() }
    }

    override fun longValue(bytes: ByteArray): Long = decode(bytes, 8) { it.readLong() }
    override fun booleanValue(bytes: ByteArray): Boolean = decode(bytes, 4) {
        val value = it.readInt()
        require(value == 0 || value == 1) { "电话回复不是 boolean：$value" }
        value == 1
    }
}

internal class NativeTelephonyAccess(
    private val contract: () -> PhoneContract,
    private val commands: NativeRootCommands,
    private val packageIdentity: String,
    private val decoder: ReplyDecoder,
) : TelephonyAccess {
    override fun getRootUid(): Int = commands.verifyUid()
    override fun checkCompatibility(subId: Int): Boolean { contract(); return true }

    override fun getUserMask(subId: Int): Long {
        require(subId >= 0)
        val args = listOf("/system/bin/service", "call", "phone", contract().getterCode.toString(),
            "i32", subId.toString(), "i32", TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER.toString())
        return decoder.longValue(ServiceParcelDump.decode(commands.call(args)))
    }

    override fun setUserMask(subId: Int, mask: Long): Boolean {
        require(subId >= 0)
        val api = contract()
        val args = mutableListOf("/system/bin/service", "call", "phone", api.setterCode.toString(),
            "i32", subId.toString(), "i32", TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER.toString(),
            "i64", mask.toString())
        if (api.setterNeedsIdentity) args += listOf("s16", packageIdentity)
        // 每次调用只执行一个 setter，响应丢失或解析失败时不重写。
        return decoder.booleanValue(ServiceParcelDump.decode(commands.call(args)))
    }
}
