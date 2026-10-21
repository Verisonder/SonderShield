package com.verisonder.sondershield.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.verisonder.sondershield.data.DataUsage
import com.verisonder.sondershield.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    var view by rememberSaveable { mutableIntStateOf(0) } // 0 usage, 1 apps, 2 rules
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Data", "Données", "Daten"), kicker = tr("On this phone", "Sur ce téléphone", "Auf diesem Handy"))
        ChipRow(Modifier.padding(bottom = 2.dp)) {
            Chip(tr("Usage", "Conso", "Verbrauch"), view == 0) { view = 0 }
            Chip(tr("Apps", "Applis", "Apps"), view == 1) { view = 1 }
            Chip(tr("Rules", "Règles", "Regeln"), view == 2) { view = 2 }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (view) {
                0 -> DataMonitorScreen()
                1 -> AppUsageScreen()
                else -> RulesScreen(showHeader = false)
            }
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
    val full: Boolean = false, // plan period and week are in, not just today
)

// The last numbers shown, so coming back to the tab is instant (fresh ones replace them).
private var lastMonitor: MonitorData? = null

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
        UsageAccessGate()
        return
    }

    var data by remember { mutableStateOf(lastMonitor) }
    LaunchedEffect(refresh, prefs.planMode, prefs.planStartMillis, prefs.planEndMillis, prefs.planTotalBytes) {
        // Today first (two quick reads) so the cards show straight away...
        val now = System.currentTimeMillis()
        val today = withContext(Dispatchers.IO) {
            val s0 = DataUsage.startOfToday()
            DataUsage.mobile(ctx, s0, now) to DataUsage.wifi(ctx, s0, now)
        }
        data = (data ?: MonitorData(today.first, today.second, 0L, emptyList())).copy(todayMobile = today.first, todayWifi = today.second)
        // ...then the plan period and the seven days, read all at once.
        val all = withContext(Dispatchers.IO) { computeRest(ctx, prefs, today.first, today.second) }
        data = all
        lastMonitor = all
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
                val seen = todaySeen.value
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageCard(Icons.signal, Palette.accent, tr("mobile data", "données mobiles", "mobile Daten"), d?.todayMobile, seen, loaded, 1, Modifier.weight(1f))
                    UsageCard(Icons.wifi, WifiGreen, "Wi-Fi", d?.todayWifi, seen, loaded, 2, Modifier.weight(1f))
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Gutter).padding(top = 28.dp)) {
                Kicker(tr("Data plan", "Forfait", "Datentarif"), Modifier.onSeen(planSeen).reveal(planSeen.value))
                Spacer(Modifier.height(12.dp))
                if (!prefs.planActive || editing) {
                    Box(Modifier.reveal(planSeen.value, 1)) {
                        PlanSetup(prefs, onDone = { editing = false; refresh++ }, onCancel = { editing = false })
                    }
                } else {
                    PlanSummary(prefs, d?.cycleMobile ?: 0L, d?.todayMobile?.total ?: 0L, planSeen.value, d?.full == true) { editing = true }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Gutter).padding(top = 28.dp, bottom = 28.dp)) {
                Kicker(tr("This week", "Cette semaine", "Diese Woche"), Modifier.onSeen(weekSeen).reveal(weekSeen.value))
                Spacer(Modifier.height(12.dp))
                val week = d?.week
                if (!week.isNullOrEmpty()) Box(Modifier.reveal(weekSeen.value, 1)) { WeekChart(week, weekSeen.value) }
                else Text(tr("Reading…", "Lecture…", "Wird gelesen…"), color = Palette.faint, fontSize = 13.sp)
            }
        }
    }
}

/** Shown until Usage access is granted: one block with the button to the system switch. */
@Composable
internal fun UsageAccessGate() {
    val ctx = LocalContext.current
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
}

