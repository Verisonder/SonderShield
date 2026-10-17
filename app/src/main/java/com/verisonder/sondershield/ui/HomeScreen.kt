package com.verisonder.sondershield.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
fun HomeScreen(onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val phase by ShieldState.phase.collectAsState()
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Top bar: mark + wordmark, DNS server.
        Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(26.dp, lit = on)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) { Wordmark() }
            val up = Upstreams.byId(prefs.upstreamId)?.name?.substringBefore(" (") ?: "Custom"
            Text(
                up, color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).border(1.5.dp, Palette.line, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Hero.
        Column(Modifier.padding(horizontal = Gutter).padding(top = 26.dp)) {
            Kicker("DNS filter · every app", color = if (on) Palette.accentText else Palette.faint)
            Text(
                when (phase) { Phase.ON -> "Ads stop here."; Phase.STARTING -> "Starting up…"; Phase.OFF -> "Ads get through." },
                style = display(34), color = Palette.text, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
            )
            Text(
                if (on) "Every app asks SonderShield first. ${if (domains > 0) domains.grouped() else "Thousands of"} ad and tracker domains get nothing back."
                else "Tap the shield to block ads and trackers in every app on this phone.",
                color = Palette.dim, fontSize = 15.sp, lineHeight = 22.sp,
            )
        }

        BigToggle(phase, onToggle)

        // Under the swoosh: today's numbers and anything that needs attention.
        val swooshColor by animateColorAsState(if (on) Palette.accent else Palette.off, label = "swoosh")
        Swoosh(blade = swooshColor, below = Palette.panel, modifier = Modifier.padding(top = 18.dp))
        Column(Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = Gutter).padding(top = 4.dp, bottom = 20.dp)) {
            Kicker("Today")
            Spacer(Modifier.height(12.dp))
            val (total, blocked) = today
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LadderStep(total.grouped(), "DNS queries\ntoday", 0, onPanel = true)
                LadderStep(blocked.grouped(), "ads and trackers\nblocked today", 1, onPanel = true)
                LadderStep(
                    if (total > 0) "%.1f%%".format(blocked * 100.0 / total) else "0%",
                    "of everything your\napps asked for", 2, onPanel = true,
                )
            }

            problem?.let { p ->
                Notice(p, "Tap to dismiss") { ShieldState.problem.value = null }
            }
            if (strictDns != null && on) {
                Notice(
                    "Private DNS is set to $strictDns. Android sends DNS straight there, past SonderShield, so nothing gets blocked. Set it to Automatic or Off.",
                    "Open settings",
                ) { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
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

/** The big on/off button: the logo itself, lit when on, with "PROTECTION ON / OFF" under it. */
@Composable
private fun BigToggle(phase: Phase, onClick: () -> Unit) {
    val on = phase != Phase.OFF
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        0.85f, if (phase == Phase.ON) 1.08f else 0.85f,
        infiniteRepeatable(tween(if (phase == Phase.STARTING) 600 else 1800), RepeatMode.Reverse), label = "glow",
    )
    val accent = Palette.accent
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { role = Role.Switch; contentDescription = if (on) "Protection on. Tap to turn off." else "Protection off. Tap to turn on." },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
            if (on) {
                Box(
                    Modifier.size(210.dp).scale(glow).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.10f), accent.copy(alpha = 0f)))),
                )
            }
            Logo(140.dp, lit = on)
        }
        Text(
            if (phase == Phase.STARTING) "STARTING" else if (on) "PROTECTION ON" else "PROTECTION OFF",
            color = if (on) Palette.accentText else Palette.faint, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
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
