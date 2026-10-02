package io.github.arc10p.fivegswitch

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FiveGBackendTest {
    private val help = """
        ${PhoneCommands.GET} [-s SLOT_ID]
        ${PhoneCommands.SET} [-s SLOT_ID] [NETWORK_TYPES_BITMASK]
            NETWORK_TYPES_BITMASK is bitmask in binary format
    """.trimIndent()

    private class Device {
        var mask = (1L shl 12) or (1L shl 15) or (1L shl 2)
        val commands = mutableListOf<String>()
        var setOutput = "${PhoneCommands.SET} completed"
        var setExit = 0
        var applyWrite = true

        fun exec(command: String): RootShell.Result {
            commands += command
            return when {
                command == "id -u" -> RootShell.Result(command, 0, "0")
                command.startsWith("cmd phone ${PhoneCommands.GET}") -> {
                    val output = "UMTS|LTE|GSM|LTE_CA" +
                        if (mask and PhoneCommands.NR != 0L) "|NR" else ""
                    RootShell.Result(command, 0, output)
                }
                command.startsWith("cmd phone ${PhoneCommands.SET}") -> {
                    if (applyWrite) mask = command.substringAfterLast(' ').toLong(2)
                    RootShell.Result(command, setExit, setOutput, if (setExit == 0) "" else "Permission denied")
                }
                else -> error("意外命令：$command")
            }
        }
    }

    private fun backend(device: Device, sim: () -> FiveGController.DataSim = {
        FiveGController.DataSim(83, 1)
    }): FiveGBackend = FiveGBackend({ command ->
        if (command == "cmd phone help") {
            device.commands += command
            RootShell.Result(command, 0, help)
        } else device.exec(command)
    }, sim)

    @Test fun `LTE_CA 是 LTE 的输出别名且不会多写一位`() {
        val mask = PhoneCommands.parseMask("UMTS|LTE|LTE_CA|GSM")
        assertEquals((1L shl 2) or (1L shl 12) or (1L shl 15), mask)
        assertEquals(0L, mask and (1L shl 18))
    }

    @Test fun `识别 AOSP 实际 CDMA 与 HSPA 名称`() {
        assertEquals((1L shl 4) or (1L shl 5) or (1L shl 6) or (1L shl 10) or
            (1L shl 11) or (1L shl 13) or (1L shl 14), PhoneCommands.parseMask(
            "CDMA - EvDo rev. 0|CDMA - EvDo rev. A|CDMA - 1xRTT|iDEN|CDMA - EvDo rev. B|CDMA - eHRPD|HSPA+"))
    }

    @Test fun `只改变 NR 位并保留任意其他位`() {
        listOf(0L, 1L, 0x7FFFFL, 1L shl 40, Long.MAX_VALUE).forEach { mask ->
            val on = PhoneCommands.with5G(mask, true)
            assertEquals(mask and PhoneCommands.NR.inv(), on and PhoneCommands.NR.inv())
            assertEquals(mask and PhoneCommands.NR.inv(), PhoneCommands.with5G(mask, false))
            assertTrue(on and PhoneCommands.NR != 0L)
        }
    }

    @Test fun `使用动态槽位并以二进制写入后读回`() {
        val device = Device()
        val original = device.mask
        val result = backend(device).run(toggle = true)
        assertTrue(result.message, result.success)
        assertEquals(FiveGController.DataSim(83, 1), result.state.sim)
        assertEquals(original or PhoneCommands.NR, device.mask)
        assertTrue(device.commands.contains(PhoneCommands.write(1, original or PhoneCommands.NR)))
        assertFalse(device.commands.any { "-s 83" in it })
        assertEquals(2, device.commands.count { PhoneCommands.GET in it })
    }

    @Test fun `关闭后再次开启不会覆盖其他网络位`() {
        val device = Device()
        device.mask = device.mask or PhoneCommands.NR
        val original = device.mask
        val controller = backend(device)
        assertFalse(controller.run(toggle = true).state.enabled!!)
        assertEquals(original and PhoneCommands.NR.inv(), device.mask)
        assertTrue(controller.run(toggle = true).state.enabled!!)
        assertEquals(original, device.mask)
    }

    @Test fun `退出码为零但输出 failed 仍算失败`() {
        val device = Device().apply { setOutput = "${PhoneCommands.SET} failed"; applyWrite = false }
        val result = backend(device).run(toggle = true)
        assertEquals(FiveGController.Error.UPDATE_FAILED, result.error)
        assertFalse(result.state.enabled!!)
        assertTrue(result.message.contains("exitCode: 0"))
        assertTrue(result.message.contains("command:"))
    }

    @Test fun `保留非零退出码和标准错误`() {
        val device = Device().apply { setExit = 1; applyWrite = false }
        val result = backend(device).run(toggle = true)
        assertEquals(FiveGController.Error.NON_ZERO_EXIT_CODE, result.error)
        assertTrue(result.message.contains("exitCode: 1"))
        assertTrue(result.message.contains("Permission denied"))
    }

    @Test fun `即使 completed 也必须验证读回配置`() {
        val device = Device().apply { applyWrite = false }
        val result = backend(device).run(toggle = true)
        assertEquals(FiveGController.Error.UPDATE_FAILED, result.error)
        assertTrue(result.message.contains("actualMask="))
    }

    @Test fun `写入前默认卡改变则中止并读取新卡`() {
        val device = Device()
        var calls = 0
        val result = backend(device) {
            if (++calls == 1) FiveGController.DataSim(83, 1) else FiveGController.DataSim(27, 0)
        }.run(toggle = true)
        assertEquals(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE, result.error)
        assertEquals(FiveGController.DataSim(27, 0), result.state.sim)
        assertTrue(device.commands.contains(PhoneCommands.read(0)))
        assertFalse(device.commands.any { PhoneCommands.SET in it })
    }

    @Test fun `写入后默认卡改变不能报告新卡切换成功`() {
        val device = Device()
        var calls = 0
        val result = backend(device) {
            if (++calls <= 2) FiveGController.DataSim(83, 1) else FiveGController.DataSim(27, 0)
        }.run(toggle = true)
        assertEquals(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE, result.error)
        assertEquals(FiveGController.DataSim(27, 0), result.state.sim)
    }

    @Test fun `下次操作自动使用新的默认数据卡`() {
        val device = Device()
        var sim = FiveGController.DataSim(83, 1)
        val controller = backend(device) { sim }
        assertTrue(controller.run(toggle = true).success)
        sim = FiveGController.DataSim(27, 0)
        assertTrue(controller.run(toggle = true).success)
        assertEquals(PhoneCommands.read(0), device.commands.last())
    }

    @Test fun `拒绝不能无歧义解码的输出`() {
        listOf("UNKNOWN", "", "524288", "NR|SATELLITE", "NR|NR", "error\nNR").forEach {
            assertThrows(IllegalArgumentException::class.java) { PhoneCommands.parseMask(it) }
        }
    }

    @Test fun `不猜测厂商改变的参数格式`() {
        assertTrue(PhoneCommands.supports(help))
        assertFalse(PhoneCommands.supports(help.replace("SLOT_ID", "SUB_ID")))
        assertFalse(PhoneCommands.supports(help.replace("binary", "decimal")))
        val result = FiveGBackend({ RootShell.Result(it, 0, if (it == "id -u") "0" else "Unknown command") },
            { FiveGController.DataSim(83, 1) }).run(toggle = true)
        assertEquals(FiveGController.Error.UNSUPPORTED_SYNTAX, result.error)
    }

    @Test fun `区分 Root 不可用电话服务不可用与无默认卡`() {
        assertEquals(FiveGController.Error.ROOT_UNAVAILABLE,
            FiveGBackend({ RootShell.Result(it, 1, "", "su denied") },
                { error("不应读取 SIM") }).run().error)
        assertEquals(FiveGController.Error.PHONE_UNAVAILABLE,
            FiveGBackend({ RootShell.Result(it, if (it == "id -u") 0 else 1,
                if (it == "id -u") "0" else "") }, { error("不应读取 SIM") }).run().error)
        assertEquals(FiveGController.Error.DEFAULT_SIM_UNAVAILABLE,
            backend(Device()) { error("没有活动 SIM") }.run().error)
    }

    @Test fun `多个调用不会交错执行读改写`() {
        val device = Device()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val controller = FiveGBackend({ command ->
            val count = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            try {
                Thread.sleep(5)
                if (command == "cmd phone help") RootShell.Result(command, 0, help) else device.exec(command)
            } finally { active.decrementAndGet() }
        }, { FiveGController.DataSim(83, 1) })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = (1..2).map {
                pool.submit(Callable { start.await(); controller.run(toggle = true) })
            }
            start.countDown()
            futures.forEach { assertTrue(it.get(10, TimeUnit.SECONDS).success) }
            assertEquals(1, maximum.get())
            assertEquals(2, device.commands.count { PhoneCommands.SET in it })
            assertEquals(0L, device.mask and PhoneCommands.NR)
        } finally { pool.shutdownNow() }
    }
}
