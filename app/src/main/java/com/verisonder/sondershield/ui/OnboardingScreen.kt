package com.verisonder.sondershield.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import kotlinx.coroutines.delay

/**
 * First launch: four short steps. Welcome, allow the VPN (which turns protection on), keep
 * it alive on HyperOS, and a Private DNS check. Every step can be skipped.
 */
@Composable
fun OnboardingScreen(onAllowVpn: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    val last = 3
    val phase by ShieldState.phase.collectAsState()

    Column(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        // Progress: four bars, the done ones in the accent.
        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..last) {
                val c by animateColorAsState(if (i <= step) Palette.accent else Palette.panel, tween(300), label = "bar")
                val h by animateDpAsState(if (i == step) 6.dp else 4.dp, label = "barH")
                Box(Modifier.weight(1f).height(h).clip(CircleShape).background(c))
            }
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it / 4 * dir } + fadeIn(tween(280)))
                    .togetherWith(slideOutHorizontally(tween(260)) { -it / 6 * dir } + fadeOut(tween(180)))
            },
            modifier = Modifier.weight(1f),
            label = "onboarding",
        ) { s ->
            Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
                when (s) {
                    0 -> Page(
                        kicker = tr("Welcome", "Bienvenue", "Willkommen"),
                        title = tr("Ads stop here.", "Les pubs s'arrêtent ici.", "Werbung endet hier."),
                        text = tr(
                            "SonderShield blocks ads, trackers and malware in every app on this phone. No root, no account, and nothing leaves your phone.",
                            "SonderShield bloque les pubs, les traqueurs et les malwares dans toutes les applis de ce téléphone. Sans root, sans compte, et rien ne quitte votre téléphone.",
                            "SonderShield blockiert Werbung, Tracker und Malware in jeder App auf diesem Handy. Ohne Root, ohne Konto, und nichts verlässt dein Handy.",
                        ),
                        lit = true,
                    )
                    1 -> {
                        val on = phase != Phase.OFF
                        Page(
                            kicker = tr("Step 1 · VPN", "Étape 1 · VPN", "Schritt 1 · VPN"),
                            title = tr("Allow the VPN.", "Autorisez le VPN.", "VPN erlauben."),
                            text = tr(
                                "Android asks before an app can filter traffic. SonderShield's VPN only carries DNS and stays on your phone: your browsing doesn't go through any server.",
                                "Android demande avant qu'une appli filtre le trafic. Le VPN de SonderShield ne transporte que le DNS et reste sur votre téléphone : votre navigation ne passe par aucun serveur.",
                                "Android fragt, bevor eine App Datenverkehr filtern darf. Das VPN von SonderShield trägt nur DNS und bleibt auf deinem Handy: Dein Surfen läuft über keinen Server.",
                            ),
                            lit = on,
                        ) {
                            if (on) Done(tr("Protection is on", "Protection activée", "Schutz ist an"))
                            else PillButton(tr("Allow and turn on", "Autoriser et activer", "Erlauben und einschalten"), onClick = onAllowVpn)
                        }
                        // Move on by itself once the VPN came up.
                        LaunchedEffect(on) { if (on && step == 1) { delay(900); step = 2 } }
                    }
                    2 -> Page(
                        kicker = tr("Step 2 · Battery", "Étape 2 · Batterie", "Schritt 2 · Akku"),
                        title = tr("Keep it running.", "Gardez-le actif.", "Laufen lassen."),
                        text = tr(
                            "HyperOS and other Android versions stop apps in the background to save battery. Set Battery saver to No restrictions for SonderShield, and turn on Autostart if your phone has it.",
                            "HyperOS et d'autres versions d'Android arrêtent les applis en arrière-plan pour économiser la batterie. Réglez l'économiseur de batterie sur Aucune restriction pour SonderShield, et activez le démarrage automatique si votre téléphone le propose.",
                            "HyperOS und andere Android-Versionen beenden Apps im Hintergrund, um Akku zu sparen. Stelle den Energiesparmodus für SonderShield auf Keine Einschränkungen und schalte Autostart ein, falls dein Handy es hat.",
                        ),
                        lit = true,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(tr("Battery settings", "Batterie", "Akku-Einstellungen")) { openAppDetails(ctx) }
                            if (hasAutostart(ctx)) PillButton(tr("Autostart", "Démarrage auto", "Autostart"), filled = false) { openAutostart(ctx) }
                        }
                    }
                    else -> {
                        var host by remember { mutableStateOf(privateDnsHostname(ctx)) }
                        LaunchedEffect(Unit) { while (true) { delay(2000); host = privateDnsHostname(ctx) } }
                        Page(
                            kicker = tr("Step 3 · Private DNS", "Étape 3 · DNS privé", "Schritt 3 · Privates DNS"),
                            title = if (host == null) tr("All set.", "C'est prêt.", "Alles bereit.") else tr("One last thing.", "Une dernière chose.", "Eine letzte Sache."),
                            text = if (host == null) tr(
                                "Private DNS isn't forcing a provider, so SonderShield sees every lookup. You can change lists, rules and colours any time in Settings.",
                                "Le DNS privé n'impose pas de fournisseur, donc SonderShield voit chaque requête. Vous pouvez changer les listes, les règles et les couleurs à tout moment dans Paramètres.",
                                "Privates DNS erzwingt keinen Anbieter, also sieht SonderShield jede Anfrage. Listen, Regeln und Farben kannst du jederzeit in den Einstellungen ändern.",
                            ) else tr(
                                "Private DNS is set to $host. Android sends DNS straight there and nothing gets blocked. Set it to Automatic or Off.",
                                "Le DNS privé est réglé sur $host. Android envoie le DNS directement là-bas et rien n'est bloqué. Réglez-le sur Automatique ou Désactivé.",
                                "Privates DNS ist auf $host gestellt. Android schickt DNS direkt dorthin und nichts wird blockiert. Stelle es auf Automatisch oder Aus.",
                            ),
                            lit = host == null,
                        ) {
                            if (host != null) PillButton(tr("Open settings", "Ouvrir les réglages", "Einstellungen öffnen")) {
                                ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } else Done(tr("Nothing bypasses SonderShield", "Rien ne contourne SonderShield", "Nichts umgeht SonderShield"))
                        }
                    }
                }
            }
        }
        // Bottom: back / skip on the left, next / finish on the right.
        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) PillButton(tr("Back", "Retour", "Zurück"), filled = false) { step-- }
            else PillButton(tr("Skip", "Passer", "Überspringen"), filled = false, onClick = onDone)
            Spacer(Modifier.weight(1f))
            PillButton(if (step == last) tr("Start", "Commencer", "Starten") else tr("Next", "Suivant", "Weiter")) {
                if (step == last) onDone() else step++
            }
        }
    }
}

@Composable
private fun Page(kicker: String, title: String, text: String, lit: Boolean, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Logo(120.dp, lit = lit)
        }
        Kicker(kicker)
        Text(title, style = display(32), color = Palette.text, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
        Text(text, color = Palette.dim, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(20.dp))
        action()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Done(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.accent))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Palette.accentText, fontSize = 15.sp)
    }
}

private fun openAppDetails(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Xiaomi's (HyperOS / MIUI) autostart page. */
private val autostart = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")

private fun hasAutostart(ctx: Context): Boolean =
    ctx.packageManager.resolveActivity(Intent().setComponent(autostart), 0) != null

private fun openAutostart(ctx: Context) {
    try {
        ctx.startActivity(Intent().setComponent(autostart).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        openAppDetails(ctx)
    }
}

/** The Private DNS host when it is in strict mode, which bypasses the tunnel. */
fun privateDnsHostname(ctx: Context): String? {
    if (Build.VERSION.SDK_INT < 28) return null
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
    @Suppress("DEPRECATION")
    for (n in cm.allNetworks) {
        val host = cm.getLinkProperties(n)?.privateDnsServerName
        if (!host.isNullOrEmpty()) return host
    }
    return null
}
