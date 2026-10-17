package com.verisonder.sondershield.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.StatsSnapshot
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Rough size of an ad or tracker request that never happens. Only for the estimate. */
private const val BYTES_PER_BLOCK = 30_000L

/**
 * Becomes true once the element has scrolled into view (and stays true), so its entrance
 * plays when the user gets there, not off-screen.
 */
@Composable
private fun Modifier.onSeen(seen: MutableState<Boolean>): Modifier {
    return this.onGloballyPositioned { c ->
        if (!seen.value && c.isAttached) {
            val window = c.findRootCoordinates().size.height
            if (c.boundsInWindow().top < window * 0.94f) seen.value = true
        }
    }
}

/** Slides up and fades in once [seen], [order] steps after the others in its group. */
@Composable
private fun Modifier.reveal(seen: Boolean, order: Int = 0): Modifier {
    val e = remember { Animatable(0f) }
    LaunchedEffect(seen) {
        if (seen) { delay(70L * order); e.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 240f)) }
    }
    return this.graphicsLayer { translationY = (1f - e.value) * 36.dp.toPx(); alpha = e.value.coerceIn(0f, 1f) }
}

/** Counts from 0 up to [target] the first time [seen], then follows changes smoothly. */
@Composable
private fun counted(target: Double, seen: Boolean): Double {
    val v = remember { Animatable(0f) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(seen, target) {
        if (!seen) return@LaunchedEffect
        v.animateTo(target.toFloat(), tween(if (!started) 1100 else 500, easing = FastOutSlowInEasing))
        started = true
    }
    return v.value.toDouble()
}

/** Everything below the first screen of Home: all-time numbers, activity chart, top blocked. */
@Composable
fun HomeStats() {
    val s by ShieldState.stats.collectAsState()
    val domains by ShieldState.domains.collectAsState()
    val startedAt by ShieldState.startedAt.collectAsState()

    val allSeen = remember { mutableStateOf(false) }
    val chartSeen = remember { mutableStateOf(false) }
    val topSeen = remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = Gutter).padding(bottom = 28.dp)) {
        Kicker(tr("All time", "Depuis le début", "Insgesamt"), Modifier.padding(top = 10.dp).onSeen(allSeen).reveal(allSeen.value))
        Spacer(Modifier.height(12.dp))
        val seen = allSeen.value
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(compact(counted(s.allTotal.toDouble(), seen).toLong()), tr("DNS queries", "requêtes DNS", "DNS-Anfragen"), Modifier.weight(1f).reveal(seen, 1))
            Tile(compact(counted(s.allBlocked.toDouble(), seen).toLong()), tr("blocked", "bloquées", "blockiert"), Modifier.weight(1f).reveal(seen, 2))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(compact(counted(s.allThreats.toDouble(), seen).toLong()), tr("threats blocked\nmalware and phishing", "menaces bloquées\nmalwares et phishing", "Bedrohungen blockiert\nMalware und Phishing"), Modifier.weight(1f).reveal(seen, 3))
            Tile(
                if (domains > 0) compact(counted(domains.toDouble(), seen).toLong()) else "–", tr("filter rules\nloaded now", "règles de filtre\nchargées", "Filterregeln\njetzt geladen"),
                Modifier.weight(1f).reveal(seen, 4),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Block rate: the filled one, wide.
        val rate = counted(if (s.allTotal > 0) s.allBlocked * 100.0 / s.allTotal else 0.0, seen)
        Row(
            Modifier.fillMaxWidth().reveal(seen, 5).clip(blockShape).background(Palette.accent).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("%.1f%%".format(rate), style = display(30), color = Palette.onAccent, modifier = Modifier.weight(1f))
            Text(tr("block rate,\nall time", "taux de blocage,\ndepuis le début", "Blockquote,\ninsgesamt"), color = Palette.onAccent, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(
                "≈ " + bytes(counted((s.allBlocked * BYTES_PER_BLOCK).toDouble(), seen).toLong()), tr("data not downloaded\nestimate", "données non téléchargées\nestimation", "nicht geladene Daten\nSchätzung"),
                Modifier.weight(1f).reveal(seen, 6),
            )
            Uptime(startedAt, Modifier.weight(1f).reveal(seen, 7))
        }

        Kicker(tr("Activity", "Activité", "Aktivität"), Modifier.padding(top = 28.dp).onSeen(chartSeen).reveal(chartSeen.value))
        Spacer(Modifier.height(10.dp))
        var week by rememberSaveable { mutableStateOf(false) }
        Row(Modifier.reveal(chartSeen.value, 1), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(tr("24 hours", "24 heures", "24 Stunden"), !week) { week = false }
            Chip(tr("7 days", "7 jours", "7 Tage"), week) { week = true }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.reveal(chartSeen.value, 2)) { ActivityChart(s, week, chartSeen.value) }

        Kicker(tr("Top blocked", "Les plus bloqués", "Am meisten blockiert"), Modifier.padding(top = 28.dp).onSeen(topSeen).reveal(topSeen.value))
        Spacer(Modifier.height(10.dp))
        if (s.top.isEmpty()) {
            Text(tr("Nothing blocked yet.", "Rien de bloqué pour l'instant.", "Noch nichts blockiert."), color = Palette.faint, fontSize = 14.sp)
        } else {
            val max = s.top.first().second.coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.top.forEachIndexed { i, (domain, count) ->
                    TopRow(i + 1, domain, count, count.toFloat() / max, topSeen.value, Modifier.reveal(topSeen.value, i + 1))
                }
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(blockShape).background(Palette.card2).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(value, style = display(22), color = Palette.accentText, maxLines = 1)
        Text(label, color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Uptime(startedAt: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (startedAt > 0) { now = System.currentTimeMillis(); delay(30_000) }
    }
    Tile(if (startedAt > 0) duration(now - startedAt, lang()) else tr("off", "arrêté", "aus"), tr("on without a break", "actif sans interruption", "ohne Unterbrechung aktiv"), modifier)
}

@Composable
private fun TopRow(rank: Int, domain: String, count: Int, share: Float, seen: Boolean, modifier: Modifier = Modifier) {
    // The bar grows from the left once the row is on screen.
    val grow = remember { Animatable(0f) }
    LaunchedEffect(seen, share) {
        if (seen) { delay(80L * rank); grow.animateTo(share.coerceIn(0.02f, 1f), tween(700, easing = FastOutSlowInEasing)) }
    }
    Column(modifier.fillMaxWidth().clip(blockShape).background(Palette.card2).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%02d".format(rank), style = display(15), color = Palette.accentText, modifier = Modifier.width(34.dp))
            Text(domain, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(compact(count.toLong()), color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        // Share of the top domain, as a bar.
        Box(Modifier.padding(start = 34.dp, top = 8.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Palette.line)) {
            Box(Modifier.fillMaxWidth(grow.value.coerceIn(0.001f, 1f)).height(4.dp).clip(CircleShape).background(Palette.accent))
        }
    }
}

/**
 * Queries (soft line with a fill) and blocked (accent) over the last 24 hours or 7 days.
 * Draws itself in from the left when it appears or the range changes.
 */
@Composable
private fun ActivityChart(s: StatsSnapshot, week: Boolean, seen: Boolean) {
    val points: List<Pair<Int, Int>> = if (week) {
        // 7 days ending today: sum the hours of each calendar day.
        val cal = Calendar.getInstance()
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        (6 downTo 0).map { d ->
            val end = StatsSnapshot.HOURS - 1 - hourOfDay - 24 * (d - 1) // exclusive end index for day d ago
            val start = end - 24
            var t = 0; var b = 0
            for (i in start.coerceAtLeast(0) until end.coerceAtMost(StatsSnapshot.HOURS)) { t += s.hours[i].first; b += s.hours[i].second }
            t to b
        }
    } else s.hours.takeLast(24)
    val labels: List<String> = if (week) {
        val f = SimpleDateFormat("EEE", Locale.getDefault())
        (6 downTo 0).map { d -> f.format(System.currentTimeMillis() - d * 86_400_000L) }
    } else {
        val f = SimpleDateFormat("HH:00", Locale.getDefault())
        (23 downTo 0).map { h -> f.format(System.currentTimeMillis() - h * 3_600_000L) }
    }

    val draw = remember { Animatable(0f) }
    LaunchedEffect(week, seen) {
        if (!seen) return@LaunchedEffect
        draw.snapTo(0f); draw.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
    }

    val soft = Palette.dim
    val accent = Palette.accent
    val grid = Palette.line
    val maxV = (points.maxOfOrNull { it.first } ?: 0).coerceAtLeast(1)
    val totalQ = points.sumOf { it.first }
    val totalB = points.sumOf { it.second }

    Column(Modifier.fillMaxWidth().clip(blockShape).background(Palette.card2).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend(soft, tr("Queries", "Requêtes", "Anfragen") + " " + compact(totalQ.toLong()))
            Spacer(Modifier.width(16.dp))
            Legend(accent, tr("Blocked", "Bloquées", "Blockiert") + " " + compact(totalB.toLong()))
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val w = size.width
            val h = size.height
            for (i in 0..3) {
                val y = h * i / 3f
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            if (points.size < 2) return@Canvas
            fun pt(i: Int, v: Int) = Offset(w * i / (points.size - 1), h - h * 0.92f * v / maxV)
            fun smooth(values: List<Int>): Path = Path().apply {
                val ps = values.indices.map { pt(it, values[it]) }
                moveTo(ps[0].x, ps[0].y)
                for (i in 1 until ps.size) {
                    val a = ps[i - 1]; val b = ps[i]
                    val mx = (a.x + b.x) / 2
                    cubicTo(mx, a.y, mx, b.y, b.x, b.y)
                }
            }
            val q = smooth(points.map { it.first })
            val b = smooth(points.map { it.second })
            clipRect(right = w * draw.value) {
                val fillQ = Path().apply { addPath(q); lineTo(w, h); lineTo(0f, h); close() }
                drawPath(fillQ, Brush.verticalGradient(listOf(soft.copy(alpha = 0.20f), soft.copy(alpha = 0f))))
                val fillB = Path().apply { addPath(b); lineTo(w, h); lineTo(0f, h); close() }
                drawPath(fillB, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0f))))
                drawPath(q, soft, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                drawPath(b, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.height(8.dp))
        // Axis: 7 day names, or every 6th hour.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = if (week) labels else labels.filterIndexed { i, _ -> i % 6 == 0 || i == labels.lastIndex }
            for (l in shown) Text(l, color = Palette.faint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** 1234 -> 1,234; 129800 -> 129.8K; 1300000 -> 1.3M */
fun compact(n: Long): String = when {
    n < 10_000 -> "%,d".format(n)
    n < 1_000_000 -> trim("%.1f".format(n / 1000.0)) + "K"
    n < 1_000_000_000 -> trim("%.1f".format(n / 1_000_000.0)) + "M"
    else -> trim("%.1f".format(n / 1_000_000_000.0)) + "B"
}

private fun trim(s: String) = s.removeSuffix(".0").removeSuffix(",0")

private fun bytes(b: Long): String = when {
    b < 1_000_000 -> "${b / 1000} KB"
    b < 1_000_000_000 -> trim("%.1f".format(b / 1e6)) + " MB"
    else -> trim("%.1f".format(b / 1e9)) + " GB"
}

private fun duration(ms: Long, lang: String): String {
    val m = ms / 60_000
    return when {
        m < 1 -> when (lang) { "fr" -> "à l'instant"; "de" -> "gerade eben"; else -> "just now" }
        m < 60 -> "$m min"
        m < 1440 -> "${m / 60} h ${m % 60} min"
        else -> "${m / 1440} ${when (lang) { "fr" -> "j"; "de" -> "T"; else -> "d" }} ${(m % 1440) / 60} h"
    }
}
