package com.verisonder.sondershield.core

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * A ready-made set of block lists, so someone who doesn't know which lists to pick can
 * choose "Default", "Strict", "Family"... Same ids as the Windows app (profiles.go).
 * A profile only decides which lists are on; rules, apps and everything else stay.
 * Built-in ones add the regional lists for the phone's languages; ones you make hold
 * exactly the lists you picked.
 */
data class Profile(
    val id: String,
    val name: String,
    val about: String = "",
    val icon: String = "star",
    val lists: Set<String>,
    val regional: Boolean = false,
    val custom: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("icon", icon).put("lists", JSONArray(lists.toList()))

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            id = o.optString("id"),
            name = o.optString("name"),
            icon = o.optString("icon", "star"),
            lists = o.optJSONArray("lists")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
            custom = true,
        )
    }
}

/**
 * Turns a profile on during a time window on the chosen days. [days] is a bit mask
 * (bit 0 = Sunday … bit 6 = Saturday); [start] and [end] are minutes after midnight, and a
 * window whose end is not after its start runs past midnight into the next day.
 */
data class Schedule(
    val id: String,
    val profile: String,
    val days: Int,
    val start: Int,
    val end: Int,
    val on: Boolean = true,
) {
    fun covers(t: LocalDateTime): Boolean {
        if (!on || days == 0) return false
        val m = t.hour * 60 + t.minute
        val day = t.dayOfWeek.value % 7 // Monday 1 … Sunday 0
        fun has(d: Int) = days and (1 shl ((d + 7) % 7)) != 0
        return if (end > start) has(day) && m >= start && m < end
        else (has(day) && m >= start) || (has(day - 1) && m < end)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("profile", profile).put("days", days).put("start", start).put("end", end).put("on", on)

    companion object {
        fun fromJson(o: JSONObject) = Schedule(
            o.optString("id"), o.optString("profile"), o.optInt("days"), o.optInt("start"), o.optInt("end"), o.optBoolean("on", true),
        )
    }
}

object Profiles {
    const val DEFAULT = "default"
    /** "Your own picks": the lists switched on by hand in the Lists screen. */
    const val OWN = ""

    private val base = setOf("stevenblack", "adguard-dns", "urlhaus")
    private val family = setOf("hagezi-nsfw", "hagezi-gambling")
    private val strict: Set<String>
        get() {
            val s = base + setOf("hagezi-pro", "adguard-tracking", "phishing-army")
            // On Xiaomi phones also block the system's own telemetry.
            val maker = Build.MANUFACTURER.lowercase()
            return if (maker in setOf("xiaomi", "redmi", "poco")) s + "hagezi-xiaomi" else s
        }

    val builtin: List<Profile>
        get() = listOf(
            Profile(DEFAULT, "Default", "Balanced blocking: basic ads and trackers", "shield", base, regional = true),
            Profile("strict", "Strict", "Aggressive blocking: ads, trackers, analytics, telemetry", "strict", strict, regional = true),
            Profile("family", "Family", "Safe browsing: ads, adult content, gambling", "family", base + family, regional = true),
            Profile("gaming", "Gaming", "Light blocking: basic ads only, fewest broken games", "game", setOf("oisd-small", "urlhaus")),
            Profile("strict-family", "Strict Family", "Maximum blocking: ads, trackers, adult content & gambling", "lock", strict + family, regional = true),
        )

    fun parseProfiles(json: String): List<Profile> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { Profile.fromJson(a.getJSONObject(it)) }.filter { it.id.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun parseSchedules(json: String): List<Schedule> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { Schedule.fromJson(a.getJSONObject(it)) }.filter { it.id.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun profilesJson(ps: List<Profile>): String = JSONArray(ps.map { it.toJson() }).toString()
    fun schedulesJson(ss: List<Schedule>): String = JSONArray(ss.map { it.toJson() }).toString()
}
