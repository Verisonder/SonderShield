package com.verisonder.sondershield.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.verisonder.sondershield.data.DataUsage
import com.verisonder.sondershield.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private val WifiGreen = Color(0xFF22C55E)

/** The "Data" tab: a data monitor, with the block/allow Rules kept on a second sub-tab. */
@Composable
fun DataScreen() {
    var view by rememberSaveable { mutableIntStateOf(0) } // 0 usage, 1 rules
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Data", "Données", "Daten"), kicker = tr("On this phone", "Sur ce téléphone", "Auf diesem Handy"))
        ChipRow(Modifier.padding(start = Gutter, bottom = 6.dp)) {
            Chip(tr("Usage", "Conso", "Verbrauch"), view == 0) { view = 0 }
            Chip(tr("Rules", "Règles", "Regeln"), view == 1) { view = 1 }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (view == 0) DataMonitorScreen() else RulesScreen(showHeader = false)
        }
    }
}

private data class DayUsage(val label: String, val mobile: Long, val wifi: Long) {
    val total: Long get() = mobile + wifi
}

private data class MonitorData(
    val todayMobile: DataUsage.Usage,
    val todayWifi: DataUsage.Usage,
    val cycleMobile: Long,
    val week: List<DayUsage>,
)

@Composable
fun DataMonitorScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var access by remember { mutableStateOf(DataUsage.hasAccess(ctx)) }
    var refresh by remember { mutableIntStateOf(0) }

    // Re-check access and numbers whenever the screen comes back (the user may have just
    // granted Usage access in system settings).
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
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                val seen = remember { mutableStateOf(false) }
                Column(Modifier.padding(horizontal = Gutter).padding(top = 14.dp)) {
                    Kicker(tr("Usage access", "Accès à l'utilisation", "Nutzungszugriff"), Modifier.onSeen(seen).reveal(seen.value))
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier.fillMaxWidth().reveal(seen.value, 1).clip(blockShape).background(Palette.accent)
                            .padding(horizontal = 18.dp, vertical = 18.dp),
                    ) {
                        Text(tr("One switch to go.", "Plus qu'un réglage.", "Noch ein Schalter."), style = display(22), color = Palette.onAccent)
                        Text(
                            tr(
                                "SonderShield reads this phone's own data counters. Allow Usage access once — nothing leaves the phone.",
                                "SonderShield lit les compteurs de données de ce téléphone. Autorisez l'accès à l'utilisation une fois — rien ne quitte le téléphone.",
                                "SonderShield liest die Datenzähler dieses Handys. Erlaube einmal den Nutzungszugriff — nichts verlässt das Handy.",
                            ),
                            color = Palette.onAccent, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                        )
                        val src = remember { MutableInteractionSource() }
                        Box(
                            Modifier.height(44.dp).pressScale(src).clip(CircleShape).background(Palette.onAccent)
                                .clickable(interactionSource = src, indication = null) { DataUsage.openAccessSettings(ctx) }
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(tr("Allow access", "Autoriser", "Erlauben"), color = Palette.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        return
    }

    var data by remember { mutableStateOf<MonitorData?>(null) }
    LaunchedEffect(refresh, prefs.planStartMillis, prefs.planTotalBytes, prefs.planDays) {
        data = withContext(Dispatchers.IO) { computeMonitor(ctx, prefs) }
    }
    val d = data
    var editing by rememberSaveable { mutableStateOf(false) }

    val todaySeen = remember { mutableStateOf(false) }
    val planSeen = remember { mutableStateOf(false) }
    val weekSeen = remember { mutableStateOf(false) }
    // Numbers only count up once the data is in, so they don't count to zero first.
    val loaded = d != null

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = Gutter).padding(top = 14.dp)) {
                Kicker(tr("Today", "Aujourd'hui", "Heute"), Modifier.onSeen(todaySeen).reveal(todaySeen.value))
                Spacer(Modifier.height(12.dp))
                val seen = todaySeen.value && loaded
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageCard(Icons.signal, Palette.accent, tr("mobile data", "données mobiles", "mobile Daten"), d?.todayMobile, seen, 1, Modifier.weight(1f))
                    UsageCard(Icons.wifi, WifiGreen, "Wi-Fi", d?.todayWifi, seen, 2, Modifier.weight(1f))
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Gutter).padding(top = 28.dp)) {
                Kicker(tr("Monthly plan", "Forfait mensuel", "Monatstarif"), Modifier.onSeen(planSeen).reveal(planSeen.value))
                Spacer(Modifier.height(12.dp))
                if (!prefs.planActive || editing) {
                    Box(Modifier.reveal(planSeen.value, 1)) {
                        PlanSetup(prefs, onDone = { editing = false; refresh++ }, onCancel = { editing = false })
                    }
                } else {
                    PlanSummary(prefs, d?.cycleMobile ?: 0L, planSeen.value && loaded) { editing = true }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Gutter).padding(top = 28.dp, bottom = 28.dp)) {
                Kicker(tr("This week", "Cette semaine", "Diese Woche"), Modifier.onSeen(weekSeen).reveal(weekSeen.value))
                Spacer(Modifier.height(12.dp))
                val week = d?.week
                if (week != null) Box(Modifier.reveal(weekSeen.value, 1)) { WeekChart(week, weekSeen.value) }
                else Text(tr("Reading…", "Lecture…", "Wird gelesen…"), color = Palette.faint, fontSize = 13.sp)
            }
        }
    }
}

