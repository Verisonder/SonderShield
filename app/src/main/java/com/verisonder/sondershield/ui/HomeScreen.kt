package com.verisonder.sondershield.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.core.Upstreams
import com.verisonder.sondershield.data.Prefs
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onToggle: () -> Unit, scroll: ScrollState) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val phase by ShieldState.phase.collectAsState()
    val alwaysOn by ShieldState.alwaysOn.collectAsState()
    val problem by ShieldState.problem.collectAsState()
    val today by ShieldState.today.collectAsState()
    val domains by ShieldState.domains.collectAsState()
    var strictDns by remember { mutableStateOf<String?>(null) }
    val on = phase != Phase.OFF

    LaunchedEffect(phase) {
        while (true) {
            strictDns = privateDnsHost(ctx)
            delay(5000)
        }
    }

    // The first screen fits exactly (the button area takes whatever height is left); scrolling
    // past it, under the swoosh, shows the all-time stats.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val screen = maxHeight
    val screenPx = with(LocalDensity.current) { screen.toPx() }
    val decay = rememberSplineBasedDecay<Float>()
    val snap = remember(screenPx, decay) { PageSnapFling(scroll, screenPx, decay) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll, flingBehavior = snap)) {
    Column(Modifier.fillMaxWidth().height(screen)) {
        // Top bar: mark + wordmark, protection profile.
        Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            // Seven quick taps on the little shield: the easter egg.
            val taps = remember { SecretTaps() }
            var egg by remember { mutableStateOf(false) }
            val wiggle = remember { Animatable(0f) }
            val eggScope = androidx.compose.runtime.rememberCoroutineScope()
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            Logo(
                26.dp, lit = on,
                modifier = Modifier.graphicsLayer { rotationZ = wiggle.value }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        val n = taps.tap()
                        if (n >= 4) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        eggScope.launch {
                            wiggle.snapTo(if (n % 2 == 0) -6f * n / 3f else 6f * n / 3f)
                            wiggle.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 600f))
                        }
                        if (n >= 7) { taps.reset(); egg = true }
                    },
            )
            if (egg) ShieldChomp(onClose = { egg = false })
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).clipToBounds()) { Wordmark() }
            Spacer(Modifier.width(8.dp))
            // The profile in use; tap to switch or make your own.
            ProfileChip()
        }

        // Hero: the headline rolls over when the state changes.
        Column(Modifier.padding(horizontal = Gutter).padding(top = 14.dp)) {
            val kickerColor by animateColorAsState(if (on) Palette.accentText else Palette.faint, tween(400), label = "kicker")
            Kicker(tr("DNS filter · every app", "Filtre DNS · toutes les applis", "DNS-Filter · jede App"), color = kickerColor)
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    (slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(300)))
                        .togetherWith(slideOutVertically(tween(260)) { -it / 2 } + fadeOut(tween(200)))
                },
                label = "headline",
            ) { p ->
                Text(
                    when (p) {
                        Phase.ON -> tr("Ads stop here.", "Pubs bloquées.", "Werbefrei.")
                        Phase.STARTING -> tr("Starting up…", "Démarrage…", "Startet…")
                        Phase.OFF -> tr("Ads get through.", "Pubs autorisées.", "Mit Werbung.")
                    },
                    style = display(34), color = Palette.text, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                )
            }
            // One short line under the headline.
            Text(
                if (on) {
                    if (domains > 0) tr("${domains.grouped()} ad and tracker domains blocked", "${domains.grouped()} domaines de pubs et traqueurs bloqués", "${domains.grouped()} Werbe- und Tracker-Domains blockiert")
                    else tr("Blocking ads and trackers in every app", "Pubs et traqueurs bloqués dans toutes les applis", "Werbung und Tracker in jeder App blockiert")
                } else tr("Tap the shield to block ads in every app", "Touchez le bouclier pour bloquer les pubs", "Tippe aufs Schild, um Werbung zu blockieren"),
                color = Palette.faint, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Logo as big as the space allows (its glow area is 1.5x, plus the label), 64 to 140 dp.
            val logo = ((maxHeight - 30.dp) / 1.5f).coerceIn(64.dp, 140.dp)
            BigToggle(phase, logo, onToggle, alwaysOn = alwaysOn && phase == Phase.ON)
        }

        // Under the swoosh: today's numbers and anything that needs attention.
        val swooshColor by animateColorAsState(if (on) Palette.accent else Palette.off, tween(500), label = "swoosh")
        val sweep = remember { Animatable(1f) }
        LaunchedEffect(on) {
            if (on) { sweep.snapTo(0f); sweep.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        }
        Swoosh(blade = swooshColor, below = Palette.panel, progress = sweep.value)
        Column(Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = Gutter).padding(top = 4.dp, bottom = 16.dp)) {
            Kicker(tr("Today", "Aujourd'hui", "Heute"))
            Spacer(Modifier.height(12.dp))
            val (total, blocked) = today
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LadderStep(total.toDouble(), { it.toLong().grouped() }, tr("DNS queries\ntoday", "requêtes DNS\naujourd'hui", "DNS-Anfragen\nheute"), 0, onPanel = true)
                LadderStep(blocked.toDouble(), { it.toLong().grouped() }, tr("ads and trackers\nblocked today", "pubs et traqueurs\nbloqués aujourd'hui", "Werbung und Tracker\nheute blockiert"), 1, onPanel = true)
                LadderStep(
                    if (total > 0) blocked * 100.0 / total else 0.0, { "%.1f%%".format(it) },
                    tr("of everything your\napps asked for", "de tout ce que vos\napplis ont demandé", "von allem, was deine\nApps angefragt haben"), 2, onPanel = true,
                )
            }

            problem?.let { p ->
                Notice(p, tr("Tap to dismiss", "Touchez pour fermer", "Tippen zum Schließen")) { ShieldState.problem.value = null }
            }
            if (strictDns != null && on) {
                Notice(
                    tr(
                        "Private DNS is set to $strictDns, which skips SonderShield. Set it to Automatic or Off.",
                        "Le DNS privé est réglé sur $strictDns, ce qui contourne SonderShield. Réglez-le sur Automatique ou Désactivé.",
                        "Privates DNS ist auf $strictDns gestellt und umgeht SonderShield. Stelle es auf Automatisch oder Aus.",
                    ),
                    tr("Open settings", "Ouvrir les réglages", "Einstellungen öffnen"),
                ) { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            ScrollHint(visible = scroll.value < 8)
        }
    }
    HomeStats()
    }
    }
}

