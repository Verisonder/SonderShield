package com.verisonder.sondershield.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.TileService
import android.content.ComponentName
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.verisonder.sondershield.R
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.ListStore
import com.verisonder.sondershield.data.Prefs
import com.verisonder.sondershield.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The tunnel. Only DNS is routed into it: the device's resolver is pointed at a
 * virtual address inside the tunnel and every other packet keeps its normal path, so
 * there is no speed cost and nothing else is touched.
 */
class ShieldVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.verisonder.sondershield.START"
        const val ACTION_STOP = "com.verisonder.sondershield.STOP"
        /** Rebuild the tunnel (bypass apps or IPv6 changed). */
        const val ACTION_RESTART = "com.verisonder.sondershield.RESTART"

        private const val CHANNEL = "shield"
        private const val NOTE_ID = 1

        const val V4_ADDR = "10.111.222.1"
        const val V4_DNS = "10.111.222.53"
        const val V6_ADDR = "fd00:5d5d::1"
        const val V6_DNS = "fd00:5d5d::53"

        fun start(context: Context) {
            Prefs(context).wantOn = true
            ContextCompat.startForegroundService(context, Intent(context, ShieldVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            Prefs(context).wantOn = false
            // Plain startService: a stop must not have to promise a foreground notification.
            if (ShieldState.phase.value != Phase.OFF) {
                context.startService(Intent(context, ShieldVpnService::class.java).setAction(ACTION_STOP))
            }
        }

        fun restartIfOn(context: Context) {
            if (ShieldState.phase.value != Phase.OFF) {
                context.startService(Intent(context, ShieldVpnService::class.java).setAction(ACTION_RESTART))
            }
        }
    }

    private val lock = Mutex()
    private var counterJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ShieldState.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ShieldState.scope.launch { lock.withLock { teardown() } }
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                goForeground()
                ShieldState.scope.launch { lock.withLock { bringUp() } }
                return START_STICKY
            }
            else -> {
                // ACTION_START, a null intent after the system restarted us (START_STICKY),
                // or android.net.VpnService from always-on VPN.
                if (intent?.action == SERVICE_INTERFACE) Prefs(this).wantOn = true
                if (intent == null && !Prefs(this).wantOn) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                goForeground()
                ShieldState.scope.launch { lock.withLock { bringUp() } }
                return START_STICKY
            }
        }
    }

    private suspend fun bringUp() {
        ShieldState.phase.value = Phase.STARTING
        ShieldState.problem.value = null
        refreshTile()
        ShieldState.engine.stop()
        ShieldState.applyConfig()

        val prefs = Prefs(this)
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(V4_ADDR, 24)
            .addDnsServer(V4_DNS)
            .addRoute(V4_DNS, 32)
        if (prefs.ipv6) {
            b.addAddress(V6_ADDR, 64).addDnsServer(V6_DNS).addRoute(V6_DNS, 128)
        }
        for (pkg in prefs.bypassApps) {
            try {
                b.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                // Uninstalled since it was picked.
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        b.setConfigureIntent(openAppIntent())

        val pfd = try {
            b.establish()
        } catch (e: Exception) {
            null.also { ShieldState.problem.value = "Could not start the VPN: ${e.message}" }
        }
        if (pfd == null) {
            if (ShieldState.problem.value == null) {
                ShieldState.problem.value = "VPN permission missing. Open SonderShield and turn it on."
            }
            prefs.wantOn = false
            teardown()
            return
        }
        try {
            ShieldState.engine.start(pfd.detachFd().toLong())
        } catch (e: Exception) {
            ShieldState.problem.value = "Engine failed to start: ${e.message}"
            prefs.wantOn = false
            teardown()
            return
        }
        ShieldState.phase.value = Phase.ON
        ShieldState.startPublishing()
        refreshTile()
        watchCounters()

        // First start, or a list was just switched on: fetch what's missing, then reload.
        val store = ListStore(this)
        if (store.missingEnabled().isNotEmpty()) {
            ShieldState.scope.launch(Dispatchers.IO) {
                for (l in store.missingEnabled()) store.update(l)
                ShieldState.listsVersion.value++
                ShieldState.applyConfig()
            }
        }
        UpdateWorker.schedule(this)
    }

    private fun teardown() {
        counterJob?.cancel()
        ShieldState.engine.stop()
        ShieldState.stopPublishing()
        ShieldState.phase.value = Phase.OFF
        refreshTile()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Another VPN took over, or the user revoked us in Settings. */
    override fun onRevoke() {
        ShieldState.problem.value = "Another VPN took over, or SonderShield was turned off in Settings."
        Prefs(this).wantOn = false
        ShieldState.scope.launch { lock.withLock { teardown() } }
    }

    override fun onDestroy() {
        counterJob?.cancel()
        if (ShieldState.phase.value != Phase.OFF) {
            ShieldState.engine.stop()
            ShieldState.stopPublishing()
            ShieldState.phase.value = Phase.OFF
            refreshTile()
        }
        super.onDestroy()
    }

    // --- notification ---

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTE_ID, buildNote(ShieldState.today.value.second), type)
    }

    private fun buildNote(blockedToday: Long): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ShieldVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(getString(R.string.note_title))
            .setContentText(getString(R.string.note_text, blockedToday))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.note_stop), stop)
            .build()
    }

    /** Keeps the notification's "blocked today" current, at most every few seconds. */
    private fun watchCounters() {
        counterJob?.cancel()
        counterJob = ShieldState.scope.launch {
            var shown = -1L
            ShieldState.today.collectLatest { (_, blocked) ->
                if (blocked != shown && ShieldState.phase.value == Phase.ON) {
                    shown = blocked
                    getSystemService(NotificationManager::class.java).notify(NOTE_ID, buildNote(blocked))
                    kotlinx.coroutines.delay(3000)
                }
            }
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, ShieldTile::class.java))
    }
}
