package com.tuvyanoam.soundpilot.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.container
import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.UserEvent
import com.tuvyanoam.soundpilot.platform.Labels
import com.tuvyanoam.soundpilot.platform.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile that cycles sound → vibrate → silent. */
class RingerTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope = MainScope()
        render()
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext.container
        val next = when (app.audio.ringerMode()) {
            RingerMode.NORMAL -> RingerMode.VIBRATE
            RingerMode.VIBRATE -> if (Permissions.hasPolicyAccess(this)) RingerMode.SILENT else RingerMode.NORMAL
            RingerMode.SILENT -> RingerMode.NORMAL
        }
        (scope ?: app.scope).launch {
            app.coordinator.applyNow(listOf(AudioCommand.Ringer(next)), Labels.mode(this@RingerTileService, next), fromAi = false)
            app.coordinator.recordUserChange(UserEvent.Kind.RINGER, ringerMode = next)
            render()
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val mode = applicationContext.container.audio.ringerMode()
        tile.icon = Icon.createWithResource(
            this,
            when (mode) {
                RingerMode.NORMAL -> R.drawable.ic_ring_normal
                RingerMode.VIBRATE -> R.drawable.ic_ring_vibrate
                RingerMode.SILENT -> R.drawable.ic_ring_silent
            },
        )
        tile.label = Labels.mode(this, mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(R.string.tile_label)
        tile.state = if (mode == RingerMode.NORMAL) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
