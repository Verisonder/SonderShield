package com.verisonder.sondershield.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.BuildConfig
import com.verisonder.sondershield.core.BlockList
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.core.Upstreams
import com.verisonder.sondershield.data.ListStore
import com.verisonder.sondershield.data.Prefs
import com.verisonder.sondershield.service.ShieldVpnService
import com.verisonder.sondershield.service.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { ListStore(ctx) }
    val scope = rememberCoroutineScope()
    val phase by ShieldState.phase.collectAsState()
    val listsVersion by ShieldState.listsVersion.collectAsState()

    var upstream by remember { mutableStateOf(prefs.upstreamId) }
    var custom by remember { mutableStateOf(prefs.customUpstream) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(prefs.enabledLists) }
    var lists by remember { mutableStateOf(prefs.allLists()) }
    var updating by remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf(prefs.updateHours) }
    var ipv6 by remember { mutableStateOf(prefs.ipv6) }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var addList by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) } // re-read list metadata

    fun applyUpstream() {
        prefs.upstreamId = upstream
        prefs.customUpstream = custom.trim()
        testResult = null
        if (phase == Phase.ON) scope.launch { ShieldState.applyConfig() }
    }

    fun listsChanged(download: List<BlockList> = emptyList()) {
        prefs.enabledLists = enabled
        scope.launch {
            if (download.isNotEmpty()) {
                updating = true
                for (l in download) store.update(l)
                updating = false
                tick++
            }
            if (phase == Phase.ON) ShieldState.applyConfig()
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings")

        // --- Theme ---
        SectionLabel("Theme")
        val themeId by ShieldState.theme.collectAsState()
        ChipRow(Modifier.horizontalScroll(rememberScrollState())) {
            for (m in ThemeMode.entries) {
                if (m == ThemeMode.You && !ThemeMode.youAvailable) continue
                Chip(m.label, ThemeMode.of(themeId) == m) {
                    prefs.theme = m.id
                    ShieldState.theme.value = m.id
                }
            }
        }
        if (ThemeMode.of(themeId) == ThemeMode.You) {
            Text(
                "Colours from your wallpaper, light or dark with the system.",
                color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
            )
        }

        // --- DNS server ---
        SectionLabel("DNS server")
        Panel {
            for (u in Upstreams.all) {
                Row(Modifier.fillMaxWidth().clickable { upstream = u.id; applyUpstream() }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(upstream == u.id, onClick = { upstream = u.id; applyUpstream() }, colors = radioColors())
                    Text(u.name, color = Palette.text, fontSize = 14.sp)
                }
            }
            Row(Modifier.fillMaxWidth().clickable { upstream = Upstreams.CUSTOM }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(upstream == Upstreams.CUSTOM, onClick = { upstream = Upstreams.CUSTOM }, colors = radioColors())
                Text("Custom", color = Palette.text, fontSize = 14.sp)
            }
            if (upstream == Upstreams.CUSTOM) {
                Field(custom, { custom = it }, "udp 9.9.9.9  or  doh https://host/dns-query 1.2.3.4", Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri, onDone = ::applyUpstream)
                Text(
                    "DoH needs the server's IP addresses after the URL, so SonderShield can reach it without asking itself.",
                    color = Palette.dim, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp),
                )
                AccentButton("Save") { applyUpstream() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccentButton("Test") {
                    testResult = "Testing…"
                    scope.launch {
                        val spec = if (upstream == Upstreams.CUSTOM) custom.trim() else Upstreams.byId(upstream)!!.spec
                        testResult = withContext(Dispatchers.IO) {
                            try { "Answered in ${ShieldState.engine.testUpstream(spec)} ms" } catch (e: Exception) { "Failed: ${e.message}" }
                        }
                    }
                }
                testResult?.let { Text(it, color = if (it.startsWith("Failed")) Palette.blocked else Palette.dim, fontSize = 13.sp) }
            }
        }

        // --- Block lists ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionLabel("Block lists") }
            if (updating) {
                CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
            } else {
                AccentButton("Update now") {
                    scope.launch {
                        updating = true
                        val changed = store.updateEnabled(force = true)
                        updating = false
                        tick++
                        if (changed && phase == Phase.ON) ShieldState.applyConfig()
                    }
                }
            }
            IconButton(onClick = { addList = true }) { Icon(Icons.plus, "Add a list", tint = Palette.accent) }
        }
        // key(): metadata is read again whenever a download finishes.
        key(tick, listsVersion) { for (l in lists) {
            val on = l.id in enabled
            val meta = prefs.listMeta(l.id)
            val status = when {
                meta.error.isNotEmpty() -> "Error: ${meta.error}"
                meta.updated > 0 -> "${meta.entries.grouped()} entries · updated ${ago(meta.updated)}"
                on -> "Downloads when protection starts"
                else -> l.about
            }
            Panel(onClick = {
                enabled = if (on) enabled - l.id else enabled + l.id
                listsChanged(if (!on && !store.file(l.id).isFile) listOf(l) else emptyList())
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, color = Palette.text, fontSize = 15.sp)
                        if (meta.updated > 0 || meta.error.isNotEmpty()) {
                            Text(l.about, color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2)
                        }
                        Text(status, color = if (meta.error.isNotEmpty()) Palette.warn else Palette.dim, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    if (l.id.startsWith("custom-")) {
                        IconButton(onClick = {
                            prefs.customLists = prefs.customLists.filter { it.id != l.id }
                            store.delete(l.id)
                            enabled = enabled - l.id
                            lists = prefs.allLists()
                            listsChanged()
                        }) { Icon(Icons.trash, "Remove list", tint = Palette.dim, modifier = Modifier.size(18.dp)) }
                    }
                    Switch(
                        checked = on,
                        onCheckedChange = { v ->
                            enabled = if (v) enabled + l.id else enabled - l.id
                            listsChanged(if (v && !store.file(l.id).isFile) listOf(l) else emptyList())
                        },
                        colors = switchColors(),
                    )
                }
            }
        } }

        SectionLabel("Update lists automatically")
        ChipRow {
            for ((h, label) in listOf(0 to "Off", 12 to "12 h", 24 to "24 h", 48 to "48 h")) {
                Chip(label, hours == h) {
                    hours = h
                    prefs.updateHours = h
                    UpdateWorker.schedule(ctx)
                }
            }
        }
        Text("On Wi-Fi only, when the battery isn't low.", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))

        // --- Tunnel ---
        SectionLabel("Connection")
        SwitchRow("Start on boot", "Turn protection back on after a restart, if it was on.", boot) {
            boot = it; prefs.startOnBoot = it
        }
        SwitchRow("IPv6 DNS", "Also catch DNS sent over IPv6. Turn off only if a network misbehaves.", ipv6) {
            ipv6 = it; prefs.ipv6 = it
            ShieldVpnService.restartIfOn(ctx)
        }
        InfoRow("Always-on VPN", "Open VPN settings", onClick = {
            ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        Text(
            "For protection that survives everything, set SonderShield as always-on VPN there. On HyperOS also set Battery saver to No restrictions for SonderShield.",
            color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        InfoRow("Battery", "App battery settings", onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        })

        SectionLabel("Statistics")
        InfoRow("Reset counters", "Today and total", onClick = { confirmReset = true })

        SectionLabel("About")
        Panel {
            Text("SonderShield ${BuildConfig.VERSION_NAME}", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "By Verisonder. Free and open source under GPL-3.0. Everything stays on this phone: no account, no analytics.",
                color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Block lists by StevenBlack, AdGuard, abuse.ch URLhaus, HaGeZi and OISD, each under its own licence.",
                color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp),
            )
            AccentButton("Source code") {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Verisonder/SonderShield")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (addList) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        val ok = name.isNotBlank() && url.startsWith("https://")
        AlertDialog(
            onDismissRequest = { addList = false },
            containerColor = Palette.card,
            title = { Text("Add a block list", color = Palette.text) },
            text = {
                Column {
                    Field(name, { name = it }, "Name", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Field(url, { url = it.trim() }, "https://… (hosts, AdBlock or domain list)", Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri)
                }
            },
            confirmButton = {
                AccentButton("Add", enabled = ok) {
                    val l = BlockList("", name.trim().replace("|", " "), url, url)
                    prefs.customLists = prefs.customLists + l
                    lists = prefs.allLists()
                    val added = lists.last()
                    enabled = enabled + added.id
                    addList = false
                    listsChanged(listOf(added))
                }
            },
            dismissButton = { AccentButton("Cancel") { addList = false } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Palette.card,
            title = { Text("Reset counters?", color = Palette.text) },
            text = { Text("Today's and all-time numbers go back to zero. The log stays.", color = Palette.dim) },
            confirmButton = { AccentButton("Reset") { ShieldState.resetStats(); confirmReset = false } },
            dismissButton = { AccentButton("Cancel") { confirmReset = false } },
        )
    }
}

@Composable
private fun radioColors() = RadioButtonDefaults.colors(selectedColor = Palette.accent, unselectedColor = Palette.dim)

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Palette.onAccent, checkedTrackColor = Palette.accent,
    uncheckedThumbColor = Palette.dim, uncheckedTrackColor = Palette.cardHi, uncheckedBorderColor = Palette.line,
)
