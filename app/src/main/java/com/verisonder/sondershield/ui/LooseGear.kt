package com.verisonder.sondershield.ui

import android.os.SystemClock
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import android.view.Surface
import android.hardware.SensorManager
import android.hardware.SensorEventListener
import android.hardware.SensorEvent
import android.hardware.Sensor
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
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

    /** Set when its slot is tapped while it's loose: it hops back. */
    var recall by mutableStateOf(false)

    /** 0..3, grows with each quick tap from the 4th (wider spin, more shake). */
    var wild by mutableIntStateOf(0)
    private var last = 0L
    private var streak = 0

    private var freedAt = 0L

    fun tap() {
        val now = SystemClock.uptimeMillis()
        // Tapping the empty slot calls it home, but only once it has been out for 10 s.
        if (loose) { if (now - freedAt >= 10_000L) recall = true; return }
        streak = if (now - last <= 1500L) streak + 1 else 1
        last = now
        wild = (streak - 3).coerceIn(0, 3)
        if (streak >= 7) {
            streak = 0
            wild = 0
            freedAt = now
            loose = true
        }
    }
}

/** Where the loose gear is and how it moves, in root pixels (read and written every frame). */
private class GearBody(var x: Float, var y: Float) {
    var vx = 0f
    var vy = 0f
    var omega = 0f // deg/s
    var held = false
}

/**
 * Drawn over the whole screen while the gear is loose; touches pass through except on the gear.
 * It falls and bounces; drag it to pick it up and let go to throw it. Tapping its empty slot
 * in the bar ([GearState.recall]) sends it home.
 */
@Composable
internal fun LooseGear(g: GearState) {
    if (!g.loose || g.home == Offset.Unspecified) return
    val d = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val text = Palette.text
    val bar = if (g.barTint == Color.Unspecified) text else g.barTint
    val small = 21f * d
    val big = 46f * d
    val body = remember { GearBody(g.home.x, g.home.y) }
    var pos by remember { mutableStateOf(g.home) }
    var deg by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableFloatStateOf(small) }
    var mix by remember { mutableFloatStateOf(0f) } // 0 = bar colour, 1 = text colour

    // Pull from the phone's accelerometer, in screen axes (x right, y down), in m/s².
    // Tilting moves the "down" with the real one; shaking the phone throws it about.
    val pull = remember { floatArrayOf(0f, SensorManager.GRAVITY_EARTH) }
    val ctx = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val ax = e.values[0]
                val ay = e.values[1]
                // Device axes → screen axes (y up) for the current screen rotation.
                val rot = view.display?.rotation ?: Surface.ROTATION_0
                val (sx, sy) = when (rot) {
                    Surface.ROTATION_90 -> -ay to ax
                    Surface.ROTATION_180 -> -ax to -ay
                    Surface.ROTATION_270 -> ay to -ax
                    else -> ax to ay
                }
                // The reading points away from the pull; flip x, and y is already "down" on screen.
                pull[0] = -sx
                pull[1] = sy
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (acc != null) sm?.registerListener(listener, acc, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        LaunchedEffect(Unit) {
            body.vx = (if (Random.nextBoolean()) 1f else -1f) * (500f + Random.nextFloat() * 600f) * d
            body.vy = -(1500f + Random.nextFloat() * 500f) * d
            body.omega = if (body.vx > 0) 1400f else -1400f // still spinning from the taps
            var t = 0f
            var lastBuzz = -1f
            var prev = withFrameNanos { it }
            while (!g.recall) {
                val now = withFrameNanos { it }
                val dt = ((now - prev) / 1e9f).coerceIn(0f, 1f / 30f)
                prev = now
                t += dt
                size = small + (big - small) * easeOutBack((t / 0.35f).coerceAtMost(1f), 2f)
                mix = (t / 0.3f).coerceIn(0f, 1f)
                val r = size / 2f
                if (body.held) {
                    // In the finger: no gravity, just a slowing spin.
                    body.x = body.x.coerceIn(r, w - r)
                    body.y = body.y.coerceIn(r, h - r)
                    body.omega *= 1f - 2f * dt
                } else {
                    val g = 2600f * d / SensorManager.GRAVITY_EARTH
                    val cap = 3f * SensorManager.GRAVITY_EARTH
                    body.vx += pull[0].coerceIn(-cap, cap) * g * dt
                    body.vy += pull[1].coerceIn(-cap, cap) * g * dt
                    body.x += body.vx * dt
                    body.y += body.vy * dt
                    var hit = 0f
                    var rollX = false // resting on the top or bottom: rolls sideways
                    var rollY = false // resting on a side: rolls up/down
                    val rest = 160f * d
                    if (body.x < r || body.x > w - r) {
                        body.x = body.x.coerceIn(r, w - r)
                        if (abs(body.vx) > rest) { hit = abs(body.vx); body.vx = -body.vx * 0.75f; body.omega = -body.omega * 0.8f } else { body.vx = 0f; rollY = true }
                    }
                    if (body.y < r || body.y > h - r) {
                        body.y = body.y.coerceIn(r, h - r)
                        if (abs(body.vy) > rest) { hit = max(hit, abs(body.vy)); body.vy = -body.vy * 0.72f } else { body.vy = 0f; rollX = true }
                    }
                    val deg2 = 180f / PI.toFloat()
                    if (rollX) {
                        body.vx *= 1f - 1.6f * dt // rolling friction
                        if (abs(body.vx) < 4f * d) body.vx = 0f
                        body.omega = (if (body.y > h / 2f) body.vx else -body.vx) / r * deg2
                    } else if (rollY) {
                        body.vy *= 1f - 1.6f * dt
                        if (abs(body.vy) < 4f * d) body.vy = 0f
                        body.omega = (if (body.x < w / 2f) body.vy else -body.vy) / r * deg2
                    } else {
                        body.omega *= 1f - 0.3f * dt
                    }
                    if (hit > 300f * d && t - lastBuzz > 0.08f) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastBuzz = t
                    }
                }
                deg += body.omega * dt
                pos = Offset(body.x, body.y)
            }
            // Back home: a hop to its slot, shrinking, landing on a tooth so it looks untouched.
            body.held = false
            val from = pos
            val fromDeg = deg
            val toDeg = ((fromDeg + if (body.omega >= 0f) 360f else -360f) / 45f).roundToInt() * 45f
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
            g.recall = false
            g.loose = false
        }

        Canvas(Modifier.fillMaxSize()) {
            translate(pos.x - size / 2f, pos.y - size / 2f) {
                scale(size / 24f, pivot = Offset.Zero) { gearIcon(lerp(bar, text, mix), deg) }
            }
        }
        // Grab it: drag to carry it, let go to throw it.
        val touch = size + 24f * d
        Box(
            Modifier
                .offset { IntOffset((pos.x - touch / 2f).roundToInt(), (pos.y - touch / 2f).roundToInt()) }
                .size(with(LocalDensity.current) { touch.toDp() })
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            if (g.recall) return@detectDragGestures
                            body.held = true
                            body.vx = 0f
                            body.vy = 0f
                            tracker.resetTracking()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity()
                            val cap = 4000f * d
                            body.vx = v.x.coerceIn(-cap, cap)
                            body.vy = v.y.coerceIn(-cap, cap)
                            body.omega += body.vx / (size / 2f) * (180f / PI.toFloat()) * 0.5f
                            body.held = false
                        },
                        onDragCancel = { body.held = false },
                    ) { change, drag ->
                        change.consume()
                        body.x += drag.x
                        body.y += drag.y
                        tracker.addPosition(change.uptimeMillis, Offset(body.x, body.y))
                    }
                },
        )
    }
}
