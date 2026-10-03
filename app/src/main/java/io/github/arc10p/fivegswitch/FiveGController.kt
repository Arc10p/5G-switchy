package io.github.arc10p.fivegswitch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
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
        ROOT_UNAVAILABLE, SERVICE_UNAVAILABLE, PHONE_UNAVAILABLE, DEFAULT_SIM_UNAVAILABLE,
        READ_FAILED, UPDATE_FAILED, UNSUPPORTED_DEVICE,
    }

    data class Result(val state: State, val message: String, val error: Error? = null) {
        val success: Boolean get() = error == null
    }

    private val executor = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
        LinkedBlockingQueue(), { Thread(it, "5G-controller") }).apply { allowCoreThreadTimeOut(true) }
    private val main = Handler(Looper.getMainLooper())
    private val modifying = AtomicBoolean(false)
    private val processes = ShortRootProcess()
    private val contract by lazy { BinderCalls.call("读取系统电话接口元数据", {}) { PhoneContract.resolveSystem() } }
    private var completedAt = 0L
    private var lastCommand = "尚未执行"
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

    fun refresh(context: Context, callback: (Result) -> Unit) =
        execute(context.packageName, null, false, callback)

    fun toggle(context: Context, callback: (Result) -> Unit): Boolean = submit(context, null, callback)

    fun set5GEnabled(context: Context, enabled: Boolean, callback: (Result) -> Unit): Boolean =
        submit(context, enabled, callback)

    private fun submit(context: Context, enabled: Boolean?, callback: (Result) -> Unit): Boolean {
        if (!modifying.compareAndSet(false, true)) return false
        execute(context.packageName, enabled, true, callback)
        return true
    }

    private fun execute(identity: String, enabled: Boolean?, modification: Boolean, callback: (Result) -> Unit) {
        executor.execute {
            // 每次命令使用独立短命 su；没有 RootService、常驻 shell 或额外 ART 进程。
            val commands = NativeRootCommands(processes::run)
            val access = NativeTelephonyAccess({ contract }, commands, identity, AndroidReplyDecoder)
            val outcome = try {
                FiveGBackend(access, { getDefaultDataSim() }, { if (BuildConfig.DEBUG) Log.i("5GSwitch", it) })
                    .run(enabled, toggle = modification && enabled == null)
            } catch (e: Exception) {
                Log.e("5GSwitch", "原生电话调用失败", e)
                Result(State(rootAvailable = commands.rootAvailable),
                    "原生电话调用失败：${e.javaClass.simpleName}: ${e.message}", Error.SERVICE_UNAVAILABLE)
            } finally {
                completedAt = SystemClock.elapsedRealtime()
                lastCommand = commands.lastStatus
                if (modification) modifying.set(false)
            }
            val result = if (outcome.state.rootAvailable == null && commands.rootAvailable != null)
                outcome.copy(state = outcome.state.copy(rootAvailable = commands.rootAvailable)) else outcome
            if (modification) lastOperation = result
            main.post { callback(result) }
        }
    }

    fun memoryReport(callback: (String) -> Unit) {
        executor.execute {
            // 仅采样应用；诊断不执行 su 或任何电话命令。
            val report = buildString {
                appendLine("版本 ${BuildConfig.VERSION_NAME} · ${if (BuildConfig.DEBUG) "Debug" else "Release"}")
                appendLine(MemorySnapshot.describe("本次应用采样", MemorySnapshot.capture()))
                appendLine("执行方式：系统原生 service Binder，无 RootService / app_process")
                appendLine("当前 su/service 命令：${if (processes.active) "执行中" else "无客户端进程"}")
                appendLine("最近一次命令（历史）：$lastCommand")
                if (completedAt != 0L) appendLine("最近一次操作结束距今：${SystemClock.elapsedRealtime() - completedAt} 毫秒")
                appendLine("主界面：软件绘制；控制线程空闲 10 秒后回收。")
                append("PSS 分摊共享页；RSS 包含共享映射。请比较同一界面状态的 PSS、私有页与 Graphics。")
            }
            main.post { callback(report) }
        }
    }
}
