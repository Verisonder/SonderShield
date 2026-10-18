package com.verisonder.sondershield.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.verisonder.sondershield.core.ChompGame
import com.verisonder.sondershield.core.Dir
import com.verisonder.sondershield.core.EnemyKind
import com.verisonder.sondershield.data.Prefs
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/*
 * Shield-Man, the easter-egg game (rules in core/ChompGame.kt). The little shield from the
 * Home bar flies to the middle, grows teeth, and the maze draws itself in around it.
 */

/** The SonderShield mark as paths in its 100 × 110 box, plus the two cut edges (for the teeth). */
private object ManParts {
    val shield: Path = PathParser().parsePathString("M50 4L92 18V52C92 78 74 97 50 106C26 97 8 78 8 52V18Z").toPath()
    val top: Path = PathParser().parsePathString("M0 0H100V30Q46 60 0 80Z").toPath()
    val bottom: Path = PathParser().parsePathString("M0 88Q52 70 100 45V110H0Z").toPath()

    /** Point and unit normal on a quadratic curve at t. */
    fun quad(t: Float, p0: Offset, c: Offset, p1: Offset): Pair<Offset, Offset> {
        val u = 1f - t
        val p = Offset(u * u * p0.x + 2 * u * t * c.x + t * t * p1.x, u * u * p0.y + 2 * u * t * c.y + t * t * p1.y)
        val d = Offset(2 * u * (c.x - p0.x) + 2 * t * (p1.x - c.x), 2 * u * (c.y - p0.y) + 2 * t * (p1.y - c.y))
        val l = sqrt(d.x * d.x + d.y * d.y)
        return p to Offset(-d.y / l, d.x / l) // left normal: points down on the top edge
    }
}

/**
 * Draws the shield with its mouth [open] (0 shut … 1 wide) and sharp teeth along the cut.
 * [left] mirrors it to face left.
 */
private fun DrawScope.shieldMan(center: Offset, size: Float, open: Float, left: Boolean, teeth: Float, top: Color, bottom: Color, scale: Float = 1f, spin: Float = 0f) {
    val box = size / 100f
    // The mouth opens on a hinge at the back: the upper jaw tilts up, the lower drops a little.
    val upper = -24f * open
    val lower = 6f * open
    withTransform({
        translate(center.x, center.y)
        rotate(spin, Offset.Zero)
        scale(if (left) -scale else scale, scale, Offset.Zero)
        translate(-50f * box, -55f * box)
        scale(box, box, Offset.Zero)
    }) {
        // Upper jaw.
        withTransform({ rotate(upper, Offset(10f, 78f)) }) {
            clipPath(ManParts.shield) {
                clipPath(ManParts.top) { drawPath(ManParts.shield, top) }
                if (teeth > 0f) teethRow(Offset(0f, 80f), Offset(46f, 60f), Offset(100f, 30f), down = true, count = 5, color = Color.White.copy(alpha = teeth))
            }
        }
        // Lower jaw.
        withTransform({ rotate(lower, Offset(10f, 86f)) }) {
            clipPath(ManParts.shield) {
                clipPath(ManParts.bottom) { drawPath(ManParts.shield, bottom) }
                if (teeth > 0f) teethRow(Offset(0f, 88f), Offset(52f, 70f), Offset(100f, 45f), down = false, count = 4, color = Color.White.copy(alpha = teeth))
            }
        }
    }
}

/**
 * Sharp triangles along a cut edge, pointing into the gap. The upper jaw has five and the
 * lower four, set between them, so they interlock when the mouth shuts; short enough to stay
 * inside the gap. Drawn clipped to the shield so none stick out at the sides.
 */
