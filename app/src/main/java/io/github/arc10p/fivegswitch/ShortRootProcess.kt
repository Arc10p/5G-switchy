package io.github.arc10p.fivegswitch

import java.io.InputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal class ShortRootProcess(
    private val start: (String) -> Process = { ProcessBuilder("su", "-c", it).start() },
    private val timeoutSeconds: Long = 60,
) {
    @Volatile private var current: Process? = null
    val active: Boolean get() = current?.isAlive == true

    fun run(command: String): CommandResult {
        check(!active) { "旧 Root 命令客户端尚未退出，请重新检测" }
        var process: Process? = null
        var stdout: FutureTask<String>? = null
        var stderr: FutureTask<String>? = null
        try {
            process = start(command)
            current = process
            process.outputStream.close()
            stdout = drain(process.inputStream, "5G-stdout")
            stderr = drain(process.errorStream, "5G-stderr")
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "Root 命令超过 $timeoutSeconds 秒，写入结果需重新检测" }
            return CommandResult(process.exitValue(), stdout.get(2, TimeUnit.SECONDS), stderr.get(2, TimeUnit.SECONDS))
        } finally {
            // 超时后不重放写入；su 客户端终止并不保证厂商 Root 服务已取消远端命令。
            process?.let { running ->
                if (running.isAlive) runCatching { running.destroyForcibly(); running.waitFor(1, TimeUnit.SECONDS) }
            }
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
            stdout?.cancel(true)
            stderr?.cancel(true)
            if (process?.isAlive != true) current = null
        }
    }

    private fun drain(stream: InputStream, name: String): FutureTask<String> = FutureTask {
        stream.bufferedReader().use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(1024)
            var truncated = false
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                val remaining = 32768 - text.length
                if (count > remaining) truncated = true
                if (remaining > 0) text.append(buffer, 0, minOf(count, remaining))
            }
            check(!truncated) { "Root 命令输出超过 32 KiB，拒绝截断回复" }
            text.toString()
        }
    }.also { task -> Thread(null, task, name, 64 * 1024L).apply { isDaemon = true }.start() }
}
