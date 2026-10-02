package io.github.arc10p.fivegswitch

import android.telephony.TelephonyManager

internal object PhoneCommands {
    const val GET = "get-allowed-network-types-for-users"
    const val SET = "set-allowed-network-types-for-users"
    const val NR = TelephonyManager.NETWORK_TYPE_BITMASK_NR

    // AOSP getNetworkTypeName() 的实际输出，不是常量名称。
    private val names = listOf(
        "GPRS", "EDGE", "UMTS", "CDMA", "CDMA - EvDo rev. 0", "CDMA - EvDo rev. A", "CDMA - 1xRTT",
        "HSDPA", "HSUPA", "HSPA", "iDEN", "CDMA - EvDo rev. B", "LTE", "CDMA - eHRPD",
        "HSPA+", "GSM", "TD_SCDMA", "IWLAN", "LTE_CA", "NR",
    )
    // getBitMaskForNetworkType(LTE_CA) 返回 LTE 位；LTE 输出通常包含 LTE|LTE_CA。
    private val bits = names.mapIndexed { index, name ->
        name to (1L shl if (name == "LTE_CA") 12 else index)
    }.toMap()

    fun supports(help: String): Boolean {
        val lines = help.lineSequence().map(String::trim).toSet()
        return "$GET [-s SLOT_ID]" in lines &&
            "$SET [-s SLOT_ID] [NETWORK_TYPES_BITMASK]" in lines &&
            help.contains("bitmask in binary format", ignoreCase = true)
    }

    fun read(slotIndex: Int): String {
        require(slotIndex >= 0)
        return "cmd phone $GET -s $slotIndex"
    }

    fun write(slotIndex: Int, mask: Long): String {
        require(slotIndex >= 0 && mask >= 0)
        return "cmd phone $SET -s $slotIndex ${java.lang.Long.toBinaryString(mask)}"
    }

    fun readRawUser(sim: FiveGController.DataSim): String {
        require(sim.subId >= 0)
        return "content query --uri content://telephony/siminfo " +
            "--projection _id:sim_id:allowed_network_types_for_reasons --where '_id=${sim.subId}'"
    }

    fun parseRawUser(output: String, sim: FiveGController.DataSim): Long {
        val row = Regex("^Row: [0-9]+ _id=([0-9]+), sim_id=([0-9]+), " +
            "allowed_network_types_for_reasons=(.+)$").matchEntire(output.trim())
        requireNotNull(row) { "没有唯一、完整的订阅网络配置记录" }
        require(row.groupValues[1].toInt() == sim.subId && row.groupValues[2].toInt() == sim.slotIndex) {
            "订阅记录与当前 SIM 槽位不一致"
        }
        val reasons = row.groupValues[3].split(',').map(String::trim)
        require(reasons.all { Regex("[a-z_]+=-?[0-9]+").matches(it) }) { "无法识别 reason 配置" }
        val users = reasons.filter { it.startsWith("user=") }
        require(users.size == 1) { "没有唯一的原始 USER 位掩码" }
        return users.single().substringAfter('=').toLong().also {
            require(it >= 0) { "无效 USER 位掩码：$it" }
        }
    }

    // 名称接口只表达 1～20 中的这些位，独立 LTE_CA 位及更高位不能参与名称比对。
    fun visibleMask(raw: Long): Long = raw and (((1L shl 20) - 1) and (1L shl 18).inv())

    fun parseMask(output: String): Long {
        val tokens = output.trim().split('|').map(String::trim)
        require(tokens.isNotEmpty() && tokens.distinct().size == tokens.size)
        // UNKNOWN 也可能表示厂商未公开的位，不能把它当作零并写回。
        return tokens.fold(0L) { mask, token ->
            mask or requireNotNull(bits[token]) { "无法识别网络类型：$token" }
        }
    }

    fun with5G(mask: Long, enabled: Boolean): Long =
        if (enabled) mask or NR else mask and NR.inv()

    fun completed(output: String): Boolean = output.trim() == "$SET completed"
}
