package io.github.arc10p.fivegswitch

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
            if (uid != 0) throw Failure(FiveGController.Error.ROOT_UNAVAILABLE, "Root 命令 UID=$uid，未取得 Root 权限。")
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
        if (mask == -1L) throw Failure(FiveGController.Error.READ_FAILED, "无效 USER mask=$mask，subId=${currentSim.subId}。")
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
