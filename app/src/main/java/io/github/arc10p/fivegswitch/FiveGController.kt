package io.github.arc10p.fivegswitch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SubscriptionManager
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object FiveGController {
    data class DataSim(val subId: Int, val slotIndex: Int)

    data class State(
        val rootAvailable: Boolean? = null,
        val sim: DataSim? = null,
        val mask: Long? = null,
    ) {
        val enabled: Boolean? get() = mask?.let { it and NetworkTypes.NR != 0L }
    }

    enum class Error {
        ROOT_UNAVAILABLE, PHONE_UNAVAILABLE, DEFAULT_SIM_UNAVAILABLE,
        READ_FAILED, UPDATE_FAILED, UNSUPPORTED_DEVICE,
    }

    data class Result(val state: State, val message: String, val error: Error? = null) {
        val success: Boolean get() = error == null
    }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "5G-controller") }
    private val main = Handler(Looper.getMainLooper())
    private val modifying = AtomicBoolean(false)
    @Volatile var lastOperation: Result? = null
        private set

    fun getDefaultDataSubId(): Int = SubscriptionManager.getDefaultDataSubscriptionId()

    private fun getDefaultDataSim(): DataSim {
        val subId = getDefaultDataSubId()
        check(SubscriptionManager.isValidSubscriptionId(subId)) { "没有有效默认数据 subscriptionId：$subId" }
        val slot = SubscriptionManager.getSlotIndex(subId)
        check(slot >= 0) { "无法确定 subId=$subId 的 SIM 槽位：$slot" }
        return DataSim(subId, slot)
    }

    fun refresh(context: Context, callback: (Result) -> Unit) {
        execute(context.applicationContext, null, false, callback)
    }

    fun toggle(context: Context, callback: (Result) -> Unit): Boolean = submit(context, null, callback)

    fun set5GEnabled(context: Context, enabled: Boolean, callback: (Result) -> Unit): Boolean =
        submit(context, enabled, callback)

    private fun submit(context: Context, enabled: Boolean?, callback: (Result) -> Unit): Boolean {
        // 界面和磁贴共享门闩，执行中的重复点击不再排队反转。
        if (!modifying.compareAndSet(false, true)) return false
        execute(context.applicationContext, enabled, true, callback)
        return true
    }

    private fun execute(context: Context, enabled: Boolean?, modification: Boolean, callback: (Result) -> Unit) {
        executor.execute {
            val binding = RootBinding(context)
            val result = try {
                val service = binding.connect()
                val access = object : TelephonyAccess {
                    override fun getRootUid() = service.rootUid
                    override fun checkCompatibility(subId: Int) = service.checkCompatibility(subId)
                    override fun getUserMask(subId: Int) = service.getUserMask(subId)
                    override fun setUserMask(subId: Int, mask: Long) = service.setUserMask(subId, mask)
                }
                FiveGBackend(access, { getDefaultDataSim() }, { Log.i("5GSwitch", it) })
                    .run(enabled, toggle = modification && enabled == null)
            } catch (e: Exception) {
                val message = "RootService 无法连接，请检查 Root 授权：${e.javaClass.simpleName}: ${e.message}"
                Log.e("5GSwitch", message, e)
                Result(State(rootAvailable = false), message, Error.ROOT_UNAVAILABLE)
            } finally {
                binding.release()
                if (modification) modifying.set(false)
            }
            if (modification) lastOperation = result
            main.post { callback(result) }
        }
    }

    private class RootBinding(context: Context) : ServiceConnection {
        private val intent = Intent(context, FiveGRootService::class.java)
        private val connected = CountDownLatch(1)
        private val released = AtomicBoolean(false)
        @Volatile private var service: IFiveGService? = null
        @Volatile private var failure: String? = null

        fun connect(): IFiveGService {
            // libsu 绑定必须在主线程；只有等待与电话 Binder 调用在工作线程。
            main.post {
                if (!released.get()) try {
                    RootService.bind(intent, this)
                } catch (e: Exception) {
                    failure = "${e.javaClass.simpleName}: ${e.message}"
                    connected.countDown()
                }
            }
            check(connected.await(60, TimeUnit.SECONDS)) { "等待 Root 授权或 RootService 连接超时（60 秒）" }
            return service ?: error(failure ?: "RootService 未返回 Binder")
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (released.get()) {
                unbind()
                return
            }
            service = IFiveGService.Stub.asInterface(binder)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            failure = "RootService Binder 已断开"
            connected.countDown()
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)

        override fun onNullBinding(name: ComponentName) {
            failure = "RootService 返回了空 Binder"
            connected.countDown()
        }

        fun release() {
            released.set(true)
            main.post { unbind() }
        }

        private fun unbind() {
            try {
                RootService.unbind(this)
            } catch (e: Exception) {
                Log.w("5GSwitch", "RootService 解绑：${e.message}")
            }
            service = null
        }
    }
}

