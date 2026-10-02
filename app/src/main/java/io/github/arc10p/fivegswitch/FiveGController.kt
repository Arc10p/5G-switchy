package io.github.arc10p.fivegswitch

import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object FiveGController {
    data class DataSim(val subId: Int, val slotIndex: Int)

    data class State(
        val rootAvailable: Boolean? = null,
        val sim: DataSim? = null,
        val mask: Long? = null,
    ) {
        val enabled: Boolean? get() = mask?.let { it and PhoneCommands.NR != 0L }
    }

    enum class Error {
        ROOT_UNAVAILABLE, PHONE_UNAVAILABLE, DEFAULT_SIM_UNAVAILABLE,
        READ_FAILED, UPDATE_FAILED, NON_ZERO_EXIT_CODE, UNSUPPORTED_SYNTAX,
    }

    data class Result(val state: State, val message: String, val error: Error? = null) {
        val success: Boolean get() = error == null
    }

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "5G-controller")
    }
    private val main = Handler(Looper.getMainLooper())
    private val modifying = AtomicBoolean(false)
    private val backend = FiveGBackend(
        shell = { RootShell.exec(it) },
        defaultSim = { getDefaultDataSim() },
        log = { Log.i("5GSwitch", it) },
    )
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

    fun refresh(callback: (Result) -> Unit) {
        executor.execute {
            val result = backend.run()
            main.post { callback(result) }
        }
    }

    fun toggle(callback: (Result) -> Unit): Boolean = submit(null, callback)

    fun set5GEnabled(enabled: Boolean, callback: (Result) -> Unit): Boolean = submit(enabled, callback)

    private fun submit(enabled: Boolean?, callback: (Result) -> Unit): Boolean {
        // 界面和磁贴共享防重入开关，排队的点击也不应重复反转。
        if (!modifying.compareAndSet(false, true)) return false
        executor.execute {
            try {
                val result = backend.run(enabled, toggle = enabled == null)
                lastOperation = result
                main.post { callback(result) }
            } finally {
                modifying.set(false)
            }
        }
        return true
    }
}

internal class FiveGBackend(
    private val shell: (String) -> RootShell.Result,
    private val defaultSim: () -> FiveGController.DataSim,
    private val log: (String) -> Unit = {},
) {
    private class Failure(
        val kind: FiveGController.Error,
        override val message: String,
    ) : Exception(message)

    private var state = FiveGController.State()
    private var probed = false

    @Synchronized
    fun run(enabled: Boolean? = null, toggle: Boolean = false): FiveGController.Result {
        state = FiveGController.State()
        return try {
            val root = exec("id -u")
            state = state.copy(rootAvailable = root.exitCode == 0 && root.stdout.trim() == "0")
            if (state.rootAvailable != true) fail(FiveGController.Error.ROOT_UNAVAILABLE,
                "Root 不可用，请在 Root 管理器中授权。", root)
            probe()
            readCurrent()
            if (enabled != null || toggle) update(enabled ?: !state.enabled!!)
            FiveGController.Result(state, if (enabled != null || toggle)
                "操作成功，已重新读取系统 USER 网络配置。" else "已读取系统 USER 网络配置。")
        } catch (e: Failure) {
            log("error=${e.kind}\n${e.message}")
            FiveGController.Result(state, e.message, e.kind)
        } catch (e: Exception) {
            val message = "无法读取电话配置：${e.javaClass.simpleName}: ${e.message}"
            log(message)
            FiveGController.Result(state, message, FiveGController.Error.READ_FAILED)
        }
    }

    private fun exec(command: String): RootShell.Result = shell(command).also {
        log(it.diagnostics())
    }

    private fun probe() {
        if (probed) return
        val result = exec("cmd phone help")
        if (result.exitCode != 0 || result.timedOut) fail(FiveGController.Error.PHONE_UNAVAILABLE,
            "cmd phone 不可用。", result)
        if (!PhoneCommands.supports(result.stdout)) fail(FiveGController.Error.UNSUPPORTED_SYNTAX,
            "不支持当前 cmd phone 参数格式；需要 SLOT_ID 和二进制 NETWORK_TYPES_BITMASK。", result)
        probed = true
    }

    private fun sim(): FiveGController.DataSim = try {
        defaultSim().also {
            check(it.subId >= 0 && it.slotIndex >= 0)
        }
    } catch (e: Exception) {
        state = state.copy(sim = null, mask = null)
        throw Failure(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE,
            "无法确定默认数据 SIM：${e.message}")
    }

    private fun readCurrent() {
        val currentSim = sim()
        state = state.copy(sim = currentSim, mask = null)
        val result = exec(PhoneCommands.read(currentSim.slotIndex))
        checkExit(result, "读取网络类型失败")
        val mask = try {
            PhoneCommands.parseMask(result.stdout)
        } catch (e: IllegalArgumentException) {
            fail(FiveGController.Error.READ_FAILED, "无法读取网络类型：${e.message}", result)
        }
        state = state.copy(mask = mask)
        log("subId=${currentSim.subId} slotIndex=${currentSim.slotIndex} currentMask=$mask")
    }

    private fun update(enabled: Boolean) {
        val originalSim = state.sim!!
        val current = state.mask!!
        val target = PhoneCommands.with5G(current, enabled)
        log("subId=${originalSim.subId} currentMask=$current targetMask=$target")
        // 默认数据卡在读取与写入之间变化时，中止本次写入。
        if (sim() != originalSim) {
            readCurrent()
            throw Failure(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE,
                "默认数据 SIM 已改变，本次未写入，请重试。")
        }
        if (target == current) return
        val written = exec(PhoneCommands.write(originalSim.slotIndex, target))
        val writeFailure = when {
            written.exitCode != 0 || written.timedOut -> Failure(
                FiveGController.Error.NON_ZERO_EXIT_CODE,
                "更新网络类型时命令失败。\n${written.diagnostics()}")
            !PhoneCommands.completed(written.stdout) -> Failure(
                FiveGController.Error.UPDATE_FAILED,
                "更新网络类型未返回 completed。\n${written.diagnostics()}")
            else -> null
        }
        // 即使写入报告失败，也尝试读回当前默认数据卡的真实配置。
        try {
            readCurrent()
        } catch (e: Failure) {
            throw Failure(writeFailure?.kind ?: e.kind,
                "${writeFailure?.message ?: "写入后无法确认状态。\n${written.diagnostics()}"}\n${e.message}")
        }
        if (writeFailure != null) throw writeFailure
        if (state.sim != originalSim) fail(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE,
            "操作期间默认数据 SIM 已改变，显示的是新卡状态，请重试。", written)
        if (state.mask != target) fail(FiveGController.Error.UPDATE_FAILED,
            "写入后配置不一致：targetMask=$target actualMask=${state.mask}。", written)
    }

    private fun checkExit(result: RootShell.Result, action: String) {
        if (result.exitCode != 0 || result.timedOut) {
            fail(FiveGController.Error.NON_ZERO_EXIT_CODE, "$action：命令返回非零退出码或超时。", result)
        }
    }

    private fun fail(kind: FiveGController.Error, message: String, result: RootShell.Result): Nothing =
        throw Failure(kind, "$message\n${result.diagnostics()}")
}
