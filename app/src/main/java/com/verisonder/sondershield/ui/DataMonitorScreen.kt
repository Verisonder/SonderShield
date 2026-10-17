package com.verisonder.sondershield.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.background
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

    // Re-check access and numbers whenever the screen comes back to the foreground (the user
    // may have just granted Usage access in system settings).
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
                Section(1, tr("Usage access needed", "Accès à l'utilisation requis", "Nutzungszugriff nötig"), first = true) {
                    Text(
                        tr(
                            "SonderShield needs Usage access to read how much data this phone uses. It only reads totals on this device — nothing leaves the phone.",
                            "SonderShield a besoin de l'accès à l'utilisation pour lire la consommation de données. Il ne lit que les totaux sur cet appareil — rien ne quitte le téléphone.",
                            "SonderShield braucht Nutzungszugriff, um den Datenverbrauch zu lesen. Es liest nur Summen auf diesem Gerät — nichts verlässt das Handy.",
                        ),
                        color = Palette.dim, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    PillButton(tr("Grant access", "Autoriser", "Zugriff erlauben")) { DataUsage.openAccessSettings(ctx) }
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

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section(1, tr("Today's data usage", "Données aujourd'hui", "Datenverbrauch heute"), first = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UsageCard(Icons.signal, Palette.accent, tr("Mobile", "Mobile", "Mobil"), d?.todayMobile, Modifier.weight(1f))
                    UsageCard(Icons.wifi, WifiGreen, tr("Wi-Fi", "Wi-Fi", "WLAN"), d?.todayWifi, Modifier.weight(1f))
                }
            }
        }
        item {
            Section(2, tr("Monthly plan", "Forfait mensuel", "Monatstarif")) {
                if (!prefs.planActive || editing) {
                    PlanSetup(prefs, onDone = { editing = false; refresh++ }, onCancel = { editing = false })
                } else {
                    PlanSummary(prefs, d?.cycleMobile ?: 0L) { editing = true }
                }
            }
        }
        item {
            Section(3, tr("This week's overview", "Aperçu de la semaine", "Diese Woche")) {
                val week = d?.week
                if (week != null) WeekChart(week)
                else Text(tr("Reading…", "Lecture…", "Wird gelesen…"), color = Palette.faint, fontSize = 13.sp)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun UsageCard(icon: ImageVector, tint: Color, label: String, u: DataUsage.Usage?, modifier: Modifier) {
    Column(modifier.clip(blockShape).background(Palette.card2).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(fmtBytes(u?.total ?: 0L), style = display(22), color = Palette.text, maxLines = 1)
        }
        Text(label, color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(12.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text(fmtBytes(u?.tx ?: 0L), color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(tr("Sent", "Envoyé", "Gesendet"), color = Palette.faint, fontSize = 11.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(fmtBytes(u?.rx ?: 0L), color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(tr("Received", "Reçu", "Empfangen"), color = Palette.faint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PlanSummary(prefs: Prefs, usedBytes: Long, onEdit: () -> Unit) {
    val total = prefs.planTotalBytes
    val remaining = (total - usedBytes).coerceAtLeast(0)
    val dailyQuota = if (prefs.planDays > 0) total / prefs.planDays else 0L
    val validTill = prefs.planStartMillis + prefs.planDays.toLong() * 86_400_000L
    val daysLeft = ceil((validTill - System.currentTimeMillis()) / 86_400_000.0).toLong().coerceAtLeast(0)
    val frac = if (total > 0) (usedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f

    Column {
        Text(tr("Monthly plan active", "Forfait mensuel actif", "Monatstarif aktiv"), style = display(18), color = Palette.text)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Palette.panel)) {
            Box(Modifier.fillMaxWidth(frac).height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (frac > 0.9f) Palette.warn else Palette.accent))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            fmtBytes(usedBytes) + " " + tr("used", "utilisés", "genutzt") + "  |  " + fmtBytes(remaining) + " " + tr("remaining", "restants", "übrig"),
            color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            tr("Daily quota:", "Quota quotidien :", "Tageskontingent:") + " " + fmtBytes(dailyQuota),
            color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            tr("Valid till", "Valable jusqu'au", "Gültig bis") + " " + ordinalDate(validTill) + "  |  " +
                daysLeft + " " + tr("day(s) remaining", "jour(s) restant(s)", "Tag(e) übrig"),
            color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(14.dp))
        PillButton(tr("Edit plan", "Modifier", "Bearbeiten"), filled = false, onClick = onEdit)
    }
}

@Composable
private fun PlanSetup(prefs: Prefs, onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var amount by rememberSaveable { mutableStateOf(if (prefs.planTotalBytes > 0) trimNum(prefs.planTotalBytes / 1_000_000_000.0) else "") }
    var unitGb by rememberSaveable { mutableStateOf(true) }
    var days by rememberSaveable { mutableStateOf(if (prefs.planDays > 0) prefs.planDays.toString() else "28") }
    var start by rememberSaveable { mutableStateOf(if (prefs.planStartMillis > 0) prefs.planStartMillis else DataUsage.startOfToday()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column {
        if (!prefs.planActive) {
            Text(
                tr("Set up your mobile plan to track what's left.", "Configurez votre forfait mobile pour suivre ce qu'il reste.", "Richte deinen Mobilfunktarif ein, um den Rest zu verfolgen."),
                color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(amount, { amount = it; error = null }, tr("Data, e.g. 20", "Données, ex. 20", "Daten, z. B. 20"), Modifier.weight(1f), keyboard = KeyboardType.Decimal)
            Spacer(Modifier.width(8.dp))
            Chip("GB", unitGb) { unitGb = true }
            Spacer(Modifier.width(6.dp))
            Chip("MB", !unitGb) { unitGb = false }
        }
        Spacer(Modifier.height(10.dp))
        Field(days, { days = it }, tr("Days in cycle", "Jours dans le cycle", "Tage im Zyklus"), Modifier.fillMaxWidth(), keyboard = KeyboardType.Number)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Cycle started:", "Début du cycle :", "Zyklusbeginn:"), color = Palette.dim, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            PillButton(ordinalDate(start), filled = false) { pickDate(ctx, start) { start = it } }
        }
        error?.let { Text(it, color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(tr("Save plan", "Enregistrer", "Speichern")) {
                val amt = amount.trim().replace(',', '.').toDoubleOrNull()
                if (amt == null || amt <= 0) {
                    error = trc(ctx, "Enter the data amount.", "Entrez la quantité de données.", "Gib die Datenmenge ein.")
                    return@PillButton
                }
                val dys = days.trim().toIntOrNull()
                if (dys == null || dys <= 0) {
                    error = trc(ctx, "Enter the cycle length in days.", "Entrez la durée du cycle en jours.", "Gib die Zykluslänge in Tagen ein.")
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

@Composable
private fun WeekChart(week: List<DayUsage>) {
    val max = (week.maxOfOrNull { it.total } ?: 1L).coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth().clip(blockShape).background(Palette.card2).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(Palette.accent, tr("Mobile", "Mobile", "Mobil"))
            Spacer(Modifier.width(18.dp))
            LegendDot(WifiGreen, tr("Wi-Fi", "Wi-Fi", "WLAN"))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            for (day in week) {
                val mH = if (day.mobile > 0) (day.mobile.toFloat() / max * 150f).dp.coerceAtLeast(3.dp) else 0.dp
                val wH = if (day.wifi > 0) (day.wifi.toFloat() / max * 150f).dp.coerceAtLeast(3.dp) else 0.dp
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (day.total > 0) shortBytes(day.total) else "", color = Palette.faint, fontSize = 9.sp, modifier = Modifier.padding(bottom = 3.dp))
                    Box(Modifier.height(150.dp).width(22.dp), contentAlignment = Alignment.BottomCenter) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
                            if (mH > 0.dp) Box(Modifier.fillMaxWidth().height(mH).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(Palette.accent))
                            if (wH > 0.dp) {
                                val shape = if (mH > 0.dp) RectangleShape else RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)
                                Box(Modifier.fillMaxWidth().height(wH).clip(shape).background(WifiGreen))
                            }
                            if (mH <= 0.dp && wH <= 0.dp) Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.panel))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(day.label, color = Palette.faint, fontSize = 11.sp)
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
        b >= kb -> "%.2f KB".format(b / kb)
        else -> "%.2f MB".format(b / mb)
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
    val s = "%.2f".format(v)
    return s.trimEnd('0').trimEnd('.')
}

private fun ordinalDate(ms: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    val day = c.get(Calendar.DAY_OF_MONTH)
    val suffix = if (day in 11..13) "th" else when (day % 10) {
        1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
    }
    val month = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(ms))
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