/** One of the two Today tiles, styled like Home's stat tiles: accent number that counts up. */
@Composable
private fun UsageCard(icon: ImageVector, tint: Color, label: String, u: DataUsage.Usage?, seen: Boolean, ready: Boolean, order: Int, modifier: Modifier) {
    // The card slides in as soon as it's on screen; its numbers count up once they've arrived.
    val count = seen && ready
    val total = counted((u?.total ?: 0L).toDouble(), count).toLong()
    val tx = counted((u?.tx ?: 0L).toDouble(), count).toLong()
    val rx = counted((u?.rx ?: 0L).toDouble(), count).toLong()
    Column(modifier.reveal(seen, order).clip(blockShape).background(Palette.card2).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        FitText(fmtBytes(total), fmtBytes(u?.total ?: 0L), display(22), Palette.accentText, Modifier.padding(top = 10.dp))
        Text(label, color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(12.dp))
        Row {
            Column(Modifier.weight(1f).padding(end = 6.dp)) {
                FitText(fmtBytes(tx), fmtBytes(u?.tx ?: 0L), TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold), Palette.text)
                Text(tr("sent", "envoyé", "gesendet"), color = Palette.faint, fontSize = 11.sp)
            }
            Column(Modifier.weight(1f)) {
                FitText(fmtBytes(rx), fmtBytes(u?.rx ?: 0L), TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold), Palette.text)
                Text(tr("received", "reçu", "empfangen"), color = Palette.faint, fontSize = 11.sp)
            }
        }
    }
}

/**
 * One line of text that shrinks to fit its width instead of being cut off. Sized from [widest]
 * (the final value) so a number counting up doesn't change size on every frame.
 */
@Composable
internal fun FitText(text: String, widest: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val full = LocalTextStyle.current.merge(style)
    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val natural = remember(widest, full) { measurer.measure(widest, full, maxLines = 1, softWrap = false).size.width }
        val scale = if (natural > maxPx && natural > 0) maxPx.toFloat() / natural * 0.98f else 1f
        Text(text, style = full.copy(fontSize = full.fontSize * scale), color = color, maxLines = 1, softWrap = false)
    }
}

