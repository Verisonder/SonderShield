package com.verisonder.sondershield.data

import android.content.Context
import android.net.Uri
import com.verisonder.sondershield.core.LogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings backup (JSON) and log export (CSV), written to files the user picks. */
object Backup {
    private const val FORMAT = "sondershield-settings"

    fun export(ctx: Context, uri: Uri) {
        val p = Prefs(ctx)
        val o = JSONObject().apply {
            put("format", FORMAT); put("version", 1)
            put("lists", JSONArray(p.enabledLists.toList()))
            put("customLists", JSONArray(p.customLists.map { JSONObject().put("name", it.name).put("url", it.url) }))
            put("upstream", p.upstreamId); put("customUpstream", p.customUpstream)
            put("blockRules", p.blockRules); put("allowRules", p.allowRules)
            put("bypass", JSONArray(p.bypassApps.toList()))
            put("ipv6", p.ipv6); put("fullTunnel", p.fullTunnel); put("boot", p.startOnBoot); put("updateHours", p.updateHours)
            put("theme", p.theme); put("accent", p.accent)
        }
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(o.toString(2).toByteArray()) }
    }

    /** Applies a backup. Throws with a readable message when the file isn't one. */
    fun import(ctx: Context, uri: Uri) {
        val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        val o = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("Not a SonderShield backup") }
        if (o.optString("format") != FORMAT) throw IllegalArgumentException("Not a SonderShield backup")
        val p = Prefs(ctx)
        o.optJSONArray("customLists")?.let { a ->
            p.customLists = (0 until a.length()).map { a.getJSONObject(it) }
                .map { com.verisonder.sondershield.core.BlockList("", it.optString("name"), it.optString("url"), it.optString("url")) }
        }
        o.optJSONArray("lists")?.let { a -> p.enabledLists = (0 until a.length()).map { a.getString(it) }.toSet() }
        if (o.has("upstream")) p.upstreamId = o.getString("upstream")
        if (o.has("customUpstream")) p.customUpstream = o.getString("customUpstream")
        if (o.has("blockRules")) p.blockRules = o.getString("blockRules")
        if (o.has("allowRules")) p.allowRules = o.getString("allowRules")
        o.optJSONArray("bypass")?.let { a -> p.bypassApps = (0 until a.length()).map { a.getString(it) }.toSet() }
        if (o.has("ipv6")) p.ipv6 = o.getBoolean("ipv6")
        if (o.has("fullTunnel")) p.fullTunnel = o.getBoolean("fullTunnel")
        if (o.has("boot")) p.startOnBoot = o.getBoolean("boot")
        if (o.has("updateHours")) p.updateHours = o.getInt("updateHours")
        if (o.has("theme")) p.theme = o.getString("theme")
        if (o.has("accent")) p.accent = o.getInt("accent")
    }

    fun exportLog(ctx: Context, uri: Uri, log: List<LogEntry>) {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        ctx.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { w ->
            w.write("time,domain,type,result,reason,ms,app\n")
            for (e in log.asReversed()) {
                val result = when (e.verdict) { 1 -> "blocked"; 2 -> "failed"; else -> "allowed" }
                w.write("${f.format(Date(e.time))},${e.domain},${e.typeName},$result,\"${e.reason.replace("\"", "'")}\",${e.ms},${com.verisonder.sondershield.core.AppNames.label(ctx, e.uid).orEmpty().replace(",", " ")}\n")
            }
        }
    }

    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
}
