package dev.incog.choptorch

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that turns the chop gesture on or off. */
class ChopTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val enable = !Prefs.enabled(this)
        Prefs.get(this).edit().putBoolean(Prefs.ENABLED, enable).apply()
        if (enable) runCatching { ChopService.start(this) } else ChopService.stop(this)
        refresh(enable)
    }

    private fun refresh(enabled: Boolean = Prefs.enabled(this)) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Chop Torch"
        tile.updateTile()
    }
}