/** Plan at a glance: the filled accent block (like Home's block rate), a bar that fills in, tiles. */
@Composable
private fun PlanSummary(prefs: Prefs, usedBytes: Long, todayBytes: Long, seen: Boolean, ready: Boolean, onEdit: () -> Unit) {
    val now = System.currentTimeMillis()
    val (_, end) = planPeriod(prefs, now)
    val total = prefs.planTotalBytes
    val remainingTarget = (total - usedBytes).coerceAtLeast(0L)
    val msLeft = (end - now).coerceAtLeast(0L)
    val daysLeft = ceil(msLeft / 86_400_000.0).toLong()
    val hoursLeft = ceil(msLeft / 3_600_000.0).toLong()
    // Today's share is fixed at midnight: what was left when the day began, split over the days left
    // (today included). The tile then shows what's still free of it today, so it goes down as you use data.
    val startPlan = planPeriod(prefs, now).first
    val usedToday = if (startPlan > DataUsage.startOfToday()) usedBytes else todayBytes.coerceAtMost(usedBytes)
    val leftAtDayStart = (total - (usedBytes - usedToday)).coerceAtLeast(0L)
    val dailyQuota = if (daysLeft > 0) leftAtDayStart / daysLeft else 0L
    val leftToday = (dailyQuota - usedToday).coerceAtLeast(0L)
    val frac = if (total > 0) (usedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val mode = prefs.planMode
    val modeLabel = when (mode) {
        "daily" -> tr("resets daily", "remise à zéro chaque jour", "täglich zurückgesetzt")
        "custom" -> tr("custom period", "période personnalisée", "eigener Zeitraum")
        else -> tr("resets monthly", "remise à zéro chaque mois", "monatlich zurückgesetzt")
    }

    val remaining = counted(remainingTarget.toDouble(), seen && ready).toLong()
    val used = counted(usedBytes.toDouble(), seen && ready).toLong()
    val grow = remember { Animatable(0f) }
    LaunchedEffect(seen, ready, frac) {
        if (seen && ready) { delay(160); grow.animateTo(frac, tween(900, easing = FastOutSlowInEasing)) }
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
                tr("left of", "restants sur", "übrig von") + " " + fmtBytes(total) + "\n" + modeLabel,
                color = Palette.onAccent, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
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
            if (mode == "daily") {
                StatTile(fmtBytes(total), tr("daily limit", "limite par jour", "Tageslimit"), Modifier.weight(1f).reveal(seen, 3))
                StatTile(
                    "$hoursLeft h", tr("left today,\nresets at midnight", "restantes,\nremise à zéro à minuit", "übrig,\nReset um Mitternacht"),
                    Modifier.weight(1f).reveal(seen, 4),
                )
            } else {
                StatTile(
                    fmtBytes(leftToday),
                    tr("left today, of\n", "restants aujourd'hui,\nsur ", "heute übrig, von\n") + fmtBytes(dailyQuota) + tr(" a day", " par jour", " pro Tag"),
                    Modifier.weight(1f).reveal(seen, 3),
                )
                val endLabel = if (mode == "custom") tr("left, ends on", "restants, fin le", "übrig, endet am") else tr("left, resets on", "restants, remise le", "übrig, Reset am")
                // A custom period ends at the end of its last day; show that day, not the next.
                val shownEnd = if (mode == "custom") end - 1 else end
                StatTile("$daysLeft " + tr("days", "jours", "Tage"), endLabel + "\n" + ordinalDate(shownEnd), Modifier.weight(1f).reveal(seen, 4))
            }
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

/** Plan setup, with the reference app's choices: how the counter resets, and the data limit. */
@Composable
private fun PlanSetup(prefs: Prefs, onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val had = prefs.planTotalBytes
    var mode by rememberSaveable { mutableStateOf(prefs.planMode) }
    var unitGb by rememberSaveable { mutableStateOf(had == 0L || had >= 1_000_000_000L) }
    var amount by rememberSaveable {
        mutableStateOf(if (had <= 0L) "" else if (had >= 1_000_000_000L) trimNum(had / 1_000_000_000.0) else trimNum(had / 1_000_000.0))
    }
    var start by rememberSaveable { mutableStateOf(if (prefs.planStartMillis > 0) prefs.planStartMillis else DataUsage.startOfToday()) }
    var end by rememberSaveable { mutableStateOf(if (prefs.planEndMillis > 0) prefs.planEndMillis else DataUsage.startOfToday() + 29L * 86_400_000L) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().clip(blockShape).background(Palette.card2).padding(16.dp)) {
        Text(
            if (prefs.planActive) tr("Edit your plan", "Modifier le forfait", "Tarif bearbeiten") else tr("Track your data plan", "Suivez votre forfait", "Datentarif verfolgen"),
            style = display(18), color = Palette.text,
        )
        Text(
            tr("Counts mobile data only.", "Compte uniquement les données mobiles.", "Zählt nur mobile Daten."),
            color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
        )

        SetupLabel(tr("Reset counter", "Remise à zéro", "Zähler zurücksetzen"))
        OptionCard(
            tr("Daily", "Chaque jour", "Täglich"), tr("Starts again every day at midnight", "Repart de zéro chaque jour à minuit", "Beginnt jeden Tag um Mitternacht neu"),
            selected = mode == "daily", onClick = { mode = "daily"; error = null },
        )
        OptionCard(
            tr("Monthly", "Chaque mois", "Monatlich"), tr("Starts again on the same date each month", "Repart de zéro à la même date chaque mois", "Beginnt jeden Monat am selben Tag neu"),
            selected = mode == "monthly", onClick = { mode = "monthly"; error = null },
        )
        OptionCard(
            tr("Custom", "Personnalisé", "Eigener Zeitraum"), tr("Your own start and end dates", "Vos propres dates de début et de fin", "Eigenes Start- und Enddatum"),
            selected = mode == "custom", onClick = { mode = "custom"; error = null },
        )
        when (mode) {
            "monthly" -> DateRow(tr("Started on", "Commencé le", "Begonnen am"), start) { start = it }
            "custom" -> {
                DateRow(tr("Starts", "Début", "Beginn"), start) { start = it; error = null }
                DateRow(tr("Ends", "Fin", "Ende"), end) { end = it; error = null }
            }
        }

        SetupLabel(tr("Data limit", "Limite de données", "Datenlimit"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(amount, { amount = it; error = null }, "0.00", Modifier.weight(1f), keyboard = KeyboardType.Decimal)
            Spacer(Modifier.width(10.dp))
            Segmented(listOf("MB", "GB"), if (unitGb) 1 else 0) { unitGb = it == 1 }
        }
        error?.let { Text(it, color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(tr("Save", "Enregistrer", "Speichern")) {
                val amt = amount.trim().replace(',', '.').toDoubleOrNull()
                if (amt == null || amt <= 0) {
                    error = trc(ctx, "Enter the data limit.", "Entrez la limite de données.", "Gib das Datenlimit ein.")
                    return@PillButton
                }
                if (mode == "custom" && end < start) {
                    error = trc(ctx, "The end date is before the start.", "La date de fin est avant le début.", "Das Enddatum liegt vor dem Beginn.")
                    return@PillButton
                }
                prefs.planTotalBytes = (amt * if (unitGb) 1_000_000_000.0 else 1_000_000.0).toLong()
                prefs.planMode = mode
                prefs.planStartMillis = if (mode == "daily") DataUsage.startOfToday() else start
                prefs.planEndMillis = if (mode == "custom") end else 0L
                onDone()
            }
            if (prefs.planActive) {
                Spacer(Modifier.width(10.dp))
                PillButton(tr("Cancel", "Annuler", "Abbrechen"), filled = false, onClick = onCancel)
                Spacer(Modifier.weight(1f))
                Text(
                    tr("Remove", "Supprimer", "Entfernen"), color = Palette.warn, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).clickable { prefs.clearPlan(); onDone() }.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupLabel(text: String) {
    Text(
        text.uppercase(), color = Palette.faint, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
    )
}

@Composable
private fun DateRow(label: String, value: Long, onPick: (Long) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        PillButton(ordinalDate(value), filled = false) { pickDate(ctx, value, onPick) }
    }
}

/** Two-way switch (MB | GB), in the same pill style as the app's chips. */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.height(48.dp).clip(CircleShape).background(Palette.panel).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val bg by animateColorAsState(if (on) Palette.pill else Color.Transparent, tween(200), label = "seg")
            val fg by animateColorAsState(if (on) Palette.onPill else Palette.dim, tween(200), label = "segText")
            Box(
                Modifier.fillMaxHeight().clip(CircleShape).background(bg).clickable { onPick(i) }.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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

/** The plan period and the last seven days, all read at the same time. */
private suspend fun computeRest(ctx: Context, prefs: Prefs, tm: DataUsage.Usage, tw: DataUsage.Usage): MonitorData = coroutineScope {
    val now = System.currentTimeMillis()
    val cycle = async {
        if (prefs.planActive) {
            val (ps, pe) = planPeriod(prefs, now)
            if (ps < now) DataUsage.mobile(ctx, ps, minOf(now, pe)).total else 0L
        } else 0L
    }
    val fmt = SimpleDateFormat("EEE", Locale.getDefault())
    val days = (6 downTo 0).map { i ->
        async {
            val s = DataUsage.midnight(i)
            val e = if (i == 0) now else DataUsage.midnight(i - 1)
            DayUsage(fmt.format(Date(s)), DataUsage.mobile(ctx, s, e).total, DataUsage.wifi(ctx, s, e).total)
        }
    }
    MonitorData(tm, tw, cycle.await(), days.awaitAll(), full = true)
}

/** The plan's current counting window [start, end) in millis. */
internal fun planPeriod(prefs: Prefs, now: Long): Pair<Long, Long> = when (prefs.planMode) {
    "daily" -> DataUsage.startOfToday() to DataUsage.midnight(-1)
    "custom" -> prefs.planStartMillis to endOfDay(prefs.planEndMillis)
    else -> {
        val anchor = prefs.planStartMillis
        fun monthsAfter(n: Int): Long {
            val c = Calendar.getInstance()
            c.timeInMillis = anchor
            c.add(Calendar.MONTH, n)
            return c.timeInMillis
        }
        var k = 0
        while (monthsAfter(k + 1) <= now && k < 1200) k++
        monthsAfter(k) to monthsAfter(k + 1)
    }
}

/** Midnight at the end of the day [ms] falls on. */
private fun endOfDay(ms: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
}

internal fun fmtBytes(b: Long): String {
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
