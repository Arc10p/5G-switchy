package io.github.arc10p.fivegswitch

import android.os.Debug
import android.os.Process
import java.io.File

internal object MemorySnapshot {
    // 固定数组便于 AIDL 传输，不引入额外序列化依赖；内存单位均为 KiB。
    fun capture(): IntArray {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        val rss = try {
            File("/proc/self/status").bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.removePrefix("VmRSS:")?.trim()?.substringBefore(' ')?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
        val categories = arrayOf("summary.java-heap", "summary.native-heap", "summary.graphics",
            "summary.code", "summary.stack", "summary.private-other", "summary.system")
        return intArrayOf(Process.myPid(), Process.myUid(), info.totalPss,
            info.totalPrivateDirty + info.totalPrivateClean,
            ((runtime.totalMemory() - runtime.freeMemory()) / 1024).toInt(),
            (Debug.getNativeHeapAllocatedSize() / 1024).toInt(), rss) +
            categories.map { info.getMemoryStat(it)?.toIntOrNull() ?: -1 }.toIntArray()
    }

    fun describe(label: String, values: IntArray): String {
        if (values.size != 14) return "$label：采样格式不兼容"
        return "$label · PID=${values[0]} UID=${values[1]}\n" +
            "PSS=${mib(values[2])} 私有页=${mib(values[3])} RSS=${mib(values[6])}\n" +
            "Java 已用堆=${mib(values[4])} Native 已分配堆=${mib(values[5])}\n" +
            "系统统计：Java=${mib(values[7])} Native=${mib(values[8])} Graphics=${mib(values[9])}\n" +
            "Code=${mib(values[10])} Stack=${mib(values[11])} Private Other=${mib(values[12])} System=${mib(values[13])}\n"
    }

    private fun mib(kib: Int): String {
        if (kib < 0) return "不可读"
        val tenths = (kib * 10L + 512) / 1024
        return "${tenths / 10}.${tenths % 10} MiB"
    }
}
