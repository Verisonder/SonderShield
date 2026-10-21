package com.verisonder.sondershield.ui

import android.app.usage.NetworkStats
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.verisonder.sondershield.data.DataUsage
import com.verisonder.sondershield.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** What one row stands for: a real app, or one of the buckets Android counts without an app. */
private enum class UseKind { APP, SYSTEM, OTHER, REMOVED, HOTSPOT }

private data class AppUse(
    val key: String,
    val label: String,
    val icon: ImageBitmap?,
    val kind: UseKind,
    val mobile: DataUsage.Usage,
    val wifi: DataUsage.Usage,
) {
    /** Bytes on the chosen network: 0 both, 1 mobile, 2 Wi-Fi. */
    fun on(net: Int): Long = when (net) {
        1 -> mobile.total
        2 -> wifi.total
        else -> mobile.total + wifi.total
    }
    fun sent(net: Int): Long = when (net) {
        1 -> mobile.tx
        2 -> wifi.tx
        else -> mobile.tx + wifi.tx
    }
    fun received(net: Int): Long = when (net) {
        1 -> mobile.rx
        2 -> wifi.rx
        else -> mobile.rx + wifi.rx
    }
}

// Icons are the slow part, so they're kept for the whole process; the last list per period
// makes coming back to the tab instant (fresh numbers replace it).
private val useIcons = HashMap<String, ImageBitmap?>()
private val lastAppUse = HashMap<Int, List<AppUse>>()

