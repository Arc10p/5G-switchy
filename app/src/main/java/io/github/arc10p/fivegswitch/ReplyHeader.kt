package io.github.arc10p.fivegswitch

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object ReplyHeader {
    fun payloadOffset(bytes: ByteArray, payloadSize: Int): Int? {
        require(bytes.size >= 4 && bytes.size % 4 == 0) { "电话回复缺少异常头或未对齐" }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun word(offset: Int): Int {
            require(offset >= 0 && offset <= bytes.size - 4) { "电话回复头截断" }
            return data.getInt(offset)
        }
        // 额外头的 size 包含 size 字段自身；先检查边界，再交给系统解析内容。
        fun endOfHeader(offset: Int): Int {
            val size = word(offset + 4)
            require(size >= 8 && size % 4 == 0 && size.toLong() + offset + 4 <= bytes.size) {
                "电话额外回复头长度无效：$size"
            }
            val count = word(offset + 8)
            require(count >= 0 && count <= (size - 8) / 4) { "电话额外回复头记录数无效：$count" }
            return offset + 4 + size
        }
        var offset = 0
        var code = word(offset)
        if (code == -127) {
            offset = endOfHeader(offset)
            code = word(offset)
        }
        val payload = when (code) {
            0 -> offset + 4
            -128 -> endOfHeader(offset)
            else -> return null // 远端异常由系统 Parcel.readException() 抛出。
        }
        require(bytes.size - payload == payloadSize) { "电话回复载荷长度无效" }
        return payload
    }
}