private fun DrawScope.teethRow(p0: Offset, c: Offset, p1: Offset, down: Boolean, count: Int, color: Color) {
    val n = count
    // Upper: 0.24 … 0.76 (in from the shield's sides); lower: the midpoints between them.
    val step = 0.13f
    val first = if (n == 5) 0.24f else 0.24f + step / 2f
    for (k in 0 until n) {
        val a = first + k * step
        val (p, _) = ManParts.quad(a, p0, c, p1)
        val (pl, _) = ManParts.quad(a - 0.04f, p0, c, p1)
        val (pr, _) = ManParts.quad(a + 0.04f, p0, c, p1)
        // Fangs point straight down (upper jaw) or up (lower), whatever the slope of the cut.
        val s = if (down) 1f else -1f
        val tip = Offset(p.x, p.y + 10f * s)
        // The base starts a little inside the jaw, so no seam shows where tooth meets jaw.
        val path = Path().apply { moveTo(pl.x, pl.y - 3f * s); lineTo(tip.x, tip.y); lineTo(pr.x, pr.y - 3f * s); close() }
        drawPath(path, color)
    }
}

/** Fast out, slight overshoot. */
private val Pop = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.15f)

/**
 * The game. [from] is where the little Home shield sits on screen (centre, px) and its size,
 * so the intro can fly it from there.
 */
