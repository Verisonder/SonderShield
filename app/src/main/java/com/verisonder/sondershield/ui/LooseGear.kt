package com.verisonder.sondershield.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The Settings gear's taps and whether it is off on its own. */
internal class GearState {
    /** Centre of the gear's slot in the bar, in root pixels. */
    var home by mutableStateOf(Offset.Unspecified)
    var loose by mutableStateOf(false)

    /** The gear's colour in the bar right now (it fades from and back to this). */
    var barTint by mutableStateOf(Color.Unspecified)

    /** 0..3, grows with each quick tap from the 4th (wider spin, more shake). */
    var wild by mutableIntStateOf(0)
    private var last = 0L
    private var streak = 0

    fun tap() {
        if (loose) return
        val now = SystemClock.uptimeMillis()
        streak = if (now - last <= 1500L) streak + 1 else 1
        last = now
        wild = (streak - 3).coerceIn(0, 3)
        if (streak >= 7) {
            streak = 0
            wild = 0
            loose = true
        }
    }
}

/** Drawn over the whole screen while the gear is loose; touches pass through except on the gear. */
@Composable
internal fun LooseGear(g: GearState) {
    if (!g.loose || g.home == Offset.Unspecified) return
    val d = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val text = Palette.text
    val bar = if (g.barTint == Color.Unspecified) text else g.barTint
    val small = 21f * d
    val big = 46f * d
    var pos by remember { mutableStateOf(g.home) }
    var deg by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableFloatStateOf(small) }
    var caught by remember { mutableStateOf(false) }
    var mix by remember { mutableFloatStateOf(0f) } // 0 = bar colour, 1 = text colour

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        LaunchedEffect(Unit) {
            var x = g.home.x
            var y = g.home.y
            var vx = (if (Random.nextBoolean()) 1f else -1f) * (500f + Random.nextFloat() * 600f) * d
            var vy = -(1500f + Random.nextFloat() * 500f) * d
            val grav = 2600f * d
            var omega = if (vx > 0) 1400f else -1400f // deg/s, still spinning from the taps
            var t = 0f
            var still = 0f
            var lastBuzz = -1f
            var prev = withFrameNanos { it }
            while (!caught && t < 9f && still < 0.5f) {
                val now = withFrameNanos { it }
                val dt = ((now - prev) / 1e9f).coerceIn(0f, 1f / 30f)
                prev = now
                t += dt
                size = small + (big - small) * easeOutBack((t / 0.35f).coerceAtMost(1f), 2f)
                mix = (t / 0.3f).coerceIn(0f, 1f)
                val r = size / 2f
                vy += grav * dt
                x += vx * dt
                y += vy * dt
                var hit = 0f
                if (x < r) { x = r; hit = abs(vx); vx = -vx * 0.85f; omega = -omega * 0.8f }
                if (x > w - r) { x = w - r; hit = abs(vx); vx = -vx * 0.85f; omega = -omega * 0.8f }
                if (y < r) { y = r; hit = max(hit, abs(vy)); vy = -vy * 0.85f }
                var floor = false
                if (y >= h - r) {
                    y = h - r
                    floor = true
                    if (vy > 160f * d) { hit = max(hit, vy); vy = -vy * 0.72f } else vy = 0f
                    vx *= 1f - 1.6f * dt // rolling friction
                    omega = vx / r * (180f / PI.toFloat()) // rolls along the bottom
                } else {
                    omega *= 1f - 0.3f * dt
                }
                deg += omega * dt
                if (hit > 300f * d && t - lastBuzz > 0.08f) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    lastBuzz = t
                }
                still = if (floor && vy == 0f && abs(vx) < 25f * d) still + dt else 0f
                pos = Offset(x, y)
            }
            // Back home: a hop to its slot, shrinking, landing on a tooth so it looks untouched.
            val from = pos
            val fromDeg = deg
            val toDeg = ((fromDeg + if (omega >= 0f) 360f else -360f) / 45f).roundToInt() * 45f
            val fromSize = size
            val fromMix = mix
            val k = Animatable(0f)
            k.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) {
                val f = value
                val to = g.home
                pos = Offset(from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f - sin(PI.toFloat() * f) * 120f * d)
                deg = fromDeg + (toDeg - fromDeg) * f
                size = fromSize + (small - fromSize) * f
                mix = fromMix * (1f - ((f - 0.55f) / 0.45f).coerceIn(0f, 1f)) // back to the bar colour as it lands
            }
            g.loose = false
        }

        Canvas(Modifier.fillMaxSize()) {
            translate(pos.x - size / 2f, pos.y - size / 2f) {
                scale(size / 24f, pivot = Offset.Zero) { gearIcon(lerp(bar, text, mix), deg) }
            }
        }
        // Tap it to catch it.
        val touch = size + 24f * d
        Box(
            Modifier
                .offset { IntOffset((pos.x - touch / 2f).roundToInt(), (pos.y - touch / 2f).roundToInt()) }
                .size(with(LocalDensity.current) { touch.toDp() })
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { caught = true },
        )
    }
}
