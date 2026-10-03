package io.github.arc10p.fivegswitch

internal data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

internal class NativeRootCommands(private val run: (String) -> CommandResult) {
    var rootAvailable: Boolean? = null
        private set
    var lastStatus: String = "尚未执行"
        private set

    fun verifyUid(): Int { invoke("exit 0"); return 0 }

    fun call(arguments: List<String>): String = invoke("exec " + arguments.joinToString(" ", transform = ::quote))

    private fun invoke(command: String): String {
        // 在同一 su 中验证 UID 后才执行 Binder 命令；PID 仅作历史诊断。
        val guarded = "fiveg_uid=$(/system/bin/id -u); printf '%s\\n%s\\n' \"\$fiveg_uid\" \"\$\$\"; " +
            "[ \"\$fiveg_uid\" = 0 ] || exit 77; $command"
        lastStatus = "已启动，等待退出"
        val result = try { run(guarded) } catch (e: Exception) {
            lastStatus = "客户端已结束；命令结果未知：${e.message}"
            throw e
        }
        val lines = result.stdout.split('\n', limit = 3)
        val uid = lines.firstOrNull()?.trim()?.toIntOrNull()
        val pid = lines.getOrNull(1)?.trim()?.toIntOrNull()
        lastStatus = "UID=${uid ?: "未知"} PID=${pid ?: "未知"} exit=${result.exitCode}（命令客户端已退出）"
        if (uid != null) rootAvailable = uid == 0
        check(uid == 0 && pid != null && pid > 0 && result.exitCode == 0 && result.stderr.isBlank()) {
            "原生 Root 命令失败：$lastStatus\nstdout=${result.stdout.take(512)}\nstderr=${result.stderr.take(512)}"
        }
        return lines.getOrElse(2) { "" }
    }

    companion object {
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
