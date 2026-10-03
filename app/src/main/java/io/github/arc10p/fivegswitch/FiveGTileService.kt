package io.github.arc10p.fivegswitch

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class FiveGTileService : TileService() {
    private var busy = false
    private var listening = false
    private var generation = 0

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        val token = ++generation
        if (busy) return
        FiveGController.refresh(this) { result ->
            if (listening && token == generation && !busy) render(result)
        }
    }

    override fun onStopListening() {
        listening = false
        generation++
        super.onStopListening()
    }

    override fun onDestroy() {
        listening = false
        generation++
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        if (isLocked) unlockAndRun { change() } else change()
    }

    private fun change() {
        if (busy) return
        busy = true
        generation++
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE
            subtitle = getString(R.string.working)
            updateTile()
        }
        val accepted = FiveGController.toggle(this) { result ->
            busy = false
            if (listening) render(result)
            if (!result.success) Toast.makeText(this,
                R.string.tile_error, Toast.LENGTH_LONG).show()
        }
        if (!accepted) {
            FiveGController.refresh(this) { result ->
                busy = false
                if (listening) render(result)
            }
        }
    }

    private fun render(result: FiveGController.Result) {
        qsTile?.apply {
            label = "5G"
            state = when {
                !result.success || result.state.enabled == null -> Tile.STATE_UNAVAILABLE
                result.state.enabled == true -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            subtitle = getString(when {
                !result.success -> R.string.unavailable
                result.state.enabled == true -> R.string.enabled
                else -> R.string.disabled
            })
            contentDescription = getString(R.string.tile_description, subtitle)
            updateTile()
        }
    }
}
