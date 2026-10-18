package com.verisonder.sondershield.data

import android.content.Context
import android.content.SharedPreferences
import com.verisonder.sondershield.core.BlockList
import com.verisonder.sondershield.core.Catalog
import com.verisonder.sondershield.core.Profile
import com.verisonder.sondershield.core.Profiles
import com.verisonder.sondershield.core.Schedule
import com.verisonder.sondershield.core.Upstreams
import java.time.LocalDate
import java.time.LocalDateTime

/** Everything the user sets, in one SharedPreferences file. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("shield", Context.MODE_PRIVATE)

    init {
        // Profiles arrived in 2.5.0: a new install starts on Default; an existing one keeps
        // the lists its owner picked.
        if (!sp.contains("profile")) {
            sp.edit().putString("profile", if (sp.getBoolean("onboarded", false)) Profiles.OWN else Profiles.DEFAULT).apply()
        }
    }

    /** The user's wish: protection on. Survives reboots and app updates. */
    var wantOn: Boolean
        get() = sp.getBoolean("on", false)
        set(v) = sp.edit().putBoolean("on", v).apply()

    var enabledLists: Set<String>
        get() = sp.getStringSet("lists.enabled", null)?.toSet() ?: firstRunLists().also { enabledLists = it }
        set(v) = sp.edit().putStringSet("lists.enabled", v).apply()

    /** First run: the defaults plus regional lists for the phone's languages. */
    private fun firstRunLists(): Set<String> {
        val (langs, country) = phoneLocale()
        return Catalog.defaultsFor(langs, country)
    }

    private fun phoneLocale(): Pair<List<String>, String> {
        val locales = android.content.res.Resources.getSystem().configuration.locales
        val langs = (0 until locales.size()).map { locales[it].language }
        val country = if (locales.size() > 0) locales[0].country else ""
        return langs to country
    }

    /** The profile you chose; [Profiles.OWN] ("") means your own list picks. */
    var profileId: String
        get() = sp.getString("profile", Profiles.DEFAULT) ?: Profiles.DEFAULT
        set(v) = sp.edit().putString("profile", v).apply()

    /** Profiles you made. */
    var customProfiles: List<Profile>
        get() = Profiles.parseProfiles(sp.getString("profiles", "[]") ?: "[]")
        set(v) = sp.edit().putString("profiles", Profiles.profilesJson(v)).apply()

    var schedules: List<Schedule>
        get() = Profiles.parseSchedules(sp.getString("schedules", "[]") ?: "[]")
        set(v) = sp.edit().putString("schedules", Profiles.schedulesJson(v)).apply()

    fun allProfiles(): List<Profile> = Profiles.builtin + customProfiles

    fun profileById(id: String): Profile? = allProfiles().firstOrNull { it.id == id }

    /** The profile in use at [t]: a running schedule wins, else your choice. Second = by schedule. */
    fun activeProfile(t: LocalDateTime = LocalDateTime.now()): Pair<String, Boolean> {
        val all = allProfiles().map { it.id }.toSet()
        schedules.firstOrNull { it.covers(t) && it.profile in all }?.let { return it.profile to true }
        val id = profileId
        return (if (id in all) id else Profiles.OWN) to false
    }

    /** The lists a profile turns on here (with the regional lists for the phone's languages). */
    fun profileLists(p: Profile): Set<String> {
        var s = p.lists
        if (p.regional) {
            val (langs, country) = phoneLocale()
            s = s + Catalog.regionalFor(langs, country)
        }
        if (httpsOn) s = s + Catalog.pageListIds
        return s
    }

    /** The list ids in use now: the active profile's, or your own picks. */
    fun activeLists(t: LocalDateTime = LocalDateTime.now()): Set<String> {
        val id = activeProfile(t).first
        val p = profileById(id) ?: return enabledLists
        return if (id == Profiles.OWN) enabledLists else profileLists(p)
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

    // Data plan (mobile data): a limit, and how the counter resets — "daily" (every midnight),
    // "monthly" (same date each month, counted from planStartMillis) or "custom" (from
    // planStartMillis to the end of the day planEndMillis falls on).
    var planTotalBytes: Long
        get() = sp.getLong("plan.total", 0L)
        set(v) = sp.edit().putLong("plan.total", v).apply()

    var planMode: String
        get() = sp.getString("plan.mode", "monthly") ?: "monthly"
        set(v) = sp.edit().putString("plan.mode", v).apply()

    var planStartMillis: Long
        get() = sp.getLong("plan.start", 0L)
        set(v) = sp.edit().putLong("plan.start", v).apply()

    var planEndMillis: Long
        get() = sp.getLong("plan.end", 0L)
        set(v) = sp.edit().putLong("plan.end", v).apply()

    val planActive: Boolean
        get() = planTotalBytes > 0 && when (planMode) {
            "daily" -> true
            "custom" -> planStartMillis > 0 && planEndMillis >= planStartMillis
            else -> planStartMillis > 0
        }

    fun clearPlan() {
        sp.edit().remove("plan.total").remove("plan.mode").remove("plan.start").remove("plan.end").remove("plan.days").apply()
    }

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
