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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.core.Upstreams
import com.verisonder.sondershield.data.Prefs
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onToggle: () -> Unit, goTo: (Tab) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val phase by ShieldState.phase.collectAsState()
    val problem by ShieldState.problem.collectAsState()
    val today by ShieldState.today.collectAsState()
    val domains by ShieldState.domains.collectAsState()
    var strictDns by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(phase) {
        while (true) {
            strictDns = privateDnsHost(ctx)
            delay(5000)
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sonder", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.text)
            Text("Shield", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.accent, modifier = Modifier.weight(1f))
            val up = Upstreams.byId(prefs.upstreamId)?.name?.substringBefore(" (") ?: "Custom"
            Text(
                up, fontSize = 12.sp, color = Palette.dim,
                modifier = Modifier.clip(CircleShape).border(1.dp, Palette.line, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        ShieldButton(phase, onToggle)

        Text(
            when (phase) { Phase.ON -> "Protected"; Phase.STARTING -> "Starting…"; Phase.OFF -> "Not protected" },
            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (phase == Phase.OFF) "Tap the shield to turn on" else "Tap the shield to turn off",
            fontSize = 13.sp, color = Palette.dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        problem?.let { p ->
            Panel(Modifier.padding(top = 14.dp), onClick = { ShieldState.problem.value = null }) {
                Text(p, color = Palette.warn, fontSize = 14.sp)
                Text("Tap to dismiss", color = Palette.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        if (strictDns != null && phase != Phase.OFF) {
            Panel(Modifier.padding(top = 10.dp), onClick = {
                ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                Text("Private DNS is set to $strictDns", color = Palette.warn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Android sends DNS straight to it, past SonderShield, so nothing gets blocked. Set Private DNS to Automatic or Off. Tap to open settings.",
                    color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        val (total, blocked) = today
        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 14.dp)) {
            Stat(blocked.grouped(), "blocked today", Modifier.weight(1f))
            Stat(total.grouped(), "queries today", Modifier.weight(1f))
            Stat(if (total > 0) "%.1f%%".format(blocked * 100.0 / total) else "–", "blocked", Modifier.weight(1f))
        }

        val enabled = prefs.enabledLists.size
        InfoRow("Block lists", "$enabled on · ${if (domains > 0) domains.grouped() + " domains" else "not loaded"}", onClick = { goTo(Tab.Settings) })
        InfoRow("Apps bypassing", prefs.bypassApps.size.toString(), onClick = { goTo(Tab.Apps) })
        InfoRow("Your rules", "${lines(prefs.blockRules)} block · ${lines(prefs.allowRules)} allow", onClick = { goTo(Tab.Rules) })
        InfoRow("Blocked in total", prefs.allTimeBlocked.grouped())
        Spacer(Modifier.height(16.dp))
    }
}

private fun lines(s: String) = s.lines().count { it.isNotBlank() && !it.trimStart().startsWith("#") }

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 5.dp).clip(cardShape).background(Palette.card).border(1.dp, Palette.line, cardShape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(value, style = numberStyle, maxLines = 1)
        Text(label, color = Palette.dim, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ShieldButton(phase: Phase, onClick: () -> Unit) {
    val on = phase != Phase.OFF
    val ring by animateColorAsState(if (on) Palette.accent else Palette.off, label = "ring")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val s by pulse.animateFloat(
        1f, if (phase == Phase.ON) 1.06f else 1f,
        infiniteRepeatable(tween(if (phase == Phase.STARTING) 600 else 1600), RepeatMode.Reverse), label = "s",
    )
    Box(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(200.dp).scale(s).clip(CircleShape)
                .background(Brush.radialGradient(listOf(ring.copy(alpha = 0.22f), ring.copy(alpha = 0.06f), ring.copy(alpha = 0f)))),
        )
        Box(
            Modifier.size(140.dp).clip(CircleShape).background(Palette.card).border(2.dp, ring, CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (on) Icons.shieldCheck else Icons.shield, "Toggle protection", tint = ring, modifier = Modifier.size(64.dp))
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