/** Data → Apps: how much each app used, today, this week, this month or in the plan period. */
@Composable
fun AppUsageScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var access by remember { mutableStateOf(DataUsage.hasAccess(ctx)) }
    var refresh by remember { mutableIntStateOf(0) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                access = DataUsage.hasAccess(ctx)
                refresh++
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    if (!access) {
        UsageAccessGate()
        return
    }

    var periodPick by rememberSaveable { mutableIntStateOf(0) } // 0 today, 1 7 days, 2 30 days, 3 plan
    var net by rememberSaveable { mutableIntStateOf(1) } // 0 all, 1 mobile, 2 Wi-Fi
    val period = if (periodPick == 3 && !prefs.planActive) 0 else periodPick
    var rows by remember(period) { mutableStateOf(lastAppUse[period]) }
    LaunchedEffect(period, refresh) {
        val now = System.currentTimeMillis()
        val (start, end) = useRange(prefs, period, now)
        val fresh = withContext(Dispatchers.IO) { loadAppUse(ctx, start, end) }
        rows = fresh
        lastAppUse[period] = fresh
    }
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    val list = rows?.filter { it.on(net) > 0L }?.sortedByDescending { it.on(net) }
    val total = list?.sumOf { it.on(net) } ?: 0L
    val max = (list?.firstOrNull()?.on(net) ?: 1L).coerceAtLeast(1L)
    val headSeen = remember { mutableStateOf(false) }
    val shownTotal = counted(total.toDouble(), headSeen.value && list != null).toLong()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(top = 14.dp)) {
                Kicker(
                    tr("Per app", "Par appli", "Pro App"),
                    Modifier.padding(horizontal = Gutter).onSeen(headSeen).reveal(headSeen.value),
                )
                Spacer(Modifier.height(8.dp))
                ChipRow(Modifier.horizontalScroll(rememberScrollState()).reveal(headSeen.value, 1)) {
                    Chip(tr("Today", "Aujourd'hui", "Heute"), period == 0) { periodPick = 0 }
                    Chip(tr("7 days", "7 jours", "7 Tage"), period == 1) { periodPick = 1 }
                    Chip(tr("30 days", "30 jours", "30 Tage"), period == 2) { periodPick = 2 }
                    if (prefs.planActive) Chip(tr("Plan", "Forfait", "Tarif"), period == 3) { periodPick = 3 }
                }
                Box(Modifier.padding(horizontal = Gutter).padding(top = 8.dp, bottom = 14.dp).reveal(headSeen.value, 2)) {
                    Segmented(listOf(tr("All", "Tout", "Alle"), tr("Mobile", "Mobile", "Mobil"), "Wi-Fi"), net) { net = it }
                }
                Box(Modifier.padding(horizontal = Gutter).padding(bottom = 14.dp).reveal(headSeen.value, 3)) {
                    UseHero(list, net, period, shownTotal, headSeen.value)
                }
            }
        }
        when {
            list == null -> item {
                Text(
                    tr("Reading…", "Lecture…", "Wird gelesen…"), color = Palette.faint, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = Gutter),
                )
            }
            list.isEmpty() -> item {
                Text(
                    tr("No data used in this period.", "Aucune donnée utilisée sur cette période.", "In diesem Zeitraum keine Daten genutzt."),
                    color = Palette.faint, fontSize = 13.sp, modifier = Modifier.padding(horizontal = Gutter),
                )
            }
            else -> itemsIndexed(list, key = { _, a -> a.key }) { i, a ->
                Box(Modifier.animateItem()) {
                    AppUseRow(a, net, max, total, if (i < 8) i else 0, open == a.key) { open = if (open == a.key) null else a.key }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/**
 * The accent block on top: the period's total (counts up), how many apps used it, the top app,
 * and a share strip where the biggest apps sweep in one after another.
 */
@Composable
private fun UseHero(list: List<AppUse>?, net: Int, period: Int, shownTotal: Long, seen: Boolean) {
    val total = list?.sumOf { it.on(net) } ?: 0L
    val top = list?.take(4).orEmpty()
    val span = when (period) {
        1 -> tr("in the last 7 days", "ces 7 derniers jours", "in den letzten 7 Tagen")
        2 -> tr("in the last 30 days", "ces 30 derniers jours", "in den letzten 30 Tagen")
        3 -> tr("in this plan period", "sur cette période du forfait", "in diesem Tarifzeitraum")
        else -> tr("today", "aujourd'hui", "heute")
    }
    val n = list?.size ?: 0
    val apps = if (n == 1) tr("1 app", "1 appli", "1 App") else tr("$n apps", "$n applis", "$n Apps")
    Column(Modifier.fillMaxWidth().clip(blockShape).background(Palette.accent).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FitText(fmtBytes(shownTotal), fmtBytes(total), display(28), Palette.onAccent) }
            Spacer(Modifier.width(12.dp))
            Text(
                tr("used by", "utilisés par", "genutzt von") + " " + apps + "\n" + span,
                color = Palette.onAccent, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End,
            )
        }
        // Share strip: the top four apps as solid pieces, everything else as one faint piece.
        val parts = remember(list, net) {
            if (total <= 0L) emptyList()
            else top.map { it.on(net).toFloat() / total } + listOf((total - top.sumOf { it.on(net) }).toFloat() / total)
        }
        val grow = remember { Animatable(0f) }
        LaunchedEffect(seen, parts) {
            if (seen && parts.isNotEmpty()) { grow.snapTo(0f); grow.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
        }
        val alphas = listOf(1f, 0.72f, 0.5f, 0.34f, 0.18f)
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (parts.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Palette.onAccent.copy(alpha = 0.18f)))
            } else {
                // Each piece starts once the one before it is a little way in.
                parts.forEachIndexed { i, f ->
                    if (f <= 0f) return@forEachIndexed
                    val start = i * 0.12f
                    val local = ((grow.value - start) / (1f - start)).coerceIn(0f, 1f)
                    val alpha = if (i == parts.lastIndex) alphas.last() else alphas[i]
                    Box(
                        Modifier.weight(f.coerceAtLeast(0.01f)).height(10.dp)
                            .graphicsLayer { scaleX = local; transformOrigin = TransformOrigin(0f, 0.5f) }
                            .clip(CircleShape).background(Palette.onAccent.copy(alpha = alpha)),
                    )
                }
            }
        }
        val lead = top.firstOrNull()
        if (lead != null && total > 0L) {
            val pct = (lead.on(net) * 100 / total).toInt()
            Text(
                tr("${lead.label} leads with $pct%", "${lead.label} en tête avec $pct %", "${lead.label} vorn mit $pct %"),
                color = Palette.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** One app: icon, name, size, and a bar against the top app. Tap for mobile/Wi-Fi and sent/received. */
@Composable
private fun AppUseRow(a: AppUse, net: Int, max: Long, total: Long, order: Int, expanded: Boolean, onTap: () -> Unit) {
    val seen = remember { mutableStateOf(false) }
    val bytes = a.on(net)
    val shown = counted(bytes.toDouble(), seen.value).toLong()
    val frac = (bytes.toFloat() / max).coerceIn(0f, 1f)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(seen.value, frac) {
        if (seen.value) grow.animateTo(frac, tween(800, easing = FastOutSlowInEasing))
    }
    val accent = Palette.accent
    val bar = remember(accent) { Brush.horizontalGradient(listOf(accent, lerp(accent, Color.White, 0.38f))) }
    val src = remember { MutableInteractionSource() }
    val share = if (total > 0) (bytes * 100 / total).toInt() else 0
    val turn by animateFloatAsState(if (expanded) 90f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "chev")

    Column(
        Modifier.padding(horizontal = Gutter).padding(bottom = 8.dp).fillMaxWidth()
            .onSeen(seen).reveal(seen.value, order).pressScale(src)
            .clip(blockShape).background(Palette.card2)
            .clickable(interactionSource = src, indication = null, onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Palette.panel), contentAlignment = Alignment.Center) {
                if (a.icon != null) {
                    Image(a.icon, null, Modifier.size(30.dp))
                } else {
                    Icon(kindIcon(a.kind), null, tint = Palette.dim, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.label, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(fmtBytes(shown), color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Palette.line)) {
                        Box(Modifier.fillMaxWidth(grow.value.coerceIn(0.015f, 1f)).height(6.dp).clip(CircleShape).background(bar))
                    }
                    Text(
                        if (share < 1) "<1%" else "$share%", color = Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, modifier = Modifier.padding(start = 10.dp).width(34.dp), textAlign = TextAlign.End,
                    )
                }
            }
            Icon(
                Icons.chevron, null, tint = Palette.faint,
                modifier = Modifier.padding(start = 6.dp).size(16.dp).graphicsLayer { rotationZ = turn },
            )
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 56.dp, top = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (net != 2) Detail(fmtBytes(a.mobile.total), tr("mobile", "mobile", "mobil"))
                    if (net != 1) Detail(fmtBytes(a.wifi.total), "Wi-Fi")
                    Detail(fmtBytes(a.sent(net)), tr("sent", "envoyé", "gesendet"))
                    Detail(fmtBytes(a.received(net)), tr("received", "reçu", "empfangen"))
                }
                kindNote(a.kind)?.let {
                    Text(it, color = Palette.faint, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun Detail(value: String, label: String) {
    Column {
        Text(value, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Palette.faint, fontSize = 11.sp)
    }
}

private fun kindIcon(k: UseKind): ImageVector = when (k) {
    UseKind.SYSTEM -> Icons.settings
    UseKind.REMOVED -> Icons.trash
    UseKind.HOTSPOT -> Icons.signal
    else -> Icons.apps
}

@Composable
private fun kindNote(k: UseKind): String? = when (k) {
    UseKind.SYSTEM -> tr(
        "Android itself and services without an app of their own.",
        "Android lui-même et les services sans appli propre.",
        "Android selbst und Dienste ohne eigene App.",
    )
    UseKind.OTHER -> tr(
        "Apps with no icon in the launcher, so their names aren't visible here.",
        "Applis sans icône dans le lanceur : leur nom n'est pas visible ici.",
        "Apps ohne Symbol im Launcher, daher ist ihr Name hier nicht sichtbar.",
    )
    UseKind.REMOVED -> tr(
        "Apps uninstalled during this period.",
        "Applis désinstallées pendant cette période.",
        "In diesem Zeitraum deinstallierte Apps.",
    )
    UseKind.HOTSPOT -> tr(
        "Devices using this phone's hotspot.",
        "Appareils connectés au partage de connexion.",
        "Geräte im Hotspot dieses Handys.",
    )
    UseKind.APP -> null
}

// ---- helpers (no Compose) ----

/** The window [start, end) for a period: today, the last 7 or 30 days (today included), or the plan. */
private fun useRange(prefs: Prefs, period: Int, now: Long): Pair<Long, Long> = when (period) {
    1 -> DataUsage.midnight(6) to now
    2 -> DataUsage.midnight(29) to now
    3 -> planPeriod(prefs, now).let { it.first to minOf(now, it.second) }
    else -> DataUsage.startOfToday() to now
}

/** Reads mobile and Wi-Fi per uid at the same time and turns uids into named rows. */
private suspend fun loadAppUse(ctx: Context, start: Long, end: Long): List<AppUse> = coroutineScope {
    if (start >= end) return@coroutineScope emptyList()
    val m = async { DataUsage.mobileByUid(ctx, start, end) }
    val w = async { DataUsage.wifiByUid(ctx, start, end) }
    val mobile = m.await()
    val wifi = w.await()
    val pm = ctx.packageManager
    val px = (30 * ctx.resources.displayMetrics.density).toInt()
    data class Acc(val label: String, val icon: ImageBitmap?, val kind: UseKind, val m: LongArray, val w: LongArray)
    val byKey = LinkedHashMap<String, Acc>()
    for (uid in mobile.keys + wifi.keys) {
        if (uid == NetworkStats.Bucket.UID_ALL) continue
        val (key, kind, pkg) = identify(pm, uid)
        val acc = byKey.getOrPut(key) {
            val label = when (kind) {
                UseKind.SYSTEM -> trc(ctx, "System", "Système", "System")
                UseKind.OTHER -> trc(ctx, "Other apps", "Autres applis", "Andere Apps")
                UseKind.REMOVED -> trc(ctx, "Removed apps", "Applis supprimées", "Entfernte Apps")
                UseKind.HOTSPOT -> trc(ctx, "Hotspot", "Partage de connexion", "Hotspot")
                UseKind.APP -> appLabel(pm, pkg!!)
            }
            val icon = if (kind == UseKind.APP) {
                useIcons.getOrPut(pkg!!) {
                    try { pm.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap() } catch (_: Exception) { null }
                }
            } else null
            Acc(label, icon, kind, LongArray(2), LongArray(2))
        }
        mobile[uid]?.let { acc.m[0] += it.rx; acc.m[1] += it.tx }
        wifi[uid]?.let { acc.w[0] += it.rx; acc.w[1] += it.tx }
    }
    byKey.map { (k, a) -> AppUse(k, a.label, a.icon, a.kind, DataUsage.Usage(a.m[0], a.m[1]), DataUsage.Usage(a.w[0], a.w[1])) }
}

/** (row key, kind, package) for a uid. Apps in a second user or dual-app space merge with the main one. */
private fun identify(pm: PackageManager, uid: Int): Triple<String, UseKind, String?> {
    if (uid == NetworkStats.Bucket.UID_REMOVED) return Triple("~removed", UseKind.REMOVED, null)
    if (uid == NetworkStats.Bucket.UID_TETHERING) return Triple("~hotspot", UseKind.HOTSPOT, null)
    val appId = uid % 100_000
    if (uid < 0 || appId < Process.FIRST_APPLICATION_UID) return Triple("~system", UseKind.SYSTEM, null)
    val pkgs = (pm.getPackagesForUid(uid) ?: pm.getPackagesForUid(appId)).orEmpty()
    val pkg = pkgs.firstOrNull { runCatching { pm.getApplicationInfo(it, 0) }.isSuccess }
        ?: return Triple("~other", UseKind.OTHER, null)
    return Triple(pkg, UseKind.APP, pkg)
}

private fun appLabel(pm: PackageManager, pkg: String): String =
    runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
