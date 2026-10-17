package com.verisonder.sondershield.core

import android.content.Context
import android.os.Process

/** App labels by uid, for the log. Remembered for the life of the process. */
object AppNames {
    private val cache = HashMap<Int, String?>()

    /** The app's name, or null for system parts (DNS resolver, kernel) and unknown uids. */
    fun label(ctx: Context, uid: Int): String? {
        if (uid < Process.FIRST_APPLICATION_UID || uid == Process.myUid()) return null
        synchronized(cache) { if (uid in cache) return cache[uid] }
        val pm = ctx.packageManager
        val name = try {
            val pkgs = pm.getPackagesForUid(uid).orEmpty()
            when {
                pkgs.isEmpty() -> null
                pkgs.size == 1 -> pm.getApplicationInfo(pkgs[0], 0).loadLabel(pm).toString()
                else -> pm.getNameForUid(uid)?.substringBefore(':') ?: pkgs[0]
            }
        } catch (_: Exception) {
            null
        }
        synchronized(cache) { cache[uid] = name }
        return name
    }
}
