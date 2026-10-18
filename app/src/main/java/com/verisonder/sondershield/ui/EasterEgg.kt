package com.verisonder.sondershield.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Easter egg: tap the little shield on Home seven times. The big shield opens its "mouth"
 * along the cut and eats the ads, trackers and malware that fly at it.
 */

private enum class Kind { AD, TRACKER, MALWARE }

/** One thing flying at the shield. Times in seconds; angle in radians (where it comes from). */
private class Threat(val kind: Kind, val spawn: Float, val travel: Float, val angle: Float, val spin: Float, val wobble: Float)

private const val COUNT = 16
private const val FINALE = 8.4f

private fun ease(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p) // fast start, eases into the mouth

/** Counts taps on the little Home logo: seven quick ones open the egg. */
class SecretTaps {
    private var count = 0
    private var last = 0L

    /** Returns how many taps in the current run (resets after 1.5 s quiet). */
    fun tap(now: Long = System.currentTimeMillis()): Int {
        count = if (now - last > 1500) 1 else count + 1
        last = now
        return count
    }

    fun reset() { count = 0 }
}

@Composable
fun ShieldChomp(onClose: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val threats = remember {
        val r = java.util.Random(7)
        val kinds = listOf(Kind.AD, Kind.AD, Kind.TRACKER, Kind.AD, Kind.MALWARE, Kind.AD, Kind.TRACKER, Kind.MALWARE)
        List(COUNT) { i ->
            Threat(
                kind = kinds[i % kinds.size],
                spawn = 0.6f + i * 0.36f + r.nextFloat() * 0.12f,
                travel = 1.15f + r.nextFloat() * 0.4f,
                angle = (r.nextFloat() * 2f * PI).toFloat(),
                spin = (r.nextFloat() - 0.5f) * 540f,
                wobble = (r.nextFloat() - 0.5f) * 0.9f,
            )
        }
    }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            t = (now - start) / 1_000_000_000f
        }
    }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }

    // How far along each threat is (1 = in the mouth).
    fun progress(th: Threat) = (t - th.spawn) / th.travel
    val eaten = threats.count { progress(it) >= 1f }
    val ads = threats.count { it.kind == Kind.AD && progress(it) >= 1f }
    val trackers = threats.count { it.kind == Kind.TRACKER && progress(it) >= 1f }
    val malware = threats.count { it.kind == Kind.MALWARE && progress(it) >= 1f }

    // The mouth opens as something gets close and snaps shut on it.
    var mouth = 0f
    var bump = 0f
    for (th in threats) {
        val p = progress(th)
        mouth = max(mouth, when {
            p < 0.55f -> 0f
            p < 1f -> (p - 0.55f) / 0.45f * 3.2f
            p < 1.1f -> 3.2f * (1f - (p - 1f) / 0.1f)
            else -> 0f
        })
        if (p in 1f..1.35f) bump = max(bump, 1f - (p - 1f) / 0.35f)
    }

    var lastEaten by remember { mutableIntStateOf(0) }
    LaunchedEffect(eaten) {
        if (eaten > lastEaten) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastEaten = eaten
    }

    val measurer = rememberTextMeasurer()
    val adLabel = remember(measurer) { measurer.measure("AD", TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display)) }
    val accent = Palette.accent
    val text = Palette.text
    val warn = Palette.warn
    val done = t > FINALE

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Palette.bg)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        ) {
            // Threats and crumbs, behind the shield so they vanish into it.
            Canvas(Modifier.fillMaxSize()) {
                // Same spot as the shield (centred, lifted 20 dp by its bottom padding).
                val c = Offset(size.width / 2f, size.height / 2f - 20.dp.toPx())
                val reach = max(size.width, size.height) * 0.62f
                for (th in threats) {
                    val p = progress(th)
                    if (p <= 0f) continue
                    if (p < 1f) {
                        val e = ease(p)
                        // Curve in: the angle bends a little on the way.
                        val a = th.angle + th.wobble * (1f - e)
                        val d = reach * (1f - e)
                        val pos = Offset(c.x + cos(a) * d, c.y + sin(a) * d)
                        val s = 1f - 0.55f * max(0f, (p - 0.75f) / 0.25f)
                        drawThreat(th.kind, pos, s, th.spin * p, accent, warn, adLabel)
                    } else if (p < 1.6f) {
                        crumbs(c, (p - 1f) / 0.6f, th, if (th.kind == Kind.MALWARE) Color(0xFF4ADE80) else if (th.kind == Kind.AD) warn else accent)
                    }
                }
            }
            // The shield.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val scale = 1f + 0.09f * bump + if (done) 0.04f * sin(t * 3f) else 0f
                Logo(
                    150.dp, lit = true, split = mouth,
                    modifier = Modifier.padding(bottom = 40.dp).graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = -4f * bump },
                )
            }
            // Words.
            Column(Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = Gutter, vertical = 24.dp)) {
                Kicker(tr("Secret found", "Secret trouvé", "Geheimnis gefunden"))
                Text(
                    if (done) tr("Served clean.", "Servi propre.", "Sauber serviert.") else tr("Om nom nom.", "Miam miam.", "Mampf mampf."),
                    style = display(36), color = text, modifier = Modifier.padding(top = 10.dp),
                )
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val pop = 1f + 0.18f * bump
                Text(
                    "$eaten", style = display(64), color = accent,
                    modifier = Modifier.graphicsLayer { scaleX = pop; scaleY = pop },
                )
                Text(
                    tr("$ads ads · $trackers trackers · $malware malware", "$ads pubs · $trackers traqueurs · $malware malwares", "$ads Werbung · $trackers Tracker · $malware Malware"),
                    color = Palette.dim, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                Text(
                    if (done) tr("Tap anywhere to go back", "Touchez pour revenir", "Tippen zum Zurückgehen") else "",
                    color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawThreat(kind: Kind, pos: Offset, s: Float, deg: Float, accent: Color, warn: Color, adLabel: TextLayoutResult) {
    val u = 22.dp.toPx() * s
    rotate(deg, pos) {
        when (kind) {
            Kind.AD -> {
                drawRoundRect(warn, Offset(pos.x - u, pos.y - u * 0.62f), Size(u * 2f, u * 1.24f), CornerRadius(u * 0.3f))
                withTransform({ scale(s, s, pos) }) {
                    drawText(adLabel, topLeft = Offset(pos.x - adLabel.size.width / 2f, pos.y - adLabel.size.height / 2f))
                }
            }
            Kind.TRACKER -> {
                // An eye.
                val r = u * 0.95f
                drawOval(accent.copy(alpha = 0.95f), Offset(pos.x - r, pos.y - r * 0.55f), Size(r * 2f, r * 1.1f))
                drawCircle(Color.Black, r * 0.38f, pos)
                drawCircle(Color.White, r * 0.12f, Offset(pos.x + r * 0.12f, pos.y - r * 0.12f))
            }
            Kind.MALWARE -> {
                // A bug: body, head, six legs, two feelers.
                val g = Color(0xFF4ADE80)
                val leg = Stroke(width = u * 0.14f)
                for (k in -1..1) {
                    val y = pos.y + k * u * 0.38f
                    drawLine(g, Offset(pos.x - u * 0.55f, y), Offset(pos.x - u * 1.05f, y + k * u * 0.25f - u * 0.1f), leg.width)
                    drawLine(g, Offset(pos.x + u * 0.55f, y), Offset(pos.x + u * 1.05f, y + k * u * 0.25f - u * 0.1f), leg.width)
                }
                drawLine(g, Offset(pos.x - u * 0.2f, pos.y - u * 0.95f), Offset(pos.x - u * 0.5f, pos.y - u * 1.35f), leg.width)
                drawLine(g, Offset(pos.x + u * 0.2f, pos.y - u * 0.95f), Offset(pos.x + u * 0.5f, pos.y - u * 1.35f), leg.width)
                drawOval(g, Offset(pos.x - u * 0.6f, pos.y - u * 0.7f), Size(u * 1.2f, u * 1.5f))
                drawCircle(g, u * 0.36f, Offset(pos.x, pos.y - u * 0.85f))
                drawLine(Color.Black.copy(alpha = 0.5f), Offset(pos.x, pos.y - u * 0.55f), Offset(pos.x, pos.y + u * 0.75f), u * 0.08f)
                drawCircle(Color.Black, u * 0.08f, Offset(pos.x - u * 0.14f, pos.y - u * 0.9f))
                drawCircle(Color.Black, u * 0.08f, Offset(pos.x + u * 0.14f, pos.y - u * 0.9f))
            }
        }
    }
}

/** Crumbs flying off the shield after a bite. [q] goes 0 → 1. */
private fun DrawScope.crumbs(c: Offset, q: Float, th: Threat, color: Color) {
    val n = 7
    val reach = 70.dp.toPx() * (0.3f + q)
    for (k in 0 until n) {
        val a = th.angle + PI.toFloat() + (k - n / 2f) * 0.45f
        val p = Offset(c.x + cos(a) * reach * (0.6f + 0.08f * (k % 3)), c.y - 20.dp.toPx() * q + sin(a) * reach * 0.7f)
        drawCircle(color.copy(alpha = (1f - q).coerceIn(0f, 1f)), 3.5.dp.toPx() * (1f - q * 0.6f), p)
    }
    val ring = min(1f, q * 1.4f)
    drawCircle(color.copy(alpha = (0.5f * (1f - ring)).coerceIn(0f, 1f)), 40.dp.toPx() + 50.dp.toPx() * ring, c, style = Stroke(3.dp.toPx() * (1f - ring) + 0.5f))
}