/** One of the two Today tiles, styled like Home's stat tiles: accent number that counts up. */
@Composable
private fun UsageCard(icon: ImageVector, tint: Color, label: String, u: DataUsage.Usage?, seen: Boolean, order: Int, modifier: Modifier) {
    val total = counted((u?.total ?: 0L).toDouble(), seen).toLong()
    val tx = counted((u?.tx ?: 0L).toDouble(), seen).toLong()
    val rx = counted((u?.rx ?: 0L).toDouble(), seen).toLong()
    Column(modifier.reveal(seen, order).clip(blockShape).background(Palette.card2).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Text(fmtBytes(total), style = display(22), color = Palette.accentText, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
        Text(label, color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(12.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text(fmtBytes(tx), color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(tr("sent", "envoyé", "gesendet"), color = Palette.faint, fontSize = 11.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(fmtBytes(rx), color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(tr("received", "reçu", "empfangen"), color = Palette.faint, fontSize = 11.sp)
            }
        }
    }
}

/** Plan at a glance: the filled accent block (like Home's block rate), a bar that fills in, tiles. */
@Composable
private fun PlanSummary(prefs: Prefs, usedBytes: Long, seen: Boolean, onEdit: () -> Unit) {
    val total = prefs.planTotalBytes
    val remainingTarget = (total - usedBytes).coerceAtLeast(0L)
    val dailyQuota = if (prefs.planDays > 0) total / prefs.planDays else 0L
    val validTill = prefs.planStartMillis + prefs.planDays.toLong() * 86_400_000L
    val daysLeft = ceil((validTill - System.currentTimeMillis()) / 86_400_000.0).toLong().coerceAtLeast(0L)
    val frac = if (total > 0) (usedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f

    val remaining = counted(remainingTarget.toDouble(), seen).toLong()
    val used = counted(usedBytes.toDouble(), seen).toLong()
    val grow = remember { Animatable(0f) }
    LaunchedEffect(seen, frac) {
        if (seen) { delay(160); grow.animateTo(frac, tween(900, easing = FastOutSlowInEasing)) }
    }
    val low = frac > 0.9f

    Column {
        Row(
            Modifier.fillMaxWidth().reveal(seen, 1).clip(blockShape).background(if (low) Palette.warn else Palette.accent)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(fmtBytes(remaining), style = display(28), color = Palette.onAccent, maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                tr("left of", "restants sur", "übrig von") + "\n" + fmtBytes(total),
                color = Palette.onAccent, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        // Used share of the plan, as a bar that fills in.
        Box(Modifier.fillMaxWidth().reveal(seen, 2).clip(blockShape).background(Palette.card2).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtBytes(used) + " " + tr("used", "utilisés", "genutzt"), color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${(frac * 100).toInt()}%", color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(Palette.line)) {
                    Box(Modifier.fillMaxWidth(grow.value.coerceIn(0.001f, 1f)).height(6.dp).clip(CircleShape).background(if (low) Palette.warn else Palette.accent))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(fmtBytes(dailyQuota), tr("daily quota", "quota quotidien", "Tageskontingent"), Modifier.weight(1f).reveal(seen, 3))
            StatTile(
                "$daysLeft " + tr("days", "jours", "Tage"),
                tr("left, valid till", "restants, jusqu'au", "übrig, gültig bis") + "\n" + ordinalDate(validTill),
                Modifier.weight(1f).reveal(seen, 4),
            )
        }
        Box(Modifier.padding(top = 12.dp).reveal(seen, 5)) {
            PillButton(tr("Edit plan", "Modifier", "Bearbeiten"), filled = false, onClick = onEdit)
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(blockShape).background(Palette.card2).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(value, style = display(20), color = Palette.accentText, maxLines = 1)
        Text(label, color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PlanSetup(prefs: Prefs, onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var amount by rememberSaveable { mutableStateOf(if (prefs.planTotalBytes > 0) trimNum(prefs.planTotalBytes / 1_000_000_000.0) else "") }
    var unitGb by rememberSaveable { mutableStateOf(true) }
    var days by rememberSaveable { mutableStateOf(if (prefs.planDays > 0) prefs.planDays.toString() else "30") }
    var start by rememberSaveable { mutableStateOf(if (prefs.planStartMillis > 0) prefs.planStartMillis else DataUsage.startOfToday()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().clip(blockShape).background(Palette.card2).padding(16.dp)) {
        Text(
            if (prefs.planActive) tr("Edit your plan", "Modifier le forfait", "Tarif bearbeiten") else tr("Track your mobile plan", "Suivez votre forfait", "Tarif verfolgen"),
            style = display(18), color = Palette.text,
        )
        Text(
            tr("How much data you get, and for how long.", "Combien de données, et pour combien de temps.", "Wie viele Daten, und wie lange."),
            color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(amount, { amount = it; error = null }, tr("Data, e.g. 20", "Données, ex. 20", "Daten, z. B. 20"), Modifier.weight(1f), keyboard = KeyboardType.Decimal)
            Spacer(Modifier.width(8.dp))
            Chip("GB", unitGb) { unitGb = true }
            Spacer(Modifier.width(6.dp))
            Chip("MB", !unitGb) { unitGb = false }
        }
        Spacer(Modifier.height(10.dp))
        Field(days, { days = it; error = null }, tr("Days it lasts", "Durée en jours", "Dauer in Tagen"), Modifier.fillMaxWidth(), keyboard = KeyboardType.Number)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Started", "Début", "Beginn"), color = Palette.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            PillButton(ordinalDate(start), filled = false) { pickDate(ctx, start) { start = it } }
        }
        error?.let { Text(it, color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(tr("Save", "Enregistrer", "Speichern")) {
                val amt = amount.trim().replace(',', '.').toDoubleOrNull()
                if (amt == null || amt <= 0) {
                    error = trc(ctx, "Enter how much data the plan has.", "Entrez la quantité de données du forfait.", "Gib die Datenmenge des Tarifs ein.")
                    return@PillButton
                }
                val dys = days.trim().toIntOrNull()
                if (dys == null || dys <= 0) {
                    error = trc(ctx, "Enter how many days it lasts.", "Entrez la durée en jours.", "Gib die Dauer in Tagen ein.")
                    return@PillButton
                }
                prefs.planTotalBytes = (amt * if (unitGb) 1_000_000_000.0 else 1_000_000.0).toLong()
                prefs.planDays = dys
                prefs.planStartMillis = start
                onDone()
            }
            if (prefs.planActive) {
                Spacer(Modifier.width(10.dp))
                PillButton(tr("Cancel", "Annuler", "Abbrechen"), filled = false, onClick = onCancel)
            }
        }
    }
}

/** Seven days, mobile stacked on Wi-Fi. Bars grow up one after another when seen, like Home's chart. */
@Composable
private fun WeekChart(week: List<DayUsage>, seen: Boolean) {
    val max = (week.maxOfOrNull { it.total } ?: 1L).coerceAtLeast(1L)
    val totalM = week.sumOf { it.mobile }
    val totalW = week.sumOf { it.wifi }
    Column(Modifier.fillMaxWidth().clip(blockShape).background(Palette.card2).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(Palette.accent, tr("Mobile", "Mobile", "Mobil") + " " + fmtBytes(totalM))
            Spacer(Modifier.width(16.dp))
            LegendDot(WifiGreen, "Wi-Fi " + fmtBytes(totalW))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            week.forEachIndexed { i, day ->
                val grow = remember { Animatable(0f) }
                LaunchedEffect(seen) {
                    if (seen) { delay(70L * i); grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
                }
                val g = grow.value
                val mH = if (day.mobile > 0) ((day.mobile.toFloat() / max * 140f).coerceAtLeast(3f) * g).dp else 0.dp
                val wH = if (day.wifi > 0) ((day.wifi.toFloat() / max * 140f).coerceAtLeast(3f) * g).dp else 0.dp
                val isToday = i == week.lastIndex
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (day.total > 0) shortBytes(day.total) else "", color = Palette.faint, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
                    Box(Modifier.height(140.dp).width(20.dp), contentAlignment = Alignment.BottomCenter) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
                            if (mH > 0.dp) Box(Modifier.fillMaxWidth().height(mH).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(Palette.accent))
                            if (wH > 0.dp) {
                                val shape = if (mH > 0.dp) RectangleShape else RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                                Box(Modifier.fillMaxWidth().height(wH).clip(shape).background(WifiGreen))
                            }
                            if (day.total == 0L) Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Palette.line))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        day.label, color = if (isToday) Palette.accentText else Palette.faint, fontSize = 11.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ---- helpers (no Compose) ----

private fun computeMonitor(ctx: Context, prefs: Prefs): MonitorData {
    val now = System.currentTimeMillis()
    val tStart = DataUsage.startOfToday()
    val tm = DataUsage.mobile(ctx, tStart, now)
    val tw = DataUsage.wifi(ctx, tStart, now)
    val cycleStart = if (prefs.planActive) prefs.planStartMillis.coerceAtMost(now) else DataUsage.midnight(29)
    val cycleMobile = DataUsage.mobile(ctx, cycleStart, now).total
    val fmt = SimpleDateFormat("EEE", Locale.getDefault())
    val week = (6 downTo 0).map { i ->
        val s = DataUsage.midnight(i)
        val e = if (i == 0) now else DataUsage.midnight(i - 1)
        val m = DataUsage.mobile(ctx, s, e).total
        val w = DataUsage.wifi(ctx, s, e).total
        DayUsage(fmt.format(Date(s)), m, w)
    }
    return MonitorData(tm, tw, cycleMobile, week)
}

private fun fmtBytes(b: Long): String {
    val gb = 1_000_000_000.0
    val mb = 1_000_000.0
    val kb = 1_000.0
    return when {
        b >= gb -> "%.2f GB".format(b / gb)
        b >= mb -> "%.2f MB".format(b / mb)
        b >= kb -> "%.0f KB".format(b / kb)
        else -> "0 MB"
    }
}

private fun shortBytes(b: Long): String {
    val gb = 1_000_000_000.0
    val mb = 1_000_000.0
    return when {
        b >= gb -> "%.1fG".format(b / gb)
        b >= mb -> "%.0fM".format(b / mb)
        else -> "%.0fK".format(b / 1_000.0)
    }
}

private fun trimNum(v: Double): String {
    val s = "%.2f".format(Locale.US, v)
    return s.trimEnd('0').trimEnd('.')
}

private fun ordinalDate(ms: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    val day = c.get(Calendar.DAY_OF_MONTH)
    val month = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(ms))
    if (Locale.getDefault().language != "en") return "$day $month"
    val suffix = if (day in 11..13) "th" else when (day % 10) {
        1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
    }
    return "$day$suffix $month"
}

private fun pickDate(ctx: Context, current: Long, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance()
    c.timeInMillis = current
    DatePickerDialog(
        ctx,
        { _, y, m, d ->
            val cc = Calendar.getInstance()
            cc.set(y, m, d, 0, 0, 0)
            cc.set(Calendar.MILLISECOND, 0)
            onPick(cc.timeInMillis)
        },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH),
    ).show()
}
