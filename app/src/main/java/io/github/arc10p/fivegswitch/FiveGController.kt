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
import com.topjohnwu.superuser.Shell
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
        ROOT_UNAVAILABLE, SERVICE_UNAVAILABLE, PHONE_UNAVAILABLE, DEFAULT_SIM_UNAVAILABLE,
        READ_FAILED, UPDATE_FAILED, UNSUPPORTED_DEVICE,
    }

    data class Result(val state: State, val message: String, val error: Error? = null) {
        val success: Boolean get() = error == null
    }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "5G-controller") }
    private val main = Handler(Looper.getMainLooper())
    private val modifying = AtomicBoolean(false)
    private val connections = ConnectionScope<RootBinding>({ it.canReuse }, { it.releaseAndAwait() })
    private val rootShells = RootShellSession<Shell>({ it.isAlive }, { shell ->
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val verification = shell.newJob().add("/system/bin/id -u").to(stdout, stderr).enqueue()
        // Builder 的超时只覆盖初始化；单独限制 UID 验证，超时后会话由外层关闭。
        val result = try {
            verification.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            verification.cancel(true)
            throw IllegalStateException("su UID 验证未能在 10 秒内完成或执行失败", e)
        }
        val uid = stdout.singleOrNull()?.trim()?.toIntOrNull()
        check(result.isSuccess && uid != null) {
            "su UID 验证失败：exitCode=${result.code} stdout=${stdout.joinToString(" ").take(512)} " +
                "stderr=${stderr.joinToString(" ").take(512)}"
        }
        Log.i("5GSwitch", "su 实际 UID=$uid libsuStatus=${shell.status}")
        uid
    }, { it.close() })
    @Volatile private var pendingLaunch: Shell.Task? = null
    @Volatile var lastOperation: Result? = null
        private set

    fun retainClient() = connections.retainClient()

    fun releaseClient() {
        connections.releaseClient()
        executor.execute { closeIfIdle() }
    }

    private fun closeIfIdle() {
        try {
            connections.closeIfIdle()
        } catch (e: Exception) {
            Log.w("5GSwitch", "等待旧 RootService 退出：${e.message}")
        }
    }

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
        connections.beginOperation()
        executor.execute {
            var binding: RootBinding? = null
            val result = try {
                val currentBinding = connections.connection { RootBinding(context) }
                binding = currentBinding
                val service = currentBinding.connect()
                Log.i("5GSwitch", "RootService pid=${service.rootPid}")
                val access = object : TelephonyAccess {
                    override fun getRootUid() = service.rootUid
                    override fun checkCompatibility(subId: Int) = service.checkCompatibility(subId)
                    override fun getUserMask(subId: Int) = service.getUserMask(subId)
                    override fun setUserMask(subId: Int, mask: Long) = service.setUserMask(subId, mask)
                }
                FiveGBackend(access, { getDefaultDataSim() }, { Log.i("5GSwitch", it) })
                    .run(enabled, toggle = modification && enabled == null)
            } catch (e: Exception) {
                val root = binding?.rootAvailable
                val causes = generateSequence<Throwable>(e) { it.cause }.take(6)
                    .joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
                val message = when (root) {
                    true -> "已验证 Root UID=0，但 RootService 连接失败：$causes"
                    false -> "su 会话未取得 UID 0：$causes"
                    null -> "RootService 连接失败，Root 状态尚未确认：$causes"
                }
                Log.e("5GSwitch", message, e)
                Result(State(rootAvailable = root), message,
                    if (root == false) Error.ROOT_UNAVAILABLE else Error.SERVICE_UNAVAILABLE)
            } finally {
                connections.endOperation()
                closeIfIdle()
                if (modification) modifying.set(false)
            }
            if (modification) lastOperation = result
            main.post { callback(result) }
        }
    }

    private class RootBinding(private val context: Context) : ServiceConnection {
        private val intent = Intent(context, FiveGRootService::class.java)
        private val connected = CountDownLatch(1)
        private val prepared = CountDownLatch(1)
        private val released = AtomicBoolean(false)
        private val unbound = CountDownLatch(1)
        private val died = CountDownLatch(1)
        @Volatile private var remoteBinder: IBinder? = null
        @Volatile private var requested = false
        @Volatile private var service: IFiveGService? = null
        @Volatile private var failure: String? = null
        var rootAvailable: Boolean? = null
            private set

        val isAlive: Boolean get() = !released.get() && remoteBinder?.isBinderAlive == true && service != null
        val canReuse: Boolean get() = !released.get() && failure == null &&
            (remoteBinder == null || isAlive)

        fun connect(): IFiveGService {
            service?.takeIf { isAlive }?.let { return it }
            // 显式创建 su，不让默认 builder 吞掉启动错误后退回普通 sh。
            // 使用独立会话，避免主 shell 初始化异常后留下无法重试的缓存状态。
            val shell = try {
                rootShells.acquire {
                    Shell.Builder.create().setContext(context).setTimeout(60).build("su")
                }
            } finally {
                rootAvailable = rootShells.rootAvailable
            }
            // libsu 绑定必须在主线程；只有等待与电话 Binder 调用在工作线程。
            if (!requested) {
                main.post {
                    if (!released.get() && !requested) try {
                        // 主线程开始提交时即标记，准备超时后的重试不能再次注册同一连接。
                        requested = true
                        RootService.bindOrTask(intent, { task -> main.post(task) }, this)?.let {
                            pendingLaunch = it
                        }
                    } catch (e: Exception) {
                        failure = "${e.javaClass.simpleName}: ${e.message}"
                        connected.countDown()
                    } finally {
                        prepared.countDown()
                    }
                }
            }
            check(prepared.await(60, TimeUnit.SECONDS)) { "主线程未能准备 RootService 绑定" }
            if (failure != null) error(failure!!)
            // 启动无响应时保留可重执行的任务，下一次重试仍能发起 root 进程。
            if (connected.count > 0) pendingLaunch?.let { shell.execTask(it) }
            check(connected.await(60, TimeUnit.SECONDS)) { "等待 Root 授权或 RootService 连接超时（60 秒）" }
            return service ?: error(failure ?: "RootService 未返回 Binder")
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (released.get()) {
                unbind()
                return
            }
            remoteBinder = binder
            try {
                binder.linkToDeath({
                    service = null
                    failure = "RootService 进程已退出；若发生在写入期间，结果需要重新检测"
                    Log.w("5GSwitch", failure!!)
                    died.countDown()
                    connected.countDown()
                }, 0)
            } catch (e: Exception) {
                failure = "RootService 连接时已死亡：${e.message}"
                died.countDown()
                connected.countDown()
                return
            }
            service = IFiveGService.Stub.asInterface(binder)
            pendingLaunch = null
            connected.countDown()
            // 若连接在界面退出后迟到，空闲清理会负责解绑并确认进程死亡。
            executor.execute { closeIfIdle() }
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

        fun releaseAndAwait() {
            if (requested && remoteBinder == null && failure == null) {
                // 超时不代表启动取消。保留待连接记录，不能清空后与迟到回调竞速。
                check(connected.await(5, TimeUnit.SECONDS) && remoteBinder != null) {
                    "RootService 启动尚未确认，保留待连接记录供重试"
                }
            }
            if (released.compareAndSet(false, true)) {
                main.post {
                    try { unbind() } finally { unbound.countDown() }
                }
            }
            check(unbound.await(5, TimeUnit.SECONDS)) { "主线程尚未完成 RootService 解绑" }
            // libsu 的远程 unbind 是 oneway，返回并不代表 root 进程已经退出。
            val binder = remoteBinder
            if (binder != null && binder.isBinderAlive) {
                check(died.await(5, TimeUnit.SECONDS) || !binder.isBinderAlive) {
                    "旧 RootService 仍在退出，稍后重试，避免绑定即将死亡的进程"
                }
            }
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
        if (sim() != currentSim) {
            state = state.copy(sim = null, mask = null)
            throw Failure(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE, "读取期间默认数据 SIM 已改变，请重试。")
        }
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