/** "MORE" with a bobbing chevron at the bottom of the first screen, until the user scrolls. */
@Composable
private fun ScrollHint(visible: Boolean) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(250), label = "hint")
    val bob = rememberInfiniteTransition(label = "bob")
    val dy by bob.animateFloat(0f, 4f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dy")
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).graphicsLayer { this.alpha = alpha; translationY = dy.dp.toPx() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("MORE", "PLUS", "MEHR"), color = Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = 90f })
    }
}

/** Warning card on the panel: amber text, a call to action under it. */
@Composable
private fun Notice(text: String, action: String, onClick: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Block(color = Palette.card2, onClick = onClick) {
        Text(text, color = Palette.warn, fontSize = 14.sp, lineHeight = 19.sp)
        Text(action.uppercase(), color = Palette.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * The big on/off button: the logo itself. Turning on, the two pieces spring apart and snap
 * shut, a ring pulses out and the glow breathes; turning off, they settle grey.
 */
@Composable
private fun BigToggle(phase: Phase, logo: Dp, onClick: () -> Unit, alwaysOn: Boolean = false) {
    val on = phase != Phase.OFF
    val accent = Palette.accent
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "press")

    val split = remember { Animatable(0f) }
    val ring = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(phase) {
        if (first) { first = false; return@LaunchedEffect } // no show on opening the app
        // Every branch ends with the pieces closed and the ring gone, even when a quick second
        // tap cancels the previous animation halfway.
        when (phase) {
            Phase.ON -> {
                launch { ring.snapTo(0f); ring.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
                split.animateTo(1f, tween(140))
                split.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 380f))
            }
            Phase.OFF -> {
                launch { if (ring.value < 1f) ring.animateTo(1f, tween(250)) }
                split.animateTo(0.5f, tween(120))
                split.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f))
            }
            Phase.STARTING -> {
                launch { if (ring.value < 1f) ring.animateTo(1f, tween(250)) }
                split.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f))
            }
        }
    }
    // Ignore a second tap while starting up or right after a tap: on/off can't flip faster
    // than the VPN comes up anyway, and quick double taps used to leave the animation stuck.
    var lastTap by remember { mutableLongStateOf(0L) }
    val guardedClick = {
        val now = System.currentTimeMillis()
        if (phase != Phase.STARTING && now - lastTap > 800) {
            lastTap = now
            onClick()
        }
    }
    val breathe = rememberInfiniteTransition(label = "breathe")
    val glow by breathe.animateFloat(
        0.86f, if (phase == Phase.ON) 1.08f else 0.86f,
        infiniteRepeatable(tween(if (phase == Phase.STARTING) 600 else 1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow",
    )
    // While starting, the logo rocks gently so it's clear something is happening.
    val rock by breathe.animateFloat(-1f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "rock")
    val glowAlpha by animateFloatAsState(if (on) 1f else 0f, tween(500), label = "glowAlpha")

    val descOn = if (alwaysOn) tr("Protection always on. Tap to open VPN settings.", "Protection toujours active. Touchez pour ouvrir les réglages VPN.", "Schutz immer an. Tippen öffnet die VPN-Einstellungen.")
        else tr("Protection on. Tap to turn off.", "Protection activée. Touchez pour désactiver.", "Schutz an. Tippen zum Ausschalten.")
    val descOff = tr("Protection off. Tap to turn on.", "Protection désactivée. Touchez pour activer.", "Schutz aus. Tippen zum Einschalten.")
    // Only the shield itself is the button (a circle the logo's size), so taps beside it
    // or on the label don't toggle protection by accident.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val area = logo * 1.5f
        Box(Modifier.size(area), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(area)) {
                val r = size.minDimension / 2
                if (glowAlpha > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.34f * glowAlpha), accent.copy(alpha = 0.10f * glowAlpha), accent.copy(alpha = 0f)),
                            radius = r * glow,
                        ),
                        radius = r * glow,
                    )
                }
                if (ring.value < 1f) {
                    drawCircle(
                        accent.copy(alpha = (1f - ring.value) * 0.8f), radius = r * (0.45f + 0.6f * ring.value),
                        style = Stroke(width = (6f - 4f * ring.value).dp.toPx()),
                    )
                }
            }
            Logo(
                logo, lit = on, split = split.value,
                modifier = Modifier
                    .clickable(interactionSource = press, indication = null, onClick = guardedClick)
                    .semantics { role = Role.Switch; contentDescription = if (on) descOn else descOff }
                    .graphicsLayer {
                    scaleX = pressScale; scaleY = pressScale
                    rotationZ = if (phase == Phase.STARTING) rock * 4f else 0f
                },
            )
        }
        AnimatedContent(
            targetState = phase to alwaysOn,
            transitionSpec = { fadeIn(tween(250)).togetherWith(fadeOut(tween(150))) },
            label = "state",
        ) { (p, always) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        p == Phase.STARTING -> tr("STARTING", "DÉMARRAGE", "STARTET")
                        p == Phase.ON && always -> tr("PROTECTION ALWAYS ON", "PROTECTION TOUJOURS ACTIVE", "SCHUTZ IMMER AN")
                        p == Phase.ON -> tr("PROTECTION ON", "PROTECTION ACTIVÉE", "SCHUTZ AN")
                        else -> tr("PROTECTION OFF", "PROTECTION DÉSACTIVÉE", "SCHUTZ AUS")
                    },
                    color = if (p != Phase.OFF) Palette.accentText else Palette.faint, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                )
                if (p == Phase.ON && always) {
                    Text(
                        tr("Set by Always-on VPN. Tap the shield to change it.", "Activé par le VPN permanent. Touchez le bouclier pour le changer.", "Durch Always-on-VPN. Tippe aufs Schild, um es zu ändern."),
                        color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** The Private DNS host when it is in strict mode ("hostname"), which bypasses the tunnel. */
private fun privateDnsHost(ctx: Context): String? {
    if (android.os.Build.VERSION.SDK_INT < 28) return null
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
    @Suppress("DEPRECATION")
    for (n in cm.allNetworks) {
        val host = cm.getLinkProperties(n)?.privateDnsServerName
        if (!host.isNullOrEmpty()) return host
    }
    return null
}

/**
 * Home scrolls like two pages: letting go anywhere between the first screen and the stats
 * settles on one of them (by direction and speed); inside the stats it scrolls freely.
 */
private class PageSnapFling(
    private val scroll: ScrollState,
    private val pagePx: Float,
    private val decay: DecayAnimationSpec<Float>,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val start = scroll.value.toFloat()
        val projected = decay.calculateTargetValue(start, initialVelocity)
        val target: Float? = when {
            start < pagePx -> when {
                initialVelocity > 800f -> pagePx
                initialVelocity < -800f -> 0f
                else -> if (projected > pagePx / 2) pagePx else 0f
            }
            projected < pagePx -> if (projected < pagePx / 2) 0f else pagePx // flung back up out of the stats
            else -> null
        }
        if (target == null) {
            // Free scroll in the stats.
            var velocity = initialVelocity
            var last = 0f
            AnimationState(0f, initialVelocity).animateDecay(decay) {
                val delta = value - last
                last = value
                val consumed = scrollBy(delta)
                velocity = this.velocity
                if (kotlin.math.abs(delta - consumed) > 0.5f) cancelAnimation()
            }
            return velocity
        }
        var last = start
        animate(start, target, initialVelocity, spring(dampingRatio = 0.85f, stiffness = 260f)) { v, _ ->
            scrollBy(v - last)
            last = v
        }
        return 0f
    }
}
