package com.verisonder.sondershield.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.verisonder.sondershield.BuildConfig
import com.verisonder.sondershield.R
import com.verisonder.sondershield.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * App updates from the GitHub releases (the same signed APK the release page has).
 * SonderShield checks by itself — when the app opens (at most every few hours) and once a
 * day in the background — and says so when a new version is out: a banner on Home, a dot on
 * Settings, and one notification per new version. Installing is one tap (Android asks to
 * confirm, as for any app update).
 */
object AppUpdate {

    data class Info(val version: String, val notes: String, val apkUrl: String, val size: Long)

    /** The newer version, when there is one (null = up to date or not checked yet). */
    val available = MutableStateFlow<Info?>(null)

    /** Bumped to open Settings › Updates (from the Home notice or the notification). */
    val openRequest = MutableStateFlow(0)

    /** Download progress 0..1 while an update downloads, else null. */
    val progress = MutableStateFlow<Float?>(null)

    private const val API = "https://api.github.com/repos/Verisonder/SonderShield/releases/latest"
    private const val CHANNEL = "updates"
    private const val NOTE_ID = 7301
    private const val PREFS = "app_update"
    private const val AUTO_EVERY_MS = 6 * 3_600_000L

    /** Checks if the last automatic check is old enough (on app open). */
    suspend fun autoCheck(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        restore(ctx)
        if (System.currentTimeMillis() - sp.getLong("checkedAt", 0L) < AUTO_EVERY_MS) return
        runCatching { check(ctx) }
    }

    /** Shows the last known result straight away, before any network. */
    fun restore(ctx: Context) {
        if (available.value != null) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = sp.getString("version", null) ?: return
        if (newer(v, BuildConfig.VERSION_NAME)) {
            available.value = Info(v, sp.getString("notes", "") ?: "", sp.getString("url", "") ?: "", sp.getLong("size", 0L))
        }
    }

    /** Asks GitHub for the latest release. Returns the newer version, or null if up to date. */
    suspend fun check(ctx: Context): Info? = withContext(Dispatchers.IO) {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "SonderShield")
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub returned ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val version = j.optString("tag_name").removePrefix("v")
            var url = ""
            var size = 0L
            val assets = j.optJSONArray("assets")
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                val name = a.optString("name")
                if (name.startsWith("SonderShield-") && name.endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                }
            }
            val info = if (url.isNotEmpty() && newer(version, BuildConfig.VERSION_NAME)) Info(version, j.optString("body"), url, size) else null
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putLong("checkedAt", System.currentTimeMillis())
                .putString("version", info?.version).putString("notes", info?.notes)
                .putString("url", info?.apkUrl).putLong("size", info?.size ?: 0L).apply()
            available.value = info
            if (info != null) notifyOnce(ctx, info)
            info
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the APK (progress in [progress]) and opens Android's installer. */
    suspend fun install(ctx: Context, info: Info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            // One-time switch: "Allow from this source".
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "SonderShield-${info.version}.apk")
            progress.value = 0f
            try {
                fun open(u: URL) = (u.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "SonderShield")
                }
                // GitHub answers with a redirect to its file host (sometimes two).
                var c = open(URL(info.apkUrl))
                var hops = 0
                while (c.responseCode in 300..399 && hops++ < 5) {
                    val next = URL(c.url, c.getHeaderField("Location"))
                    c.disconnect()
                    c = open(next)
                }
                if (c.responseCode != 200) throw IllegalStateException("Download failed (${c.responseCode})")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
                c.disconnect()
                out
            } catch (e: Exception) {
                out.delete()
                progress.value = null
                throw e
            }
        }
        progress.value = null
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** One notification per new version, so it never nags. */
    private fun notifyOnce(ctx: Context, info: Info) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getString("notified", null) == info.version) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = when (java.util.Locale.getDefault().language) {
            "fr" -> "SonderShield ${info.version} est disponible" to "Touchez pour mettre à jour."
            "de" -> "SonderShield ${info.version} ist verfügbar" to "Tippen zum Aktualisieren."
            else -> "SonderShield ${info.version} is available" to "Tap to update."
        }
        runCatching {
            NotificationManagerCompat.from(ctx).notify(
                NOTE_ID,
                NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_shield)
                    .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build(),
            )
        }
        sp.edit().putString("notified", info.version).apply()
    }

    /** a > b, comparing dotted numbers ("2.6.0" > "2.5.1"). */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size, 3)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** The daily background check. */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<CheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("app-update-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            runCatching { check(applicationContext) }
            return Result.success()
        }
    }
}
