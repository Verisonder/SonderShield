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
import com.verisonder.sondershield.core.Root
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.ListStore
import com.verisonder.sondershield.data.Prefs
import com.verisonder.sondershield.ui.MainActivity
import com.verisonder.sondershield.ui.trc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The tunnel. By default only DNS is routed into it: the device's resolver is pointed at a
 * virtual address inside the tunnel and every other packet keeps its normal path, so
 * there is no speed cost and nothing else is touched.
 *
 * Full-tunnel mode routes everything in; the engine re-makes each connection from this
 * app (excluded from its own VPN), directly or through WireGuard.
 */
class ShieldVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.verisonder.sondershield.START"
        const val ACTION_STOP = "com.verisonder.sondershield.STOP"
        /** Rebuild the tunnel (bypass apps or IPv6 changed). */
        const val ACTION_RESTART = "com.verisonder.sondershield.RESTART"

        /** The phone's VPN settings, where Always-on VPN is turned off. */
        fun vpnSettingsIntent(): Intent =
            Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private const val CHANNEL = "shield"
        private const val NOTE_ID = 1

        const val V4_ADDR = "10.111.222.1"
        const val V4_DNS = "10.111.222.53"
        const val V6_ADDR = "fd00:5d5d::1"
        const val V6_DNS = "fd00:5d5d::53"
        private const val MTU = 1500

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

        /** The VPN permission dialog is still needed (never in root mode, which has no VPN). */
        fun needsConsent(context: Context): Boolean = !Prefs(context).rootMode && prepare(context) != null

        fun restartIfOn(context: Context) {
            if (ShieldState.phase.value != Phase.OFF) {
                context.startService(Intent(context, ShieldVpnService::class.java).setAction(ACTION_RESTART))
            }
        }
    }

    private val lock = Mutex()
    /** Root mode's iptables redirect is installed and must come out on stop. */
    @Volatile private var rootRules = false
    private var counterJob: Job? = null
    private var alwaysJob: Job? = null
    private var profileJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ShieldState.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs(this).wantOn = false // also when it comes from the notification or the widget
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
                if (intent?.action == SERVICE_INTERFACE || intent?.action == ACTION_START) Prefs(this).wantOn = true
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
        if (rootRules) {
            Root.clear()
            rootRules = false
        }
        if (prefs.rootMode) {
            bringUpRoot(prefs)
            return
        }
        val full = prefs.tunnelFull
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(V4_ADDR, 24)
            .addDnsServer(V4_DNS)
        if (full) {
            // Everything in, IPv6 included (else it would leak around a WireGuard server).
            b.addRoute("0.0.0.0", 0).addAddress(V6_ADDR, 64).addRoute("::", 0)
            if (prefs.ipv6) b.addDnsServer(V6_DNS)
            // The engine's own connections must go out on the real network.
            b.addDisallowedApplication(packageName)
        } else {
            b.addRoute(V4_DNS, 32)
            if (prefs.ipv6) b.addAddress(V6_ADDR, 64).addDnsServer(V6_DNS).addRoute(V6_DNS, 128)
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
            null.also { ShieldState.problem.value = trc(this, "Could not start the VPN: ", "Impossible de démarrer le VPN : ", "VPN konnte nicht gestartet werden: ") + e.message }
        }
        if (pfd == null) {
            if (ShieldState.problem.value == null) {
                ShieldState.problem.value = trc(this, "VPN permission missing. Open SonderShield and turn it on.", "Autorisation VPN manquante. Ouvrez SonderShield et activez-le.", "VPN-Berechtigung fehlt. Öffne SonderShield und schalte es ein.")
            }
            prefs.wantOn = false
            teardown()
            return
        }
        try {
            val fd = pfd.detachFd().toLong()
            if (full) ShieldState.engine.startFull(fd, MTU.toLong()) else ShieldState.engine.start(fd)
        } catch (e: Exception) {
            ShieldState.problem.value = trc(this, "Engine failed to start: ", "Le moteur n'a pas démarré : ", "Die Engine ist nicht gestartet: ") + e.message
            prefs.wantOn = false
            teardown()
            return
        }
        started()
    }

    /** Root mode: the engine serves DNS on loopback; iptables sends the phone's DNS there. */
    private fun bringUpRoot(prefs: Prefs) {
        try {
            ShieldState.engine.startLocal(Root.PORT.toLong())
        } catch (e: Exception) {
            ShieldState.problem.value = trc(this, "Engine failed to start: ", "Le moteur n'a pas démarré : ", "Die Engine ist nicht gestartet: ") + e.message
            prefs.wantOn = false
            teardown()
            return
        }
        val bypass = prefs.bypassApps.mapNotNull { runCatching { packageManager.getApplicationInfo(it, 0).uid }.getOrNull() }
        val blocked = prefs.firewallApps.mapNotNull { runCatching { packageManager.getApplicationInfo(it, 0).uid }.getOrNull() }
        val (code, out) = Root.apply(android.os.Process.myUid(), bypass, blocked)
        if (code != 0) {
            Root.clear()
            ShieldState.problem.value = trc(this, "Root mode failed: ", "Le mode root a échoué : ", "Root-Modus fehlgeschlagen: ") + out.trim().lines().lastOrNull().orEmpty().ifEmpty { "su refused" }
            prefs.wantOn = false
            teardown()
            return
        }
        rootRules = true
        started()
    }

    private fun started() {
        ShieldState.phase.value = Phase.ON
        if (ShieldState.startedAt.value == 0L) ShieldState.startedAt.value = System.currentTimeMillis()
        ShieldState.startPublishing()
        refreshTile()
        watchCounters()
        watchAlwaysOn()
        watchProfile()

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
        alwaysJob?.cancel()
        profileJob?.cancel()
        ShieldState.alwaysOn.value = false
        if (rootRules) {
            Root.clear()
            rootRules = false
        }
        ShieldState.engine.stop()
        ShieldState.stopPublishing()
        ShieldState.phase.value = Phase.OFF
        ShieldState.startedAt.value = 0L
        refreshTile()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Another VPN took over, or the user revoked us in Settings. */
    override fun onRevoke() {
        ShieldState.problem.value = trc(this, "Another VPN took over, or SonderShield was turned off in Settings.", "Un autre VPN a pris la main, ou SonderShield a été désactivé dans les réglages.", "Ein anderes VPN hat übernommen, oder SonderShield wurde in den Einstellungen ausgeschaltet.")
        Prefs(this).wantOn = false
        ShieldState.scope.launch { lock.withLock { teardown() } }
    }

    override fun onDestroy() {
        counterJob?.cancel()
        alwaysJob?.cancel()
        profileJob?.cancel()
        ShieldState.alwaysOn.value = false
        if (rootRules) {
            Root.clear()
            rootRules = false
        }
        if (ShieldState.phase.value != Phase.OFF) {
            ShieldState.engine.stop()
            ShieldState.stopPublishing()
            ShieldState.phase.value = Phase.OFF
            ShieldState.startedAt.value = 0L
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

    /** Keeps ShieldState.alwaysOn in step with the system setting while the VPN runs (the user
     *  can flip it in Settings at any time). */
    private fun watchAlwaysOn() {
        alwaysJob?.cancel()
        alwaysJob = ShieldState.scope.launch {
            while (true) {
                val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) !rootRules && isAlwaysOn else false
                if (v != ShieldState.alwaysOn.value) {
                    ShieldState.alwaysOn.value = v
                    refreshTile()
                }
                kotlinx.coroutines.delay(4000)
            }
        }
    }

    /** Switches lists when a profile schedule starts or ends. */
    private fun watchProfile() {
        profileJob?.cancel()
        profileJob = ShieldState.scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(20_000)
                val now = Prefs(this@ShieldVpnService).activeProfile().first
                if (now != ShieldState.appliedProfile) {
                    val store = ListStore(this@ShieldVpnService)
                    for (l in store.missingEnabled()) store.update(l)
                    ShieldState.applyConfig()
                    ShieldState.listsVersion.value++
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
        ShieldWidget.update(this)
    }
}
