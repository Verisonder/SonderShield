package com.verisonder.sondershield.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
    val themeId by ShieldState.theme.collectAsState()

    var upstream by remember { mutableStateOf(prefs.upstreamId) }
    var custom by remember { mutableStateOf(prefs.customUpstream) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(prefs.enabledLists) }
    var lists by remember { mutableStateOf(prefs.allLists()) }
    var updating by remember { mutableStateOf(false) }
    var hours by remember { mutableIntStateOf(prefs.updateHours) }
    var ipv6 by remember { mutableStateOf(prefs.ipv6) }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var addList by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) } // re-read list metadata

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
        ScreenHeader("Settings")

        // 01 DNS server
        Section(1, "DNS server", first = true) {
            for (u in Upstreams.all) {
                val sel = upstream == u.id
                OptionCard(
                    u.name.substringBefore(" ("),
                    u.name.substringAfter("(", "").removeSuffix(")").replaceFirstChar { it.uppercase() } +
                        if (sel && testResult != null) " · $testResult" else "",
                    selected = sel,
                    onClick = { upstream = u.id; applyUpstream() },
                )
            }
            OptionCard("Custom", "Your own server", selected = upstream == Upstreams.CUSTOM, onClick = { upstream = Upstreams.CUSTOM })
            if (upstream == Upstreams.CUSTOM) {
                Field(custom, { custom = it }, "udp 9.9.9.9  or  doh https://host/dns-query 1.2.3.4", Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri, onDone = ::applyUpstream)
                Text(
                    "DoH needs the server's IP addresses after the URL, so SonderShield can reach it without asking itself.",
                    color = Palette.faint, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (upstream == Upstreams.CUSTOM) PillButton("Save") { applyUpstream() }
                PillButton("Test", filled = false) {
                    testResult = "testing…"
                    scope.launch {
                        val spec = if (upstream == Upstreams.CUSTOM) custom.trim() else Upstreams.byId(upstream)!!.spec
                        testResult = withContext(Dispatchers.IO) {
                            try { "answered in ${ShieldState.engine.testUpstream(spec)} ms" } catch (e: Exception) { "failed: ${e.message}" }
                        }
                    }
                }
                if (upstream == Upstreams.CUSTOM) testResult?.let { Text(it, color = Palette.dim, fontSize = 13.sp) }
            }
        }

        // 02 Block lists
        Section(2, "Block lists") {
            Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (updating) "Updating…" else "Update now", filled = false, enabled = !updating) {
                    scope.launch {
                        updating = true
                        val changed = store.updateEnabled(force = true)
                        updating = false
                        tick++
                        if (changed && phase == Phase.ON) ShieldState.applyConfig()
                    }
                }
                PillButton("Add a list", filled = false) { addList = true }
                if (updating) CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
            // key(): metadata is read again whenever a download finishes.
            key(tick, listsVersion) {
                for (l in lists) {
                    val on = l.id in enabled
                    val meta = prefs.listMeta(l.id)
                    val status = when {
                        meta.error.isNotEmpty() -> "Error: ${meta.error}"
                        meta.updated > 0 -> "${meta.entries.grouped()} entries · ${ago(meta.updated)}"
                        on -> "Downloads when protection starts"
                        else -> l.about
                    }
                    fun flip(v: Boolean) {
                        enabled = if (v) enabled + l.id else enabled - l.id
                        listsChanged(if (v && !store.file(l.id).isFile) listOf(l) else emptyList())
                    }
                    OptionCard(l.name, status, onClick = { flip(!on) }, subColor = if (meta.error.isNotEmpty()) Palette.warn else null) {
                        if (l.id.startsWith("custom-")) {
                            IconPill(Icons.trash, "Remove list") {
                                prefs.customLists = prefs.customLists.filter { it.id != l.id }
                                store.delete(l.id)
                                enabled = enabled - l.id
                                lists = prefs.allLists()
                                listsChanged()
                            }
                        }
                        Toggle(on) { flip(it) }
                    }
                }
            }
        }

        // 03 Updates
        Section(3, "Update lists automatically") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((h, label) in listOf(0 to "Off", 12 to "12 h", 24 to "24 h", 48 to "48 h")) {
                    Chip(label, hours == h) {
                        hours = h
                        prefs.updateHours = h
                        UpdateWorker.schedule(ctx)
                    }
                }
            }
            Text("On Wi-Fi only, when the battery isn't low.", color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        // 04 Connection
        Section(4, "Connection") {
            ToggleCard("Start on boot", "Turn protection back on after a restart, if it was on.", boot) {
                boot = it; prefs.startOnBoot = it
            }
            ToggleCard("IPv6 DNS", "Also catch DNS sent over IPv6. Turn off only if a network misbehaves.", ipv6) {
                ipv6 = it; prefs.ipv6 = it
                ShieldVpnService.restartIfOn(ctx)
            }
            OptionCard(
                "Always-on VPN",
                "Protection that survives everything. Set SonderShield as always-on in VPN settings.",
                onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
            OptionCard(
                "Battery",
                "On HyperOS set Battery saver to No restrictions, or the system may stop SonderShield.",
                onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
        }

        // 05 Theme
        Section(5, "Theme") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in ThemeMode.entries) {
                    if (m == ThemeMode.You && !ThemeMode.youAvailable) continue
                    Chip(m.label, ThemeMode.of(themeId) == m) {
                        prefs.theme = m.id
                        ShieldState.theme.value = m.id
                    }
                }
            }
            if (ThemeMode.of(themeId) == ThemeMode.You) {
                Text("Colours from your wallpaper, light or dark with the system.", color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                AccentPicker(prefs)
            }
        }

        // 06 Statistics
        Section(6, "Statistics") {
            OptionCard("Blocked in total", prefs.allTimeBlocked.grouped())
            OptionCard("Reset counters", "Today and all-time go back to zero. The log stays.", onClick = { confirmReset = true }) {
                Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp))
            }
        }

        // 07 About
        Section(7, "About") {
            Block {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(30.dp, lit = true)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Wordmark(17)
                        Text("Version ${BuildConfig.VERSION_NAME}", color = Palette.faint, fontSize = 12.sp)
                    }
                }
                Text(
                    "By Verisonder. Free and open source under GPL-3.0. Everything stays on this phone: no account, no analytics.",
                    color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Block lists by StevenBlack, AdGuard, abuse.ch URLhaus, HaGeZi and OISD, each under its own licence. Fonts: Unbounded and DM Sans (SIL Open Font License).",
                    color = Palette.faint, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
                PillButton("Source code", filled = false) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Verisonder/SonderShield")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (addList) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        val ok = name.isNotBlank() && url.startsWith("https://")
        AlertDialog(
            onDismissRequest = { addList = false },
            containerColor = Palette.panel,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Add a block list", style = display(20), color = Palette.text) },
            text = {
                Column {
                    Field(name, { name = it }, "Name", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Field(url, { url = it.trim() }, "https://… (hosts, AdBlock or domains)", Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri)
                }
            },
            confirmButton = {
                PillButton("Add", enabled = ok) {
                    val l = BlockList("", name.trim().replace("|", " "), url, url)
                    prefs.customLists = prefs.customLists + l
                    lists = prefs.allLists()
                    val added = lists.last()
                    enabled = enabled + added.id
                    addList = false
                    listsChanged(listOf(added))
                }
            },
            dismissButton = { PillButton("Cancel", filled = false) { addList = false } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Palette.panel,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Reset counters?", style = display(20), color = Palette.text) },
            text = { Text("Today's and all-time numbers go back to zero. The log stays.", color = Palette.dim) },
            confirmButton = { PillButton("Reset") { ShieldState.resetStats(); confirmReset = false } },
            dismissButton = { PillButton("Cancel", filled = false) { confirmReset = false } },
        )
    }
}

/**
 * Accent colour: a row of swatches, the last one a rainbow "custom" circle that opens a hue
 * bar for any other colour.
 */
@Composable
private fun AccentPicker(prefs: Prefs) {
    val current by ShieldState.accent.collectAsState()
    val isPreset = AccentChoices.any { it.second.toArgb() == current }
    var showCustom by remember { mutableStateOf(false) }
    var hue by remember { mutableFloatStateOf(hueOf(Color(current))) }
    fun pick(c: Color) {
        val argb = c.toArgb()
        prefs.accent = argb
        ShieldState.accent.value = argb
    }
    Text("ACCENT", color = Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((name, c) in AccentChoices) {
            Swatch(name, fill = Brush.linearGradient(listOf(c, c)), selected = c.toArgb() == current) {
                showCustom = false
                pick(c)
            }
        }
        // Custom: rainbow until a custom colour is in use, then that colour inside a rainbow ring.
        val rainbow = Brush.sweepGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.85f, 1f) })
        Swatch(
            "Custom colour", fill = if (isPreset) rainbow else Brush.linearGradient(listOf(Color(current), Color(current))),
            selected = !isPreset || showCustom, ring = if (isPreset) null else rainbow,
        ) {
            showCustom = !showCustom
            if (showCustom && isPreset) { hue = hueOf(Color(current)); pick(Color.hsv(hue, 0.85f, 1f)) }
        }
    }
    AnimatedVisibility(
        visible = showCustom,
        enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) + fadeIn(),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
    ) {
        Column(Modifier.padding(top = 16.dp)) {
            Text("Drag to pick any colour", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            HueBar(hue) { h -> hue = h; pick(Color.hsv(h, 0.85f, 1f)) }
        }
    }
}

