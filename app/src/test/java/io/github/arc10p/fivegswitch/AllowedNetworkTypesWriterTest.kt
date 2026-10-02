package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test

class AllowedNetworkTypesWriterTest {
    class LegacyPhone {
        var received: List<Any>? = null
        var accepted = true
        fun setAllowedNetworkTypesForReason(subId: Int, reason: Int, mask: Long): Boolean {
            received = listOf(subId, reason, mask)
            return accepted
        }
    }

    class VendorPhone {
        var received: List<Any>? = null
        var writes = 0
        var failure: Throwable? = null
        fun setAllowedNetworkTypesForReason(subId: Int, reason: Int, mask: Long, identity: String): Boolean {
            writes++
            received = listOf(subId, reason, mask, identity)
            failure?.let { throw it }
            return true
        }
    }

    class UnknownPhone {
        fun setAllowedNetworkTypesForReason(subId: Int, reason: Int, mask: Long, identity: Int): Boolean =
            subId >= 0 && reason >= 0 && mask >= 0 && identity >= 0
    }

    @Test fun `三参数系统保持原始调用并完整保留 LTE_CA 与高位`() {
        val phone = LegacyPhone()
        val writer = AllowedNetworkTypesWriter(LegacyPhone::class.java.methods.toList())
        val mask = 392191L or (1L shl 40)
        assertTrue(writer.write(phone, 1, 0, mask, "测试包身份"))
        assertEquals(listOf(1, 0, mask), phone.received)
    }

    @Test fun `设备四参数签名收到相同 Long 掩码及本应用包身份`() {
        val phone = VendorPhone()
        val writer = AllowedNetworkTypesWriter(VendorPhone::class.java.methods.toList())
        val mask = 392191L or (1L shl 40)
        assertTrue(writer.write(phone, 1, 0, mask, "io.github.arc10p.fivegswitch"))
        assertEquals(listOf(1, 0, mask, "io.github.arc10p.fivegswitch"), phone.received)
        assertEquals(1, phone.writes)
    }

    @Test fun `真实 boolean 拒绝结果不能包装为成功`() {
        val phone = LegacyPhone().apply { accepted = false }
        val writer = AllowedNetworkTypesWriter(LegacyPhone::class.java.methods.toList())
        assertFalse(writer.write(phone, 1, 0, 392191L, "测试包身份"))
    }

    @Test fun `系统写入异常保留原始原因且不重放`() {
        val original = SecurityException("系统拒绝包身份")
        val phone = VendorPhone().apply { failure = original }
        val writer = AllowedNetworkTypesWriter(VendorPhone::class.java.methods.toList())
        assertSame(original, assertThrows(SecurityException::class.java) {
            writer.write(phone, 1, 0, 392191L, "测试包身份")
        })
        assertEquals(1, phone.writes)
    }

    @Test fun `未知第四参数类型不得盲猜调用`() {
        val error = assertThrows(NoSuchMethodException::class.java) {
            AllowedNetworkTypesWriter(UnknownPhone::class.java.methods.toList())
        }
        assertTrue(error.message!!.contains("setAllowedNetworkTypesForReason"))
    }
}
