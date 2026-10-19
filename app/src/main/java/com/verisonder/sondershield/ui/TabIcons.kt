package com.verisonder.sondershield.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/*
 * The bottom bar's icons, drawn line by line so each can move in its own way when its tab
 * is picked (and again on every tap):
 *   Home      the roof drops onto the house and bounces; the walls squash under it
 *   Filter    the shield flips round once
 *   Data      the three bars grow up one after another
 *   Apps      the four tiles pop in one by one while the grid turns a quarter
 *   Settings  the gear spins and settles
 * Same 24-unit drawings as Icons.home/filter/data/apps/settings, 2-unit round strokes.
 */

private val roof = PathParser().parsePathString("M3.5 10.5L12 4l8.5 6.5").toPath()
private val walls = PathParser().parsePathString("M5.5 9v10.5h13V9").toPath()
private val shield = PathParser().parsePathString("M12 3.5l7.5 2.8v5.4c0 4.6-3.1 7.9-7.5 9.3-4.4-1.4-7.5-4.7-7.5-9.3V6.3z").toPath()
private val gear = PathParser().parsePathString(
    "M19.26 9.75L21.86 10.31L21.86 13.69L19.26 14.25L18.72 15.55L20.17 17.77L17.77 20.17L15.55 18.72L14.25 19.26L13.69 21.86L10.31 21.86L9.75 19.26L8.45 18.72L6.23 20.17L3.83 17.77L5.28 15.55L4.74 14.25L2.14 13.69L2.14 10.31L4.74 9.75L5.28 8.45L3.83 6.23L6.23 3.83L8.45 5.28L9.75 4.74L10.31 2.14L13.69 2.14L14.25 4.74L15.55 5.28L17.77 3.83L20.17 6.23L18.72 8.45Z",
).toPath()

private val line = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** How long each icon's move takes, in ms. */
private fun Tab.animMs() = when (this) {
    Tab.Home -> 900
    Tab.Filter -> 650
    Tab.Data -> 750
    Tab.Apps -> 750
    Tab.Settings -> 850
}

/**
 * One bottom-bar icon. [play] is a counter: each time it goes up, the icon plays its move.
 * At rest it is the plain icon.
 */
@Composable
fun TabIcon(tab: Tab, tint: Color, play: Int, size: Dp) {
    val p = remember { Animatable(1f) }
    val first = remember { play }
    LaunchedEffect(play) {
        if (play == first) return@LaunchedEffect // not on first show
        p.snapTo(0f)
        p.animateTo(1f, tween(tab.animMs(), easing = LinearEasing))
    }
    val t = p.value
    // The shield's flip is a 3D turn, done on the layer rather than in the drawing.
    val layer = if (tab == Tab.Filter) {
        Modifier.graphicsLayer {
            rotationY = 360f * easeInOutCubic(t)
            cameraDistance = 10f * density
            val pop = 1f + 0.18f * sin(PI * t).toFloat()
            scaleX = pop
            scaleY = pop
        }
    } else Modifier
    Canvas(Modifier.size(size).then(layer)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            when (tab) {
                Tab.Home -> drawHome(tint, t)
                Tab.Filter -> drawPath(shield, tint, style = line)
                Tab.Data -> drawData(tint, t)
                Tab.Apps -> drawApps(tint, t)
                Tab.Settings -> drawSettings(tint, t)
            }
        }
    }
}

private fun DrawScope.drawHome(c: Color, t: Float) {
    // The roof falls from above and bounces; the walls squash each time it lands.
    val drop = -8f * (1f - easeOutBounce(t))
    val squash = 0.16f * bounceHit(t) * (1f - 0.6f * t)
    withTransform({ scale(1f + squash * 0.5f, 1f - squash, pivot = Offset(12f, 19.5f)) }) {
        drawPath(walls, c, style = line)
    }
    translate(top = drop + squash * 10.5f) { drawPath(roof, c, style = line) }
}

private fun DrawScope.drawData(c: Color, t: Float) {
    val bars = listOf(Triple(5f, 11f, 0), Triple(12f, 4f, 1), Triple(19f, 13f, 2))
    for ((x, top, i) in bars) {
        val local = ((t - i * 0.14f) / 0.6f).coerceIn(0f, 1f)
        val f = easeOutBack(local, 2.2f)
        val y = 20f - (20f - top) * f
        drawLine(c, Offset(x, 20f), Offset(x, y), strokeWidth = 2f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawApps(c: Color, t: Float) {
    val tiles = listOf(Offset(4f, 4f), Offset(14f, 4f), Offset(14f, 14f), Offset(4f, 14f)) // clockwise
    rotate(90f * easeInOutCubic(t), pivot = Offset(12f, 12f)) {
        tiles.forEachIndexed { i, o ->
            val local = ((t - i * 0.1f) / 0.55f).coerceIn(0f, 1f)
            val s = 0.25f + 0.75f * easeOutBack(local, 2.6f)
            scale(s, pivot = Offset(o.x + 3f, o.y + 3f)) {
                drawRect(c, topLeft = o, size = Size(6f, 6f), style = line)
            }
        }
    }
}

private fun DrawScope.drawSettings(c: Color, t: Float) {
    rotate(180f * easeOutBack(t, 1.6f), pivot = Offset(12f, 12f)) { drawPath(gear, c, style = line) }
    drawCircle(c, radius = 3f, center = Offset(12f, 12f), style = line)
}

// ---- easing ----

private fun easeOutBack(x: Float, s: Float = 1.70158f): Float {
    val c3 = s + 1f
    val u = x - 1f
    return 1f + c3 * u * u * u + s * u * u
}

private fun easeInOutCubic(x: Float): Float =
    if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

private fun easeOutBounce(x: Float): Float {
    val n = 7.5625f
    val d = 2.75f
    return when {
        x < 1f / d -> n * x * x
        x < 2f / d -> { val u = x - 1.5f / d; n * u * u + 0.75f }
        x < 2.5f / d -> { val u = x - 2.25f / d; n * u * u + 0.9375f }
        else -> { val u = x - 2.625f / d; n * u * u + 0.984375f }
    }
}

/** A short pulse at each moment the bouncing roof touches down (where easeOutBounce hits 1). */
private fun bounceHit(x: Float): Float {
    val d = 2.75f
    val hits = floatArrayOf(1f / d, 2f / d, 2.5f / d, 1f)
    var best = 0f
    for (h in hits) {
        val dist = abs(x - h)
        if (dist < 0.08f) best = maxOf(best, 1f - dist / 0.08f)
    }
    return best
}
