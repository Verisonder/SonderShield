package com.verisonder.sondershield.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
    var query by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf(0) }

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
        ScreenTitle("Apps")
        Text(
            "Apps switched on here skip SonderShield: their ads aren't blocked. Use it for an app that breaks.",
            color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
        Field(query, { query = it }, "Search apps", Modifier.fillMaxWidth().padding(horizontal = 16.dp), leading = Icons.search)
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent) }
            return@Column
        }
        // Bypassing apps first, then the rest alphabetically.
        val shown = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
            .sortedBy { if (it.pkg in bypass) 0 else 1 }
        LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
            items(shown, key = { it.pkg }) { a ->
                val on = a.pkg in bypass
                fun flip() {
                    bypass = if (on) bypass - a.pkg else bypass + a.pkg
                    prefs.bypassApps = bypass
                    pending++
                }
                Row(
                    Modifier.fillMaxWidth().clickable { flip() }.padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (a.icon != null) Image(a.icon, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.pkg, color = Palette.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(
                        checked = on, onCheckedChange = { flip() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.bg, checkedTrackColor = Palette.warn,
                            uncheckedThumbColor = Palette.dim, uncheckedTrackColor = Palette.cardHi, uncheckedBorderColor = Palette.line,
                        ),
                    )
                }
            }
        }
    }
}
