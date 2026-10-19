package com.verisonder.sondershield.data

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/**
 * Reads this phone's own data counters through Android's NetworkStatsManager: device-wide
 * totals, split into mobile and Wi-Fi, sent and received. Needs the one-time "Usage access"
 * permission (granted in Settings, not a runtime prompt). Per-app totals come from the same
 * counters, keyed by app uid.
 */
object DataUsage {

    data class Usage(val rx: Long, val tx: Long) {
        val total: Long get() = rx + tx
    }

    /** Whether the user has granted Usage access to SonderShield. */
    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Opens the system screen where the user turns Usage access on for SonderShield. */
    fun openAccessSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun bytes(ctx: Context, type: Int, start: Long, end: Long): Usage {
        return try {
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val b = nsm.querySummaryForDevice(type, null, start, end)
            Usage(b.rxBytes, b.txBytes)
        } catch (e: Throwable) {
            Usage(0, 0)
        }
    }

    /** Bytes per app uid between [start] and [end] on one network type. Empty if it can't be read. */
    private fun perUid(ctx: Context, type: Int, start: Long, end: Long): Map<Int, Usage> {
        val out = HashMap<Int, LongArray>()
        try {
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val st = nsm.querySummary(type, null, start, end)
            val b = NetworkStats.Bucket()
            try {
                while (st.hasNextBucket()) {
                    st.getNextBucket(b)
                    val a = out.getOrPut(b.uid) { LongArray(2) }
                    a[0] += b.rxBytes
                    a[1] += b.txBytes
                }
            } finally {
                st.close()
            }
        } catch (_: Throwable) {
        }
        return out.mapValues { Usage(it.value[0], it.value[1]) }
    }

    @Suppress("DEPRECATION")
    fun mobileByUid(ctx: Context, start: Long, end: Long): Map<Int, Usage> =
        perUid(ctx, ConnectivityManager.TYPE_MOBILE, start, end)

    @Suppress("DEPRECATION")
    fun wifiByUid(ctx: Context, start: Long, end: Long): Map<Int, Usage> =
        perUid(ctx, ConnectivityManager.TYPE_WIFI, start, end)

    @Suppress("DEPRECATION")
    fun mobile(ctx: Context, start: Long, end: Long): Usage =
        bytes(ctx, ConnectivityManager.TYPE_MOBILE, start, end)

    @Suppress("DEPRECATION")
    fun wifi(ctx: Context, start: Long, end: Long): Usage =
        bytes(ctx, ConnectivityManager.TYPE_WIFI, start, end)

    /** Midnight at the start of today, in millis. */
    fun startOfToday(): Long = midnight(0)

    /** Midnight [daysAgo] days before today. */
    fun midnight(daysAgo: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return c.timeInMillis
    }
}
