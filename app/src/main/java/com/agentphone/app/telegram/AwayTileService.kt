package com.agentphone.app.telegram

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.agentphone.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Quick-settings switch for the away master toggle. */
@AndroidEntryPoint
class AwayTileService : TileService() {

    @Inject
    lateinit var settings: AwaySettingsRepository

    @Inject
    lateinit var runtime: AwayRuntime

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            settings.setEnabled(!settings.current().enabled)
            runtime.sync()
            refresh()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        scope.launch {
            val enabled = settings.current().enabled
            withContext(Dispatchers.Main) {
                qsTile?.apply {
                    state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    label = getString(R.string.away_tile_label)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = getString(
                            if (enabled) R.string.away_tile_on else R.string.away_tile_off,
                        )
                    }
                    updateTile()
                }
            }
        }
    }
}
