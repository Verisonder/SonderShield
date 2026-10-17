package com.verisonder.sondershield.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.verisonder.sondershield.R
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.Prefs
import com.verisonder.sondershield.ui.MainActivity
import com.verisonder.sondershield.ui.trc

/** Home screen widget: the mark lit in the accent when on, the state, blocked today. Tap toggles. */
class ShieldWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context)
    }

    companion object {
        private var lastUpdate = 0L

        /** Redraws every SonderShield widget. [throttle]: skip if one ran in the last few seconds. */
        fun update(context: Context, throttle: Boolean = false) {
            val now = System.currentTimeMillis()
            if (throttle && now - lastUpdate < 8000) return
            lastUpdate = now
            val ctx = context.applicationContext
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ShieldWidget::class.java))
            if (ids.isEmpty()) return
            ShieldState.init(ctx)
            val prefs = Prefs(ctx)
            val on = ShieldState.phase.value != Phase.OFF
            val blocked = prefs.todayCounts().second

            val v = RemoteViews(ctx.packageName, R.layout.widget_shield)
            v.setInt(R.id.widget_top, "setColorFilter", if (on) prefs.accent else 0xFF3A3A3A.toInt())
            v.setInt(R.id.widget_bottom, "setColorFilter", 0xFFFFFFFF.toInt())
            v.setTextViewText(
                R.id.widget_state,
                when (ShieldState.phase.value) {
                    Phase.ON -> trc(ctx, "Protected", "Protégé", "Geschützt")
                    Phase.STARTING -> trc(ctx, "Starting…", "Démarrage…", "Startet…")
                    Phase.OFF -> trc(ctx, "Off", "Désactivé", "Aus")
                },
            )
            v.setTextViewText(
                R.id.widget_count,
                if (on) trc(ctx, "%,d blocked today".format(blocked), "%,d bloqués aujourd'hui".format(blocked), "%,d heute blockiert".format(blocked))
                else trc(ctx, "Tap to turn on", "Touchez pour activer", "Tippen zum Einschalten"),
            )
            v.setOnClickPendingIntent(R.id.widget_root, tapIntent(ctx, on))
            mgr.updateAppWidget(ids, v)
        }

        /** Off -> start (or open the app for the VPN prompt the first time); on -> stop. */
        private fun tapIntent(ctx: Context, on: Boolean): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return when {
                on -> PendingIntent.getService(
                    ctx, 20, Intent(ctx, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP), flags,
                )
                ShieldVpnService.needsConsent(ctx) -> PendingIntent.getActivity(
                    ctx, 21, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_TURN_ON, true), flags,
                )
                else -> PendingIntent.getForegroundService(
                    ctx, 22, Intent(ctx, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START), flags,
                )
            }
        }
    }
}
