package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test

class PhoneContractTest {
    class Legacy {
        fun getAllowedNetworkTypesForReason(sub: Int, reason: Int): Long = sub + reason.toLong()
        fun setAllowedNetworkTypesForReason(sub: Int, reason: Int, mask: Long): Boolean = sub + reason + mask >= 0
    }
    class Vendor {
        fun getAllowedNetworkTypesForReason(sub: Int, reason: Int): Long = sub + reason.toLong()
        fun setAllowedNetworkTypesForReason(sub: Int, reason: Int, mask: Long, identity: String): Boolean =
            sub + reason + mask >= 0 && identity.isNotEmpty()
    }
    class Unknown {
        fun getAllowedNetworkTypesForReason(sub: Int, reason: Int): Long = sub + reason.toLong()
        fun setAllowedNetworkTypesForReason(sub: Int, reason: Int, mask: Long, identity: Int): Boolean =
            sub + reason + mask + identity >= 0
    }
    class Ambiguous {
        fun getAllowedNetworkTypesForReason(sub: Int, reason: Int): Long = sub + reason.toLong()
        fun setAllowedNetworkTypesForReason(sub: Int, reason: Int, mask: Long): Boolean = sub + reason + mask >= 0
        fun setAllowedNetworkTypesForReason(sub: Int, reason: Int, mask: Long, identity: String): Boolean =
            sub + reason + mask >= 0 && identity.isNotEmpty()
    }

    private fun contract(type: Class<*>, get: Int = 83, set: Int = 92) =
        PhoneContract.from(type.declaredMethods.toList()) { name ->
            when (name) {
                "TRANSACTION_getAllowedNetworkTypesForReason" -> get
                "TRANSACTION_setAllowedNetworkTypesForReason" -> set
                else -> error("未知事务 $name")
            }
        }

    @Test fun `三参数签名使用系统发现的事务编号`() {
        val api = contract(Legacy::class.java, 701, 1203)
        assertEquals(701, api.getterCode)
        assertEquals(1203, api.setterCode)
        assertFalse(api.setterNeedsIdentity)
    }
    @Test fun `四参数签名必须发送包身份`() { assertTrue(contract(Vendor::class.java).setterNeedsIdentity) }
    @Test fun `未知第四参数类型停止调用`() {
        assertThrows(NoSuchMethodException::class.java) { contract(Unknown::class.java) }
    }
    @Test fun `含混重载不盲目选取写入接口`() {
        assertThrows(NoSuchMethodException::class.java) { contract(Ambiguous::class.java) }
    }
    @Test fun `缺失读取接口不能发起任何事务`() {
        assertThrows(NoSuchMethodException::class.java) { PhoneContract.from(emptyList()) { error("不应读取编号") } }
    }
    @Test fun `缺失事务常量不能回退硬编码`() {
        assertThrows(IllegalStateException::class.java) {
            PhoneContract.from(Legacy::class.java.declaredMethods.toList()) { error("事务常量缺失") }
        }
    }
    @Test fun `事务号越界或重复全部拒绝`() {
        listOf(0 to 2, -1 to 2, 1 to 0x01000000, 42 to 42).forEach { (get, set) ->
            assertThrows(IllegalArgumentException::class.java) { contract(Legacy::class.java, get, set) }
        }
    }
}
