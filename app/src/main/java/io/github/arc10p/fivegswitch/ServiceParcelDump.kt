package io.github.arc10p.fivegswitch

// 系统 service 的文本输出恢复为 Parcel 字节；不从地址或 ASCII 栏提取数据。
internal object ServiceParcelDump {
    fun decode(output: String): ByteArray {
        val text = output.trim()
        val prefix = "Result: Parcel("
        require(text.startsWith(prefix) && text.endsWith(")")) { "原生电话回复格式不支持：${text.take(512)}" }
        val rows = text.substring(prefix.length, text.length - 1).trim().lines().filter { it.isNotBlank() }
        require(rows.isNotEmpty() && rows.size <= 256) { "原生电话回复为空或过长" }
        val words = ArrayList<Int>()
        var addressed: Boolean? = null
        for (row in rows) {
            val line = row.trim()
            val quote = line.indexOf('\'')
            val data = if (quote >= 0) {
                require(line.endsWith("'")) { "电话回复 ASCII 栏截断" }
                line.substring(0, quote).trim()
            } else line
            val hasAddress = data.startsWith("0x")
            if (addressed == null) addressed = hasAddress else require(addressed == hasAddress)
            val hex = if (hasAddress) {
                val match = Regex("^0x([0-9a-fA-F]{8}):\\s*(.*)$").matchEntire(data)
                    ?: error("电话回复地址格式错误")
                require(match.groupValues[1].toLong(16) == words.size * 4L) { "电话回复地址断裂" }
                match.groupValues[2]
            } else {
                require(rows.size == 1) { "多行电话回复缺少地址" }
                data
            }
            val parts = hex.trim().split(Regex("\\s+"))
            require(parts.size in 1..4 && parts.all { it.matches(Regex("[0-9a-fA-F]{8}")) }) {
                "电话回复数据畸形或截断：${data.take(128)}"
            }
            words.addAll(parts.map { it.toLong(16).toInt() })
        }
        require(words.isNotEmpty()) { "电话回复缺少异常头" }
        return ByteArray(words.size * 4).also { bytes ->
            words.forEachIndexed { index, word ->
                for (byte in 0..3) bytes[index * 4 + byte] = (word ushr (byte * 8)).toByte()
            }
        }
    }
}
