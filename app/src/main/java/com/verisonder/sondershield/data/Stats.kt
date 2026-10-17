package com.verisonder.sondershield.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the stats part of Home shows. Immutable; a new one is published as numbers change. */
data class StatsSnapshot(
    val allTotal: Long = 0,
    val allBlocked: Long = 0,
    val allThreats: Long = 0,
    /** Last 168 hours, oldest first: (queries, blocked) per hour. Index 167 is this hour. */
    val hours: List<Pair<Int, Int>> = List(HOURS) { 0 to 0 },
    /** Most blocked domains, most first. */
    val top: List<Pair<String, Int>> = emptyList(),
) {
    companion object { const val HOURS = 168 }
}

/**
 * Long-running counters: all-time totals, hourly history for the chart, and how often each
 * domain was blocked. Kept in memory, written to a small JSON file every few seconds.
 * Thread-safe: queries arrive from engine threads.
 */
class Stats(context: Context) {
    private val file = File(context.filesDir, "stats.json")
    private val lock = Any()

    private var allTotal = 0L
    private var allBlocked = 0L
    private var allThreats = 0L
    private val hours = HashMap<Long, IntArray>() // epoch hour -> [queries, blocked]
    private val top = HashMap<String, Int>()
    private var dirty = false

    init {
        load(Prefs(context))
    }

    fun record(domain: String, blocked: Boolean, threat: Boolean, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            allTotal++
            val h = hours.getOrPut(now / HOUR) { IntArray(2) }
            h[0]++
            if (blocked) {
                allBlocked++
                h[1]++
                if (threat) allThreats++
                top[domain] = (top[domain] ?: 0) + 1
                if (top.size > TOP_KEEP * 2) trimTop()
            }
            dirty = true
        }
    }

    fun snapshot(now: Long = System.currentTimeMillis()): StatsSnapshot = synchronized(lock) {
        val nowH = now / HOUR
        val hs = (0 until StatsSnapshot.HOURS).map { i ->
            val v = hours[nowH - (StatsSnapshot.HOURS - 1 - i)]
            (v?.get(0) ?: 0) to (v?.get(1) ?: 0)
        }
        StatsSnapshot(
            allTotal, allBlocked, allThreats, hs,
            top.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value },
        )
    }

    /** Writes to disk when something changed. Returns true when it wrote. */
    fun save(): Boolean {
        val json = synchronized(lock) {
            if (!dirty) return false
            dirty = false
            prune(System.currentTimeMillis() / HOUR)
            JSONObject().apply {
                put("v", 1)
                put("total", allTotal); put("blocked", allBlocked); put("threats", allThreats)
                put("hours", JSONArray().apply { hours.forEach { (k, v) -> put(JSONArray().put(k).put(v[0]).put(v[1])) } })
                put("top", JSONObject().apply { top.forEach { (k, v) -> put(k, v) } })
            }.toString()
        }
        return try {
            val tmp = File(file.parentFile, "stats.json.tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        } catch (_: Exception) {
            false
        }
    }

    fun reset() {
        synchronized(lock) {
            allTotal = 0; allBlocked = 0; allThreats = 0
            hours.clear(); top.clear()
            dirty = true
        }
        save()
    }

    private fun load(prefs: Prefs) {
        try {
            if (!file.isFile) {
                // First run with this file: carry over the counter that existed before it.
                allBlocked = prefs.allTimeBlocked
                allTotal = prefs.todayCounts().first
                return
            }
            val o = JSONObject(file.readText())
            allTotal = o.optLong("total"); allBlocked = o.optLong("blocked"); allThreats = o.optLong("threats")
            o.optJSONArray("hours")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.getJSONArray(i)
                    hours[e.getLong(0)] = intArrayOf(e.getInt(1), e.getInt(2))
                }
            }
            o.optJSONObject("top")?.let { t -> t.keys().forEach { k -> top[k] = t.optInt(k) } }
        } catch (_: Exception) {
            // A broken file starts the stats over rather than crashing.
        }
    }

    private fun prune(nowH: Long) {
        hours.keys.removeAll { it < nowH - StatsSnapshot.HOURS }
    }

    private fun trimTop() {
        val keep = top.entries.sortedByDescending { it.value }.take(TOP_KEEP).associate { it.key to it.value }
        top.clear()
        top.putAll(keep)
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val TOP_KEEP = 1000
        /** Lists whose hits count as threats (malware, phishing) rather than ads. */
        val THREAT_LISTS = setOf("urlhaus", "hagezi-tif", "rpilist-malware", "phishing-army")
    }
}