internal interface TelephonyAccess {
    fun getRootUid(): Int
    fun checkCompatibility(subId: Int): Boolean
    fun getUserMask(subId: Int): Long
    fun setUserMask(subId: Int, mask: Long): Boolean
}

internal class FiveGBackend(
    private val telephony: TelephonyAccess,
    private val defaultSim: () -> FiveGController.DataSim,
    private val log: (String) -> Unit = {},
) {
    private class Failure(val kind: FiveGController.Error, override val message: String) : Exception(message)
    private var state = FiveGController.State()

    @Synchronized
    fun run(enabled: Boolean? = null, toggle: Boolean = false): FiveGController.Result {
        state = FiveGController.State()
        return try {
            val uid = rpc(FiveGController.Error.ROOT_UNAVAILABLE, "getRootUid()") { telephony.getRootUid() }
            state = state.copy(rootAvailable = uid == 0)
            if (uid != 0) throw Failure(FiveGController.Error.ROOT_UNAVAILABLE, "RootService UID=$uid，未取得 Root 权限。")
            readCurrent()
            if (enabled != null || toggle) update(enabled ?: !state.enabled!!)
            FiveGController.Result(state, if (enabled != null || toggle)
                "操作成功，已读回完整 USER 位掩码。" else "已读取系统 USER 网络配置。")
        } catch (e: Failure) {
            if (e.kind == FiveGController.Error.ROOT_UNAVAILABLE) state = state.copy(rootAvailable = false)
            log("error=${e.kind}\n${e.message}")
            FiveGController.Result(state, e.message, e.kind)
        } catch (e: Exception) {
            val message = "读取电话配置失败：${e.javaClass.simpleName}: ${e.message}"
            log(message)
            FiveGController.Result(state, message, FiveGController.Error.READ_FAILED)
        }
    }

    private fun sim(): FiveGController.DataSim = try {
        defaultSim().also { check(it.subId >= 0 && it.slotIndex >= 0) }
    } catch (e: Exception) {
        state = state.copy(sim = null, mask = null)
        throw Failure(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE, "无法确定默认数据 SIM：${e.message}")
    }

    private fun readCurrent() {
        val currentSim = sim()
        state = state.copy(sim = currentSim, mask = null)
        val compatible = rpc(FiveGController.Error.PHONE_UNAVAILABLE,
            "checkCompatibility(subId=${currentSim.subId})") { telephony.checkCompatibility(currentSim.subId) }
        if (!compatible) throw Failure(FiveGController.Error.UNSUPPORTED_DEVICE,
            "电话接口未返回有效 USER 配置：subId=${currentSim.subId}。")
        val mask = rpc(FiveGController.Error.READ_FAILED,
            "getAllowedNetworkTypesForReason(subId=${currentSim.subId}, reason=USER)") {
            telephony.getUserMask(currentSim.subId)
        }
        if (mask < 0) throw Failure(FiveGController.Error.READ_FAILED, "无效 USER mask=$mask，subId=${currentSim.subId}。")
        if (sim() != currentSim) throw Failure(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE,
            "读取期间默认数据 SIM 已改变，请重试。")
        state = state.copy(mask = mask)
        log("subId=${currentSim.subId} slotIndex=${currentSim.slotIndex} currentMask=$mask")
    }

    private fun update(enabled: Boolean) {
        val originalSim = state.sim!!
        val current = state.mask!!
        val target = NetworkTypes.with5G(current, enabled)
        log("subId=${originalSim.subId} currentMask=$current targetMask=$target")
        if (sim() != originalSim) {
            readCurrent()
            throw Failure(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE, "默认数据 SIM 已改变，本次未写入，请重试。")
        }
        if (current == target) return
        val operation = "setAllowedNetworkTypesForReason(subId=${originalSim.subId}, reason=USER, mask=$target)"
        val writeFailure = try {
            if (rpc(FiveGController.Error.UPDATE_FAILED, operation) { telephony.setUserMask(originalSim.subId, target) }) null
            else Failure(FiveGController.Error.UPDATE_FAILED, "$operation 返回 false。")
        } catch (e: Failure) { e }
        // setter 的结果与读回结果分别检查，错误不能伪装成已关闭。
        try {
            readCurrent()
        } catch (e: Failure) {
            throw Failure(writeFailure?.kind ?: e.kind,
                "${writeFailure?.message ?: "$operation 写入后无法确认状态。"}\n${e.message}")
        }
        if (writeFailure != null) throw writeFailure
        if (state.sim != originalSim) throw Failure(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE,
            "$operation 执行期间默认数据 SIM 已改变，显示的是新卡状态，请重试。")
        if (state.mask != target) throw Failure(FiveGController.Error.UPDATE_FAILED,
            "$operation 写入后配置不一致：targetMask=$target actualMask=${state.mask}。")
    }

    private inline fun <T> rpc(kind: FiveGController.Error, operation: String, block: () -> T): T = try {
        block().also { log("$operation → $it") }
    } catch (e: Exception) {
        throw Failure(kind, "$operation 失败：${e.javaClass.simpleName}: ${e.message}")
    }
}
