package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FiveGBackendTest {
    private class Device : TelephonyAccess {
        var uid = 0
        var uidFailure: String? = null
        var compatible = true
        var mask = (1L shl 12) or (1L shl 15) or (1L shl 2)
        val calls = mutableListOf<String>()
        var setResult = true
        var applyWrite = true
        var getFailure: String? = null
        var setFailure: String? = null
        var checkFailure: String? = null
        var afterSet: () -> Unit = {}

        override fun getRootUid(): Int {
            uidFailure?.let { error(it) }
            return uid
        }
        override fun checkCompatibility(subId: Int): Boolean {
            calls += "check:$subId"
            checkFailure?.let { error(it) }
            return compatible
        }
        override fun getUserMask(subId: Int): Long {
            calls += "get:$subId"
            getFailure?.let { error(it) }
            return mask
        }
        override fun setUserMask(subId: Int, mask: Long): Boolean {
            calls += "set:$subId:$mask"
            setFailure?.let { error(it) }
            if (applyWrite) this.mask = mask
            afterSet()
            return setResult
        }
    }

    private fun backend(device: TelephonyAccess, sim: () -> FiveGController.DataSim = {
        FiveGController.DataSim(83, 1)
    }) = FiveGBackend(device, sim)

    @Test fun `只改变 NR 位并保留任意其他位`() {
        listOf(0L, 1L, 0x7FFFFL, 1L shl 40, Long.MAX_VALUE, Long.MIN_VALUE).forEach { mask ->
            val on = NetworkTypes.with5G(mask, true)
            assertEquals(mask and NetworkTypes.NR.inv(), on and NetworkTypes.NR.inv())
            assertEquals(mask and NetworkTypes.NR.inv(), NetworkTypes.with5G(mask, false))
            assertTrue(on and NetworkTypes.NR != 0L)
        }
    }

    @Test fun `整个 Binder 路径完整保留 LTE_CA 与未知高位`() {
        val device = Device().apply { mask = mask or (1L shl 18) or (1L shl 40) }
        val original = device.mask
        val controller = backend(device)
        assertTrue(controller.run(toggle = true).success)
        assertEquals(original or NetworkTypes.NR, device.mask)
        assertTrue(controller.run(toggle = true).success)
        assertEquals(original, device.mask)
    }

    @Test fun `零掩码也能安全开启关闭 NR`() {
        val device = Device().apply { mask = 0 }
        val controller = backend(device)
        assertTrue(controller.run(toggle = true).success)
        assertEquals(NetworkTypes.NR, device.mask)
        assertTrue(controller.run(toggle = true).success)
        assertEquals(0L, device.mask)
    }

    @Test fun `每次操作动态使用默认数据 subscriptionId`() {
        val device = Device()
        var sim = FiveGController.DataSim(83, 1)
        val controller = backend(device) { sim }
        assertTrue(controller.run(toggle = true).success)
        assertTrue(device.calls.any { it.startsWith("set:83:") })
        sim = FiveGController.DataSim(27, 0)
        assertTrue(controller.run(toggle = true).success)
        assertTrue(device.calls.any { it.startsWith("set:27:") })
        assertEquals("get:27", device.calls.last())
        assertFalse(device.calls.any { it.startsWith("set:0:") || it.startsWith("set:1:") })
    }

    @Test fun `写入前默认卡改变则中止并读取新卡`() {
        val device = Device()
        var calls = 0
        val result = backend(device) {
            if (++calls <= 2) FiveGController.DataSim(83, 1) else FiveGController.DataSim(27, 0)
        }.run(toggle = true)
        assertEquals(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE, result.error)
        assertEquals(FiveGController.DataSim(27, 0), result.state.sim)
        assertFalse(device.calls.any { it.startsWith("set:") })
    }

    @Test fun `读取期间换卡拒绝使用过时快照`() {
        val device = Device()
        var calls = 0
        val result = backend(device) {
            if (++calls == 1) FiveGController.DataSim(83, 1) else FiveGController.DataSim(27, 0)
        }.run(toggle = true)
        assertEquals(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE, result.error)
        assertNull(result.state.mask)
        assertFalse(device.calls.any { it.startsWith("set:") })
    }

    @Test fun `写入后默认卡改变不能报告新卡切换成功`() {
        val device = Device()
        var sim = FiveGController.DataSim(83, 1)
        device.afterSet = { sim = FiveGController.DataSim(27, 0) }
        val result = backend(device) { sim }.run(toggle = true)
        assertEquals(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE, result.error)
        assertEquals(FiveGController.DataSim(27, 0), result.state.sim)
    }

    @Test fun `setter 返回 false 必须报告失败并读回真实状态`() {
        val device = Device().apply { setResult = false; applyWrite = false }
        val result = backend(device).run(toggle = true)
        assertEquals(FiveGController.Error.UPDATE_FAILED, result.error)
        assertFalse(result.state.enabled!!)
        assertTrue(result.message.contains("返回 false"))
        assertEquals(2, device.calls.count { it.startsWith("get:") })
    }

    @Test fun `保留 Binder 设置异常以及实际参数`() {
        val device = Device().apply { setFailure = "Permission denied" }
        val result = backend(device).run(toggle = true)
        assertEquals(FiveGController.Error.UPDATE_FAILED, result.error)
        assertTrue(result.message.contains("subId=83"))
        assertTrue(result.message.contains("reason=USER"))
        assertTrue(result.message.contains("mask="))
        assertTrue(result.message.contains("Permission denied"))
    }

    @Test fun `setter 返回 true 也必须完整验证读回值`() {
        val device = Device().apply { applyWrite = false }
        val result = backend(device).run(toggle = true)
        assertEquals(FiveGController.Error.UPDATE_FAILED, result.error)
        assertTrue(result.message.contains("actualMask="))
    }

    @Test fun `读取失败不能伪装成已关闭`() {
        val device = Device().apply { getFailure = "DeadObjectException" }
        val result = backend(device).run()
        assertEquals(FiveGController.Error.READ_FAILED, result.error)
        assertNull(result.state.enabled)
        assertTrue(result.message.contains("DeadObjectException"))
    }

    @Test fun `拒绝非 Root UID`() {
        val result = backend(Device().apply { uid = 2000 }).run()
        assertEquals(FiveGController.Error.ROOT_UNAVAILABLE, result.error)
        assertFalse(result.state.rootAvailable!!)
    }

    @Test fun `获取 UID 时 Binder 死亡不能断言未授权`() {
        val result = backend(Device().apply { uidFailure = "DeadObjectException" }).run()
        assertEquals(FiveGController.Error.ROOT_UNAVAILABLE, result.error)
        assertNull(result.state.rootAvailable)
        assertTrue(result.message.contains("DeadObjectException"))
    }

    @Test fun `区分电话 Binder 故障与接口不兼容`() {
        assertEquals(FiveGController.Error.PHONE_UNAVAILABLE,
            backend(Device().apply { checkFailure = "phone Binder 不可用" }).run().error)
        assertEquals(FiveGController.Error.UNSUPPORTED_DEVICE,
            backend(Device().apply { compatible = false }).run().error)
    }

    @Test fun `无有效默认数据卡不会写入`() {
        val device = Device()
        assertEquals(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE,
            backend(device) { FiveGController.DataSim(-1, -1) }.run(toggle = true).error)
        assertFalse(device.calls.any { it.startsWith("set:") })
    }

    @Test fun `目标配置已满足时无需调用 setter`() {
        val device = Device()
        assertTrue(backend(device).run(enabled = false).success)
        assertFalse(device.calls.any { it.startsWith("set:") })
    }

    @Test fun `多个调用不会交错执行读改写`() {
        val device = Device()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val access = object : TelephonyAccess by device {
            override fun getUserMask(subId: Int): Long {
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                return try { Thread.sleep(10); device.getUserMask(subId) }
                finally { active.decrementAndGet() }
            }
        }
        val controller = backend(access)
        val original = device.mask
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = (1..2).map { pool.submit(Callable { start.await(); controller.run(toggle = true) }) }
            start.countDown()
            futures.forEach { assertTrue(it.get(10, TimeUnit.SECONDS).success) }
            assertEquals(1, maximum.get())
            assertEquals(2, device.calls.count { it.startsWith("set:") })
            assertEquals(original, device.mask)
        } finally { pool.shutdownNow() }
    }

    @Test fun `setter 失败仍显示实际已改变的 USER 配置`() {
        val result = backend(Device().apply { setResult = false }).run(toggle = true)
        assertEquals(FiveGController.Error.UPDATE_FAILED, result.error)
        assertTrue(result.state.enabled!!)
    }

    @Test fun `拒绝系统无效掩码哨兵负一`() {
        val device = Device().apply { mask = -1 }
        assertEquals(FiveGController.Error.READ_FAILED, backend(device).run(toggle = true).error)
        assertFalse(device.calls.any { it.startsWith("set:") })
    }

    @Test fun `有最高位的 Long 掩码完整保留而不截断`() {
        val device = Device().apply { mask = Long.MIN_VALUE or (1L shl 18) or 1L }
        val original = device.mask
        val controller = backend(device)
        assertTrue(controller.run(toggle = true).success)
        assertEquals(original or NetworkTypes.NR, device.mask)
        assertTrue(controller.run(toggle = true).success)
        assertEquals(original, device.mask)
    }

    @Test fun `写入时 Binder 死亡不能自动重放 toggle`() {
        val device = Device()
        var dead = false
        var writes = 0
        val access = object : TelephonyAccess by device {
            override fun setUserMask(subId: Int, mask: Long): Boolean {
                writes++
                dead = true
                // 模拟已经写入但响应丢失，重放 toggle 会反向修改状态。
                device.mask = mask
                error("DeadObjectException")
            }
            override fun checkCompatibility(subId: Int): Boolean {
                if (dead) error("DeadObjectException")
                return true
            }
        }
        val result = backend(access).run(toggle = true)
        assertEquals(FiveGController.Error.UPDATE_FAILED, result.error)
        assertEquals(1, writes)
        assertNull(result.state.mask)
        assertTrue(device.mask and NetworkTypes.NR != 0L)
    }
}
