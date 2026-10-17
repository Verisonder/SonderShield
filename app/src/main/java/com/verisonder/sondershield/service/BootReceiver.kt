package com.verisonder.sondershield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.verisonder.sondershield.data.Prefs

/** Turns protection back on after a reboot or an app update, if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val prefs = Prefs(context)
        val wanted = prefs.wantOn && (prefs.startOnBoot || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
        // prepare() is null only while the user's VPN consent still stands.
        if (wanted && VpnService.prepare(context) == null) {
            ShieldVpnService.start(context)
        }
    }
}
