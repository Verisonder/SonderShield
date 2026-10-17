package com.verisonder.sondershield.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.verisonder.sondershield.data.Prefs
import com.verisonder.sondershield.service.ShieldVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class AppItem(val pkg: String, val label: String, val icon: ImageBitmap?, val system: Boolean)

/** Loaded once per process: icons are the slow part. */
private var appCache: List<AppItem>? = null

private fun loadApps(ctx: Context): List<AppItem> {
    appCache?.let { return it }
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val px = (40 * ctx.resources.displayMetrics.density).toInt()
    val list = pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != ctx.packageName }
        .map { ai ->
            val icon = try { ai.loadIcon(pm).toBitmap(px, px).asImageBitmap() } catch (_: Exception) { null }
            AppItem(ai.packageName, ai.loadLabel(pm).toString(), icon, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        }
        .sortedBy { it.label.lowercase() }
    appCache = list
    return list
}

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var apps by remember { mutableStateOf(appCache) }
    var bypass by remember { mutableStateOf(prefs.bypassApps) }
    var blocked by remember { mutableStateOf(prefs.firewallApps) }
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 bypass, 1 firewall
    val firewallWorks = prefs.tunnelFull || prefs.rootMode
    var query by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (apps == null) apps = withContext(Dispatchers.IO) { loadApps(ctx) }
    }
    // Rebuild the tunnel once the user stops tapping, not on every switch.
    LaunchedEffect(pending) {
        if (pending > 0) {
            delay(1200)
            ShieldVpnService.restartIfOn(ctx)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            tr("Apps", "Applis", "Apps"),
            kicker = if (mode == 0) tr("Bypass", "Exceptions", "Ausnahmen") else tr("Firewall", "Pare-feu", "Firewall"),
            sub = if (mode == 0) tr(
                "Apps switched on here skip SonderShield, so their ads aren't blocked. Use it for an app that breaks.",
                "Les applis activées ici contournent SonderShield : leurs pubs ne sont pas bloquées. Utile pour une appli qui ne marche plus.",
                "Hier eingeschaltete Apps umgehen SonderShield, ihre Werbung wird also nicht blockiert. Nützlich für eine App, die nicht mehr geht.",
            ) else tr(
                "Apps switched on here get no internet at all, on Wi-Fi and mobile data.",
                "Les applis activées ici n'ont plus du tout accès à internet, en Wi-Fi comme en données mobiles.",
                "Hier eingeschaltete Apps bekommen gar kein Internet mehr, weder im WLAN noch mobil.",
            ),
        )
        ChipRow(Modifier.padding(top = 4.dp)) {
            Chip(tr("Bypass", "Exceptions", "Ausnahmen"), mode == 0) { mode = 0 }
            Chip(tr("No internet", "Sans internet", "Kein Internet"), mode == 1) { mode = 1 }
        }
        if (mode == 1 && !firewallWorks) {
            Text(
                tr(
                    "The firewall needs Full tunnel or Root mode (Settings › Tunnel). Until then these apps keep their internet.",
                    "Le pare-feu a besoin du tunnel complet ou du mode root (Réglages › Tunnel). D'ici là, ces applis gardent internet.",
                    "Die Firewall braucht den vollen Tunnel oder den Root-Modus (Einstellungen › Tunnel). Bis dahin behalten diese Apps ihr Internet.",
                ),
                color = Palette.warn, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = Gutter).padding(top = 10.dp),
            )
        }
        Field(query, { query = it }, tr("Search apps", "Rechercher une appli", "Apps suchen"), Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 10.dp), leading = Icons.search)
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent) }
            return@Column
        }
        val set = if (mode == 0) bypass else blocked
        // Switched-on apps first, then the rest alphabetically.
        val shown = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
            .sortedBy { if (it.pkg in set) 0 else 1 }
        if (set.isNotEmpty() && query.isBlank()) {
            Text(
                if (mode == 0) tr("${bypass.size} bypassing", "${bypass.size} en exception", "${bypass.size} ausgenommen")
                else tr("${blocked.size} without internet", "${blocked.size} sans internet", "${blocked.size} ohne Internet"), color = Palette.accentText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(horizontal = Gutter).padding(top = 14.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
            items(shown, key = { it.pkg }) { a ->
                val on = a.pkg in set
                // An app is either bypassing or cut off, not both.
                fun flip() {
                    if (mode == 0) {
                        bypass = if (on) bypass - a.pkg else bypass + a.pkg
                        if (!on) blocked = blocked - a.pkg
                    } else {
                        blocked = if (on) blocked - a.pkg else blocked + a.pkg
                        if (!on) bypass = bypass - a.pkg
                    }
                    prefs.bypassApps = bypass
                    prefs.firewallApps = blocked
                    pending++
                }
                Column(Modifier.fillMaxWidth().clickable { flip() }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (a.icon != null) Image(a.icon, null, Modifier.size(38.dp)) else Spacer(Modifier.size(38.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.pkg, color = Palette.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Toggle(on) { flip() }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.7f)))
                }
            }
        }
    }
}