@Composable
fun ShieldManGame(from: Pair<Offset, Float>?, onClose: () -> Unit) {
    // The game window gets no system-bar sizes of its own; read them here (from Home's window).
    val dens = LocalDensity.current
    val root = androidx.core.view.ViewCompat.getRootWindowInsets(androidx.compose.ui.platform.LocalView.current)
    val sysBars = root?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    val topBar = with(dens) {
        max(sysBars?.top ?: 0, androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(dens)).toDp()
    }.coerceAtLeast(24.dp)
    // Gesture bar or buttons; at least 34 dp so the bottom row always clears the gesture line.
    val bottomBar = with(dens) {
        max(sysBars?.bottom ?: 0, androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(dens)).toDp()
    }.coerceAtLeast(34.dp)
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val haptic = LocalHapticFeedback.current
    val game = remember {
        ChompGame().also { g ->
            // Name the enemies after things SonderShield really blocked on this phone.
            val (ads, malware) = blockedNames()
            val rnd = java.util.Random()
            g.labeler = { kind ->
                val pool = if (kind == EnemyKind.AD) ads else malware
                val onScreen = g.enemies.map { it.label }.toSet()
                (pool.filter { it !in onScreen }.ifEmpty { pool }).let { if (it.isEmpty()) "" else it[rnd.nextInt(it.size)] }
            }
        }
    }
    var best by remember { mutableStateOf(prefs.sp.getInt("egg.best", 0)) }
    // Name tags over the enemies; remembered between games.
    var showNames by remember { mutableStateOf(prefs.sp.getBoolean("egg.names", true)) }
    var frame by remember { mutableLongStateOf(0L) }
    var clock by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val intro = remember { Animatable(0f) }
    val leaving = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var facingLeft by remember { mutableStateOf(false) }
    // 0 = plain shield, 1 = teeth out and chomping (only while powered up). Eased each frame.
    var jaw by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) { intro.animateTo(1f, tween(1500)) }
    LaunchedEffect(Unit) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            clock += dt
            val target = if (game.powerLeft > 0f && game.state == ChompGame.State.PLAY) 1f else 0f
            jaw += (target - jaw) * min(1f, dt * 9f)
            if (intro.value >= 1f && !closing) game.tick(dt)
            if (game.shield.dir == Dir.LEFT) facingLeft = true else if (game.shield.dir == Dir.RIGHT) facingLeft = false
            while (game.events.isNotEmpty()) {
                when (game.events.removeFirst()) {
                    ChompGame.Event.AD, ChompGame.Event.MALWARE, ChompGame.Event.POWER -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    ChompGame.Event.DIED -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    ChompGame.Event.OVER -> if (game.score > best) { best = game.score; prefs.sp.edit().putInt("egg.best", best).apply() }
                    else -> {}
                }
            }
            frame++
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun close() {
        if (closing) return
        closing = true
        if (game.score > best) prefs.sp.edit().putInt("egg.best", game.score).apply()
        scope.launch { leaving.animateTo(1f, tween(320)); onClose() }
    }

    val accent = Palette.accent
    val text = Palette.text
    val warn = Palette.warn
    val dim = Palette.dim
    val faint = Palette.faint
    val bg = Palette.bg
    val measurer = rememberTextMeasurer()
    val adLabel = remember(measurer) { measurer.measure("AD", TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display)) }
    val density = LocalDensity.current
    // Text drawn on the maze, measured once per string.
    val texts = remember { HashMap<String, androidx.compose.ui.text.TextLayoutResult>() }
    fun label(s: String, style: TextStyle) = texts.getOrPut(s + style.fontSize + style.color) { measurer.measure(s, style) }
    val tagStyle = TextStyle(color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = Body, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal)

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().graphicsLayer { alpha = 1f - leaving.value }
                .pointerInput(Unit) {
                    var acc = Offset.Zero
                    val step = 22.dp.toPx()
                    detectDragGestures(onDragStart = { acc = Offset.Zero }) { change, drag ->
                        change.consume()
                        acc += drag
                        if (abs(acc.x) > step || abs(acc.y) > step) {
                            game.steer(if (abs(acc.x) > abs(acc.y)) (if (acc.x > 0) Dir.RIGHT else Dir.LEFT) else (if (acc.y > 0) Dir.DOWN else Dir.UP))
                            acc = Offset.Zero
                        }
                    }
                },
        ) {
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            // Maze size: full width less margins, leaving room for the HUD above and below.
            val topPx = with(density) { (topBar + 110.dp).toPx() } // status bar + score
            val bottomPx = with(density) { (bottomBar + 64.dp).toPx() } // gesture bar + lives row
            val tile = min((wPx - with(density) { 24.dp.toPx() }) / game.w, (hPx - topPx - bottomPx) / game.h)
            val mazeW = tile * game.w
            val mazeH = tile * game.h
            val ox = (wPx - mazeW) / 2f
            val oy = topPx + (hPx - topPx - bottomPx - mazeH) / 2f
            val manSize = tile * 1.6f
            val i = intro.value

            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION") frame
                // Background fades in over Home.
                drawRect(bg.copy(alpha = (i / 0.35f).coerceIn(0f, 1f)))
                fun at(x: Float, y: Float) = Offset(ox + (x + 0.5f) * tile, oy + (y + 0.5f) * tile)
                val startPos = at(game.shield.x, game.shield.y)
                // The maze draws in from the shield outwards.
                val reveal = ((i - 0.45f) / 0.5f).coerceIn(0f, 1f)
                val maxR = hypot(mazeW, mazeH)
                val r = maxR * reveal
                // Solid colour (accent over the background), so overlapping wall pieces don't show seams.
                val wallColor = androidx.compose.ui.graphics.lerp(bg, accent, 0.32f)
                val inset = tile * 0.18f
                clipRect(ox, oy, ox + mazeW, oy + mazeH) {
                    for (y in 0 until game.h) for (x in 0 until game.w) {
                        val c = at(x.toFloat(), y.toFloat())
                        val dc = (c - startPos).getDistance()
                        if (dc > r) continue
                        val a = ((r - dc) / (tile * 3f)).coerceIn(0f, 1f)
                        if (game.wall(x, y)) {
                            val tl = Offset(ox + x * tile, oy + y * tile)
                            drawRoundRect(wallColor.copy(alpha = a), Offset(tl.x + inset, tl.y + inset), Size(tile - 2 * inset, tile - 2 * inset), CornerRadius(tile * 0.25f))
                            // Bridges to wall neighbours, so walls read as one shape.
                            if (game.wall(x + 1, y) && x + 1 < game.w) drawRect(wallColor.copy(alpha = a), Offset(tl.x + tile / 2, tl.y + inset), Size(tile, tile - 2 * inset))
                            if (game.wall(x, y + 1)) drawRect(wallColor.copy(alpha = a), Offset(tl.x + inset, tl.y + tile / 2), Size(tile - 2 * inset, tile))
                        } else if (game.door(x, y)) {
                            drawRect(warn.copy(alpha = 0.6f * a), Offset(ox + x * tile, oy + y * tile + tile * 0.42f), Size(tile, tile * 0.16f))
                        }
                        val k = y * game.w + x
                        if (game.dots[k]) drawCircle(text.copy(alpha = 0.55f * a), tile * 0.09f, c)
                        if (game.powers[k]) {
                            val pulse = 1f + 0.12f * sin(clock * 6f)
                            shieldMan(c, tile * 0.8f * pulse, 0f, false, 0f, accent.copy(alpha = a), text.copy(alpha = a))
                        }
                    }
                    if (i >= 1f) {
                        // Enemies.
                        val scared = game.powerLeft > 0f
                        val blink = game.powerLeft in 0.01f..2f && ((clock * 6f).toInt() % 2 == 0)
                        for (e in game.enemies) {
                            if (e.away > 0f) continue
                            val p = at(e.m.x, e.m.y)
                            val bob = sin(clock * 8f + e.index) * tile * 0.06f
                            val body = Offset(p.x, p.y + bob)
                            // Its initial as a small icon on the body ("AD" / plain bug when there's no name).
                            val initial = e.label.firstOrNull()?.uppercaseChar()?.toString()
                            when (e.kind) {
                                EnemyKind.AD -> drawThreat(
                                    Kind.AD, body, tile * 0.44f / 22.dp.toPx(), sin(clock * 5f + e.index) * 10f, accent, warn,
                                    if (initial != null) label(initial, TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display)) else adLabel,
                                )
                                EnemyKind.MALWARE -> {
                                    drawThreat(
                                        Kind.MALWARE, body, tile * 0.4f / 22.dp.toPx(), sin(clock * 9f) * 8f, accent, warn, adLabel,
                                        bugColor = if (scared) (if (blink) Color.White else Color(0xFF6B7CFF)) else Color(0xFF4ADE80),
                                    )
                                    if (initial != null) {
                                        drawCircle(Color.Black.copy(alpha = 0.75f), tile * 0.2f, Offset(body.x, body.y + tile * 0.05f))
                                        val l = label(initial, TextStyle(color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display))
                                        drawText(l, topLeft = Offset(body.x - l.size.width / 2f, body.y + tile * 0.05f - l.size.height / 2f))
                                    }
                                }
                            }
                        }
                        // Name tags on top of everything, only once out of the house, and never
                        // two on top of each other (the later one waits until there's room).
                        if (showNames) {
                            val placed = mutableListOf<androidx.compose.ui.geometry.Rect>()
                            for (e in game.enemies) {
                                if (e.away > 0f || !e.leftHouse || e.label.isEmpty()) continue
                                val p = at(e.m.x, e.m.y)
                                val l = label(e.label.take(16), tagStyle)
                                val pad = 4.dp.toPx()
                                val tl = Offset(p.x - l.size.width / 2f - pad, p.y - tile * 0.95f - l.size.height)
                                val rect = androidx.compose.ui.geometry.Rect(tl, Size(l.size.width + 2 * pad, l.size.height.toFloat()))
                                if (placed.any { it.overlaps(rect) }) continue
                                placed += rect
                                drawRoundRect(
                                    (if (e.kind == EnemyKind.AD) warn else if (scared) Color(0xFF6B7CFF) else Color(0xFF16A34A)).copy(alpha = 0.9f),
                                    tl, rect.size, CornerRadius(l.size.height / 2f),
                                )
                                drawText(l, topLeft = Offset(tl.x + pad, tl.y))
                            }
                        }
                        // Points floating up.
                        game.popup?.let { pu ->
                            if (game.popupTime < 1.1f) {
                                val q = game.popupTime / 1.1f
                                val pos = at(pu.x, pu.y - q * 1.2f)
                                val l = label("+${pu.points}", TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display))
                                drawText(l, topLeft = Offset(pos.x - l.size.width / 2f, pos.y - l.size.height / 2f), alpha = 1f - q)
                                if (showNames && pu.label.isNotEmpty()) {
                                    val n = label(pu.label.take(22), TextStyle(color = text, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Body))
                                    drawText(n, topLeft = Offset(pos.x - n.size.width / 2f, pos.y + l.size.height / 2f), alpha = 1f - q)
                                }
                            }
                        }
                    }
                }
                // The shield: in the intro it flies from the Home bar to its start tile.
                val (fromPos, fromSize) = from ?: (Offset(wPx * 0.12f, with(density) { 60.dp.toPx() }) to with(density) { 26.dp.toPx() })
                val fly = Pop.transform((i / 0.6f).coerceIn(0f, 1f))
                val pos = if (i < 1f) Offset(fromPos.x + (startPos.x - fromPos.x) * fly, fromPos.y + (startPos.y - fromPos.y) * fly) else startPos
                val size = if (i < 1f) fromSize + (manSize - fromSize) * fly + manSize * 0.9f * sin((fly * 3.14159f).coerceIn(0f, 3.14159f)) * 0.35f else manSize
                // Teeth and the chomping mouth only while powered up; otherwise a plain shield.
                val teeth = if (i < 1f) 0f else jaw
                val moving = game.state == ChompGame.State.PLAY && game.shield.dir != Dir.NONE
                val bite = if (game.sinceBite < 0.18f) 1f - game.sinceBite / 0.18f else 0f
                val open = if (i < 1f) 0f else jaw * if (moving) max(0.15f + 0.85f * abs(sin(clock * 14f)), bite) else 0.25f
                val dyingQ = if (game.state == ChompGame.State.DYING) (game.stateTime / 1.2f).coerceIn(0f, 1f) else 0f
                // All white until you take a power shield; then the top turns blue (and blinks
                // white in its last two seconds, like the scared malware).
                val powered = i >= 1f && game.powerLeft > 0f
                val fading = game.powerLeft in 0.01f..2f && ((clock * 6f).toInt() % 2 == 0)
                val topColor = when {
                    i < 1f -> androidx.compose.ui.graphics.lerp(accent, text, ((i - 0.4f) / 0.3f).coerceIn(0f, 1f))
                    powered && !fading -> accent
                    else -> text
                }
                shieldMan(
                    pos, size, open, facingLeft, teeth, topColor, text,
                    scale = 1f - dyingQ, spin = dyingQ * 540f,
                )
            }

            // HUD.
            val hud = ((i - 0.8f) / 0.2f).coerceIn(0f, 1f)
            Column(Modifier.fillMaxWidth().padding(top = topBar).padding(horizontal = Gutter, vertical = 10.dp).graphicsLayer { alpha = hud }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Kicker(tr("Shield-Man", "Shield-Man", "Shield-Man"), Modifier.weight(1f))
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable { close() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.close, tr("Close", "Fermer", "Schließen"), tint = text, modifier = Modifier.size(20.dp)) }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                    @Suppress("UNUSED_EXPRESSION") frame
                    Text("${game.score}", style = display(30), color = text)
                    Spacer(Modifier.width(10.dp))
                    Text(tr("LEVEL ${game.level}", "NIVEAU ${game.level}", "LEVEL ${game.level}"), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 6.dp))
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(tr("BEST", "RECORD", "REKORD"), color = faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text("${max(best, game.score)}", color = dim, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            // Right under the maze, not at the screen's edge.
            val underMaze = with(density) { (oy + mazeH).toDp() } + 18.dp
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = underMaze).graphicsLayer { alpha = hud },
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                @Suppress("UNUSED_EXPRESSION") frame
                repeat(game.lives.coerceAtLeast(0)) { Logo(18.dp, lit = true) }
                Spacer(Modifier.width(10.dp))
                Text("${game.eatenAds} " + tr("ads", "pubs", "Werbung") + " · ${game.eatenMalware} malware", color = faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                // Show / hide the names over the enemies.
                val src = remember { MutableInteractionSource() }
                Row(
                    Modifier.height(30.dp).pressScale(src).clip(CircleShape)
                        .background(if (showNames) accent else Color.Transparent)
                        .border(1.5.dp, if (showNames) accent else Palette.line, CircleShape)
                        .clickable(interactionSource = src, indication = null) {
                            showNames = !showNames
                            prefs.sp.edit().putBoolean("egg.names", showNames).apply()
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showNames) tr("Names on", "Noms affichés", "Namen an") else tr("Names off", "Noms masqués", "Namen aus"),
                        color = if (showNames) Palette.onAccent else dim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Messages over the maze.
            @Suppress("UNUSED_EXPRESSION") frame
            val msg: Pair<String, String>? = when (game.state) {
                ChompGame.State.READY -> if (i >= 1f) (tr("Ready?", "Prêt ?", "Bereit?") to tr("Swipe to move. Eat the ads, dodge the malware.", "Glissez pour bouger. Mangez les pubs, évitez les malwares.", "Wische zum Bewegen. Friss Werbung, meide Malware.")) else null
                ChompGame.State.CLEAR -> tr("Clean!", "Propre !", "Sauber!") to tr("Level ${game.level + 1} next", "Niveau ${game.level + 1}", "Gleich Level ${game.level + 1}")
                ChompGame.State.OVER -> tr("Game over", "Perdu", "Game over") to tr("Tap to play again", "Touchez pour rejouer", "Tippen für neue Runde")
                else -> null
            }
            if (msg != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(top = 60.dp).clip(blockShape).background(Palette.panel.copy(alpha = 0.92f))
                        .let { if (game.state == ChompGame.State.OVER) it.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { game.restart() } else it }
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(msg.first, style = display(26), color = text)
                    Text(msg.second, color = dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    if (game.state == ChompGame.State.OVER && game.eatenNames.isNotEmpty()) {
                        val names = game.eatenNames.distinct().takeLast(4).reversed().joinToString(", ")
                        Text(tr("You ate $names", "Vous avez mangé $names", "Gefressen: $names"), color = faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (game.state == ChompGame.State.OVER && game.score >= best && game.score > 0) {
                        Text(tr("New best!", "Nouveau record !", "Neuer Rekord!"), color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

/**
 * Real names for the enemies: domains SonderShield blocked on this phone, shortened to
 * "doubleclick.net". Ads from recent blocks and the top-blocked list; malware from hits on
 * the threat lists. Either can be empty (then the enemy has no name).
 */
private fun blockedNames(): Pair<List<String>, List<String>> {
    val log = com.verisonder.sondershield.core.ShieldState.log.value
    val threatLists = com.verisonder.sondershield.data.Stats.THREAT_LISTS
    fun isThreat(r: String) = r.startsWith("list:") && r.removePrefix("list:") in threatLists
    val malware = log.filter { it.verdict == 1 && isThreat(it.reason) }.map { shortDomain(it.domain) }.distinct()
    val ads = (log.filter { it.verdict == 1 && !isThreat(it.reason) }.map { it.domain } +
        com.verisonder.sondershield.core.ShieldState.stats.value.top.map { it.first })
        .map { shortDomain(it) }.filter { it !in malware }.distinct().take(40)
    return ads to malware.take(40)
}

/** "googleads.g.doubleclick.net" → "doubleclick.net" (keeps "bbc.co.uk"-style endings). */
internal fun shortDomain(d: String): String {
    val p = d.trimEnd('.').lowercase().split('.').filter { it.isNotEmpty() }
    if (p.size <= 2) return p.joinToString(".")
    val second = setOf("co", "com", "net", "org", "ac", "gov", "edu")
    return if (p[p.size - 2] in second && p.last().length == 2) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
}
