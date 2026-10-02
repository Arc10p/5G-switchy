package io.github.arc10p.fivegswitch

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var rootStatus: TextView
    private lateinit var simStatus: TextView
    private lateinit var fiveGStatus: TextView
    private lateinit var details: TextView
    private lateinit var toggle: Button
    private var latest: FiveGController.Result? = null
    private var request = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootStatus = findViewById(R.id.root_status)
        simStatus = findViewById(R.id.sim_status)
        fiveGStatus = findViewById(R.id.five_g_status)
        details = findViewById(R.id.result)
        toggle = findViewById(R.id.toggle)
        // Android 15 及以上强制边到边，保留系统栏与刘海的安全边距。
        val padding = (24 * resources.displayMetrics.density).toInt()
        findViewById<android.view.View>(R.id.page).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top,
                padding + bars.right, padding + bars.bottom)
            insets
        }
        toggle.setOnClickListener {
            if (latest?.success != true) refresh() else change()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val token = ++request
        loading()
        FiveGController.refresh(this) { result ->
            if (!isDestroyed && token == request) render(result, rememberOperation = true)
        }
    }

    private fun change() {
        val token = ++request
        loading()
        val accepted = FiveGController.toggle(this) { result ->
            if (!isDestroyed && token == request) render(result)
        }
        if (!accepted) {
            details.setText(R.string.busy)
            FiveGController.refresh(this) { result ->
                if (!isDestroyed && token == request) render(result, rememberOperation = true)
            }
        }
    }

    private fun loading() {
        toggle.isEnabled = false
        toggle.setText(R.string.working)
    }

    private fun render(result: FiveGController.Result, rememberOperation: Boolean = false) {
        latest = result
        rootStatus.setText(when (result.state.rootAvailable) {
            true -> R.string.available
            false -> R.string.unavailable
            null -> R.string.unknown
        })
        simStatus.text = result.state.sim?.let {
            getString(R.string.sim_value, it.slotIndex + 1, it.subId)
        } ?: getString(R.string.unknown)
        fiveGStatus.setText(when (result.state.enabled) {
            true -> R.string.enabled
            false -> R.string.disabled
            null -> R.string.unknown
        })
        val operation = if (rememberOperation && result.success) FiveGController.lastOperation else null
        details.text = buildString {
            append(if (operation != null) operation.message else result.message)
            result.state.mask?.let { append("\nUSER mask: $it") }
        }
        toggle.setText(when {
            !result.success -> R.string.retry
            result.state.enabled == true -> R.string.disable_5g
            else -> R.string.enable_5g
        })
        toggle.isEnabled = true
    }
}
