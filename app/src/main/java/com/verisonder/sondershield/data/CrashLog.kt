package com.verisonder.sondershield.data

import android.content.Context
import android.os.Build
import com.verisonder.sondershield.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone (nothing is sent anywhere). Settings shows it and can
 * share it, so a test build's crash can be read without a computer.
 */
object CrashLog {
    private var installed = false

    fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(app).writeText(
                    "SonderShield ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})\n" +
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " · thread ${t.name}\n\n" + sw,
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? = file(ctx).takeIf { it.isFile }?.readText()

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
