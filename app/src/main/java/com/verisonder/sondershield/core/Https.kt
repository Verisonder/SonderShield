package com.verisonder.sondershield.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.security.KeyStore

/** HTTPS filtering helpers: the browsers on this phone and the CA's install state. */
object Https {
    data class Browser(val pkg: String, val label: String)

    /** Apps that open web links, this one excluded. */
    fun browsers(ctx: Context): List<Browser> {
        val pm = ctx.packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        return pm.queryIntentActivities(view, android.content.pm.PackageManager.MATCH_ALL)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .map { Browser(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    fun uids(ctx: Context, pkgs: Collection<String>): String = pkgs.mapNotNull {
        try { ctx.packageManager.getApplicationInfo(it, 0).uid } catch (_: Exception) { null }
    }.distinct().joinToString(",")

    fun certDer(ca: String): ByteArray? = shield.Shield.certDER(ca).takeIf { it.isNotEmpty() }?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }

    /** Is our CA among the user's trusted certificates? */
    fun installed(ca: String): Boolean {
        val der = certDer(ca) ?: return false
        return try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            ks.aliases().toList().any { it.startsWith("user:") && ks.getCertificate(it)?.encoded?.contentEquals(der) == true }
        } catch (_: Exception) {
            false
        }
    }

    /** Firefox keeps its own certificate store unless told to use the phone's. */
    fun isFirefox(pkg: String) = pkg.startsWith("org.mozilla.") || pkg == "io.github.forkmaintainers.iceraven"
}
