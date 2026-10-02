package io.github.arc10p.fivegswitch

import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object RootShell {
    data class Result(
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String = "",
        val timedOut: Boolean = false,
    ) {
        fun diagnostics(): String =
            "command: $command\nexitCode: $exitCode\n" +
                "stdout: $stdout\nstderr: $stderr\ntimeout: $timedOut"
    }

    fun exec(command: String): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            var stdout = ""
            var stderr = ""
            // 分别持续排空两个管道，避免输出填满后死锁。
            val outReader = thread(isDaemon = true, name = "5G-stdout") {
                stdout = drain(process.inputStream)
            }
            val errReader = thread(isDaemon = true, name = "5G-stderr") {
                stderr = drain(process.errorStream)
            }
            val completed = process.waitFor(60, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            outReader.join(1000)
            errReader.join(1000)
            if (outReader.isAlive) process.inputStream.close()
            if (errReader.isAlive) process.errorStream.close()
            Result(command, if (completed) process.exitValue() else -1,
                stdout.trim(), stderr.trim(), timedOut = !completed)
        } catch (e: Exception) {
            Result(command, -1, "", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun drain(stream: InputStream): String {
        val output = StringBuilder()
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    // 限制诊断内存，超出的输出仍然读取并丢弃。
                    val remaining = 65536 - output.length
                    if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
                }
            }
        } catch (e: Exception) {
            output.append("\n读取输出失败：${e.message}")
        }
        return output.toString()
    }
}
