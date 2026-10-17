package com.verisonder.sondershield.data

import android.content.Context
import android.content.SharedPreferences
import com.verisonder.sondershield.core.BlockList
import com.verisonder.sondershield.core.Catalog
import com.verisonder.sondershield.core.Upstreams
import java.time.LocalDate

/** Everything the user sets, in one SharedPreferences file. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("shield", Context.MODE_PRIVATE)

    /** The user's wish: protection on. Survives reboots and app updates. */
    var wantOn: Boolean
        get() = sp.getBoolean("on", false)
        set(v) = sp.edit().putBoolean("on", v).apply()

    var enabledLists: Set<String>
        get() = sp.getStringSet("lists.enabled", null)?.toSet() ?: firstRunLists().also { enabledLists = it }
        set(v) = sp.edit().putStringSet("lists.enabled", v).apply()

    /** First run: the defaults plus regional lists for the phone's languages. */
    private fun firstRunLists(): Set<String> {
        val locales = android.content.res.Resources.getSystem().configuration.locales
        val langs = (0 until locales.size()).map { locales[it].language }
        val country = if (locales.size() > 0) locales[0].country else ""
        return Catalog.defaultsFor(langs, country)
    }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** Custom lists, one "name|url" per line. */
    var customLists: List<BlockList>
        get() = sp.getString("lists.custom", "").orEmpty().lines().mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) null else {
                val url = line.substring(i + 1).trim()
                BlockList("custom-" + url.hashCode().toUInt().toString(16), line.substring(0, i).trim(), url, url)
            }
        }
        set(v) = sp.edit().putString("lists.custom", v.joinToString("\n") { "${it.name}|${it.url}" }).apply()

    fun allLists(): List<BlockList> = Catalog.lists + customLists

    var upstreamId: String
        get() = sp.getString("upstream", Upstreams.DEFAULT) ?: Upstreams.DEFAULT
        set(v) = sp.edit().putString("upstream", v).apply()

    var customUpstream: String
        get() = sp.getString("upstream.custom", "") ?: ""
        set(v) = sp.edit().putString("upstream.custom", v).apply()

    fun upstreamSpec(): String =
        if (upstreamId == Upstreams.CUSTOM && customUpstream.isNotBlank()) customUpstream
        else (Upstreams.byId(upstreamId) ?: Upstreams.byId(Upstreams.DEFAULT)!!).spec

    var blockRules: String
        get() = sp.getString("rules.block", "") ?: ""
        set(v) = sp.edit().putString("rules.block", v).apply()

    var allowRules: String
        get() = sp.getString("rules.allow", "") ?: ""
        set(v) = sp.edit().putString("rules.allow", v).apply()

    var bypassApps: Set<String>
        get() = sp.getStringSet("bypass", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("bypass", v).apply()

    /** Firewall: apps with no internet at all (full tunnel or root mode). */
    var firewallApps: Set<String>
        get() = sp.getStringSet("firewall", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("firewall", v).apply()

    var ipv6: Boolean
        get() = sp.getBoolean("ipv6", true)
        set(v) = sp.edit().putBoolean("ipv6", v).apply()

    // Data plan: total mobile allowance, when the cycle started, and how many days it lasts.
    // All three > 0 means a plan is set up.
    var planTotalBytes: Long
        get() = sp.getLong("plan.total", 0L)
        set(v) = sp.edit().putLong("plan.total", v).apply()

    var planStartMillis: Long
        get() = sp.getLong("plan.start", 0L)
        set(v) = sp.edit().putLong("plan.start", v).apply()

    var planDays: Int
        get() = sp.getInt("plan.days", 0)
        set(v) = sp.edit().putInt("plan.days", v).apply()

    val planActive: Boolean
        get() = planTotalBytes > 0 && planStartMillis > 0 && planDays > 0

    /** Route all traffic through the engine, not only DNS (needed for WireGuard). */
    var fullTunnel: Boolean
        get() = sp.getBoolean("tunnel.full", false)
        set(v) = sp.edit().putBoolean("tunnel.full", v).apply()

    /** Imported WireGuard config (wg-quick text), "" for none. Kept out of backups: it holds a private key. */
    var wgConfig: String
        get() = sp.getString("wg.config", "") ?: ""
        set(v) = sp.edit().putString("wg.config", v).apply()

    var wgName: String
        get() = sp.getString("wg.name", "") ?: ""
        set(v) = sp.edit().putString("wg.name", v).apply()

    /** Send full-tunnel traffic through the imported WireGuard server. */
    var wgOn: Boolean
        get() = sp.getBoolean("wg.on", false)
        set(v) = sp.edit().putBoolean("wg.on", v).apply()

    /** Root mode: no VPN; iptables sends DNS to the engine's local server. */
    var rootMode: Boolean
        get() = sp.getBoolean("root", false)
        set(v) = sp.edit().putBoolean("root", v).apply()

    /** HTTPS filtering in the chosen apps (needs the full tunnel and the CA installed). */
    var httpsOn: Boolean
        get() = sp.getBoolean("https.on", false)
        set(v) = sp.edit().putBoolean("https.on", v).apply()

    /** SonderShield's CA, certificate and key as PEM. Made once, kept out of backups. */
    var caPem: String
        get() = sp.getString("https.ca", "") ?: ""
        set(v) = sp.edit().putString("https.ca", v).apply()

    /** Apps whose HTTPS is filtered; null until the user (or the first switch-on) picks. */
    var httpsApps: Set<String>?
        get() = sp.getStringSet("https.apps", null)?.toSet()
        set(v) = sp.edit().putStringSet("https.apps", v).apply()

    /** Your own never-filter domains, one per line. */
    var httpsSkip: String
        get() = sp.getString("https.skip", "") ?: ""
        set(v) = sp.edit().putString("https.skip", v).apply()

    /** The tunnel carries everything (full tunnel or HTTPS filtering), not only DNS. */
    val tunnelFull: Boolean get() = !rootMode && (fullTunnel || httpsOn)

    var startOnBoot: Boolean
        get() = sp.getBoolean("boot", true)
        set(v) = sp.edit().putBoolean("boot", v).apply()

    /** default, light, oled, you */
    var theme: String
        get() = sp.getString("theme", "default") ?: "default"
        set(v) = sp.edit().putString("theme", v).apply()

    /** Accent colour (ARGB). Default: electric blue. */
    var accent: Int
        get() = sp.getInt("accent", 0xFF3B82FF.toInt())
        set(v) = sp.edit().putInt("accent", v).apply()

    /** 0 = never. */
    var updateHours: Int
        get() = sp.getInt("update.hours", 24)
        set(v) = sp.edit().putInt("update.hours", v).apply()

    // List file metadata: entries, last download time (ms), ETag, Last-Modified, last error.
    fun listMeta(id: String): ListMeta {
        val p = sp.getString("meta.$id", null)?.split('\u0001') ?: return ListMeta()
        return ListMeta(
            p.getOrNull(0)?.toIntOrNull() ?: 0, p.getOrNull(1)?.toLongOrNull() ?: 0L,
            p.getOrNull(2).orEmpty(), p.getOrNull(3).orEmpty(), p.getOrNull(4).orEmpty(),
        )
    }

    fun setListMeta(id: String, m: ListMeta) {
        sp.edit().putString("meta.$id", listOf(m.entries, m.updated, m.etag, m.lastModified, m.error).joinToString("\u0001")).apply()
    }

    // Today's counters. Kept per day so "today" resets at midnight.
    fun todayCounts(): Pair<Long, Long> {
        val today = LocalDate.now().toString()
        if (sp.getString("stats.day", null) != today) return 0L to 0L
        return sp.getLong("stats.total", 0L) to sp.getLong("stats.blocked", 0L)
    }

    fun addToday(total: Long, blocked: Long) {
        val today = LocalDate.now().toString()
        val (t, b) = todayCounts()
        sp.edit().putString("stats.day", today)
            .putLong("stats.total", t + total).putLong("stats.blocked", b + blocked).apply()
        sp.edit().putLong("stats.all.blocked", sp.getLong("stats.all.blocked", 0L) + blocked).apply()
    }

    val allTimeBlocked: Long get() = sp.getLong("stats.all.blocked", 0L)

    fun resetStats() {
        sp.edit().remove("stats.day").remove("stats.total").remove("stats.blocked").remove("stats.all.blocked").apply()
    }
}

data class ListMeta(
    val entries: Int = 0,
    val updated: Long = 0L,
    val etag: String = "",
    val lastModified: String = "",
    val error: String = "",
)
