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
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
 * Easter egg: tap the little shield on Home seven times to play Shield-Man (ShieldMan.kt).
 * This file holds the tap counter and the drawings of the ads, trackers and malware.
 */

internal enum class Kind { AD, TRACKER, MALWARE }


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

internal fun DrawScope.drawThreat(kind: Kind, pos: Offset, s: Float, deg: Float, accent: Color, warn: Color, adLabel: TextLayoutResult, bugColor: Color = Color(0xFF4ADE80)) {
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
                val g = bugColor
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


/**
 * The SonderShield mark that hides the game: seven quick taps start Shield-Man. Each tap
 * wiggles it; from the fourth the phone ticks. Used on Home and in Settings › About.
 */
@androidx.compose.runtime.Composable
fun SecretLogo(size: androidx.compose.ui.unit.Dp, lit: Boolean) {
    val taps = androidx.compose.runtime.remember { SecretTaps() }
    var egg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val wiggle = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var at by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<Offset, Float>?>(null) }
    Logo(
        size, lit = lit,
        modifier = Modifier
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                at = Offset(p.x + c.size.width / 2f, p.y + c.size.height / 2f) to c.size.width.toFloat()
            }
            .graphicsLayer { rotationZ = wiggle.value }
            .clickable(interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }, indication = null) {
                val n = taps.tap()
                if (n >= 4) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                scope.launch {
                    wiggle.snapTo(if (n % 2 == 0) -2f * n else 2f * n)
                    wiggle.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 600f))
                }
                if (n >= 7) { taps.reset(); egg = true }
            },
    )
    if (egg) ShieldManGame(from = at, onClose = { egg = false })
}
