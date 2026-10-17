package com.verisonder.sondershield.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.verisonder.sondershield.R
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.ui.MainActivity

/** Quick Settings tile: one tap on or off. */
class ShieldTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        ShieldState.init(this)
        update()
    }

    override fun onClick() {
        super.onClick()
        if (ShieldState.phase.value != Phase.OFF && ShieldState.alwaysOn.value) {
            openVpnSettings()
        } else if (ShieldState.phase.value != Phase.OFF) {
            ShieldVpnService.stop(this)
        } else if (ShieldVpnService.needsConsent(this)) {
            // First time: the VPN permission dialog needs an activity.
            openApp()
        } else {
            ShieldVpnService.start(this)
        }
        update()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openVpnSettings() {
        val i = ShieldVpnService.vpnSettingsIntent()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_TURN_ON, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun update() {
        val t = qsTile ?: return
        val on = ShieldState.phase.value != Phase.OFF
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t.subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
        }
        t.updateTile()
    }
}