/** A colour circle drawn on a canvas so its edge and selection ring stay smooth. */
@Composable
private fun Swatch(name: String, fill: Brush, selected: Boolean, ring: Brush? = null, onClick: () -> Unit) {
    val ringWidth by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "ring")
    val ringColor = Palette.text
    Canvas(
        Modifier.size(44.dp).pressScale().clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = name; this.selected = selected },
    ) {
        val r = size.minDimension / 2
        val stroke = 2.5.dp.toPx()
        val gap = 3.dp.toPx()
        // Unselected: the colour fills the whole circle. Selected: it shrinks to leave a gap
        // and a ring (white, or rainbow for a custom colour) appears around it.
        val inner = r - (stroke + gap) * ringWidth
        if (ringWidth > 0f) {
            if (ring != null) drawCircle(ring, radius = r - stroke / 2, style = Stroke(stroke))
            else drawCircle(ringColor, radius = r - stroke / 2, style = Stroke(stroke), alpha = ringWidth)
        }
        drawCircle(fill, radius = inner)
    }
}

/** Rainbow track with a round thumb; tap or drag anywhere on it. */
@Composable
private fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    val thumbBorder = Palette.text
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(Unit) { detectTapGestures { onHue((it.x / size.width).coerceIn(0f, 1f) * 359f) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onHue((change.position.x / size.width).coerceIn(0f, 1f) * 359f)
                }
            }
            .semantics { contentDescription = "Custom colour" },
    ) {
        val thumbR = size.height / 2 - 2.dp.toPx()
        val left = thumbR
        val right = size.width - thumbR
        val trackH = 12.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.85f, 1f) }, startX = left, endX = right),
            topLeft = Offset(left, (size.height - trackH) / 2), size = Size(right - left, trackH),
            cornerRadius = CornerRadius(trackH / 2),
        )
        val x = left + (right - left) * (hue / 359f)
        val c = Offset(x, size.height / 2)
        drawCircle(Color.Black.copy(alpha = 0.25f), radius = thumbR + 1.dp.toPx(), center = c)
        drawCircle(thumbBorder, radius = thumbR, center = c)
        drawCircle(Color.hsv(hue, 0.85f, 1f), radius = thumbR - 3.dp.toPx(), center = c)
    }
}

private fun hueOf(c: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return hsv[0]
}
