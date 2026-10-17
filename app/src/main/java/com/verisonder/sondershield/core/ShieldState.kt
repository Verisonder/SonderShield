package com.verisonder.sondershield.core

import android.content.Context
import com.verisonder.sondershield.data.ListStore
import com.verisonder.sondershield.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LogEntry(
    val time: Long,
    val domain: String,
    val qtype: Int,
    /** 0 allowed, 1 blocked, 2 failed */
    val verdict: Int,
    val reason: String,
    val ms: Int,
) {
    val typeName: String get() = when (qtype) {
        1 -> "A"; 28 -> "AAAA"; 65 -> "HTTPS"; 64 -> "SVCB"; 5 -> "CNAME"; 12 -> "PTR"
        15 -> "MX"; 16 -> "TXT"; 33 -> "SRV"; 2 -> "NS"; 6 -> "SOA"; else -> "TYPE$qtype"
    }
}

enum class Phase { OFF, STARTING, ON }

/** The one engine and everything the screens watch. Lives as long as the process. */
object ShieldState {
    private lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: shield.Engine by lazy { shield.Shield.newEngine() }

    val phase = MutableStateFlow(Phase.OFF)
    /** Last problem worth showing (VPN refused, another VPN took over, ...). */
    val problem = MutableStateFlow<String?>(null)
    val domains = MutableStateFlow(0)
    val today = MutableStateFlow(0L to 0L)
    val log = MutableStateFlow<List<LogEntry>>(emptyList())
    /** Bumped when list files or their metadata change, so the lists screen re-reads. */
    val listsVersion = MutableStateFlow(0)
    /** Theme id from Settings; the activity recolours when it changes. */
    val theme = MutableStateFlow("default")
    /** Accent colour (ARGB) from Settings. */
    val accent = MutableStateFlow(0xFF3B82FF.toInt())

    private const val LOG_MAX = 2000
    private val ring = ArrayDeque<LogEntry>()
    private var ringDirty = false
    private val configLock = Mutex()
    private var publisher: Job? = null

    private val listener = object : shield.Listener {
        override fun onQuery(domain: String?, qtype: Long, verdict: Long, reason: String?, ms: Long) {
            val e = LogEntry(System.currentTimeMillis(), domain.orEmpty(), qtype.toInt(), verdict.toInt(), reason.orEmpty(), ms.toInt())
            synchronized(ring) {
                ring.addFirst(e)
                if (ring.size > LOG_MAX) ring.removeLast()
                ringDirty = true
            }
        }
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        engine.setListener(listener)
        today.value = Prefs(app).todayCounts()
        theme.value = Prefs(app).theme
        accent.value = Prefs(app).accent
    }

    /** Pushes rules, upstream and list files from settings into the engine. */
    suspend fun applyConfig() = withContext(Dispatchers.IO) {
        configLock.withLock {
            val prefs = Prefs(app)
            engine.setRules(prefs.blockRules, prefs.allowRules)
            try {
                engine.setUpstream(prefs.upstreamSpec())
            } catch (e: Exception) {
                problem.value = "DNS server setting is invalid: ${e.message}. Using Cloudflare."
                engine.setUpstream(Upstreams.byId(Upstreams.DEFAULT)!!.spec)
            }
            try {
                engine.setLists(ListStore(app).enabledPaths())
            } catch (_: Exception) {
                // A missing file is skipped by the engine; the lists screen shows the state.
            }
            domains.value = engine.domainCount().toInt()
        }
    }

    /** Rules only: cheap, no list reload. */
    fun applyRules() {
        val prefs = Prefs(app)
        engine.setRules(prefs.blockRules, prefs.allowRules)
    }

    fun startPublishing() {
        if (publisher?.isActive == true) return
        var lastTotal = engine.total()
        var lastBlocked = engine.blocked()
        publisher = scope.launch {
            val prefs = Prefs(app)
            var tick = 0
            while (isActive) {
                delay(700)
                synchronized(ring) {
                    if (ringDirty) {
                        log.value = ring.toList()
                        ringDirty = false
                    }
                }
                val t = engine.total()
                val b = engine.blocked()
                if (t != lastTotal || ++tick % 20 == 0) {
                    prefs.addToday(t - lastTotal, b - lastBlocked)
                    lastTotal = t
                    lastBlocked = b
                    today.value = prefs.todayCounts()
                }
            }
        }
    }

    fun stopPublishing() {
        publisher?.cancel()
        publisher = null
    }

    fun clearLog() {
        synchronized(ring) {
            ring.clear()
            ringDirty = false
        }
        log.value = emptyList()
    }

    fun resetStats() {
        Prefs(app).resetStats()
        today.value = 0L to 0L
    }

}
