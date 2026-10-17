package com.verisonder.sondershield.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.verisonder.sondershield.data.Backup
import com.verisonder.sondershield.data.CrashLog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.BuildConfig
import com.verisonder.sondershield.core.BlockList
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.core.Upstreams
import com.verisonder.sondershield.data.ListStore
import com.verisonder.sondershield.data.Prefs
import com.verisonder.sondershield.service.ShieldVpnService
import com.verisonder.sondershield.service.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    // An import rewrites every setting: re-read them all by starting the screen over.
    var reload by remember { mutableIntStateOf(0) }
    key(reload) { SettingsContent(onReload = { reload++ }) }
}

@Composable
private fun SettingsContent(onReload: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { ListStore(ctx) }
    val scope = rememberCoroutineScope()
    val phase by ShieldState.phase.collectAsState()
    val listsVersion by ShieldState.listsVersion.collectAsState()
    val themeId by ShieldState.theme.collectAsState()

    var upstream by remember { mutableStateOf(prefs.upstreamId) }
    var custom by remember { mutableStateOf(prefs.customUpstream) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var ipv6 by remember { mutableStateOf(prefs.ipv6) }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var full by remember { mutableStateOf(prefs.fullTunnel) }
    var wgName by remember { mutableStateOf(prefs.wgName) }
    var wgOn by remember { mutableStateOf(prefs.wgOn) }
    var root by remember { mutableStateOf(prefs.rootMode) }
    var httpsOn by remember { mutableStateOf(prefs.httpsOn) }
    var confirmReset by remember { mutableStateOf(false) }

    fun applyUpstream() {
        prefs.upstreamId = upstream
        prefs.customUpstream = custom.trim()
        testResult = null
        if (phase == Phase.ON) scope.launch { ShieldState.applyConfig() }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("Settings", "Réglages", "Einstellungen"))

        // 01 DNS server
        Section(1, tr("DNS server", "Serveur DNS", "DNS-Server"), first = true) {
            for (u in Upstreams.all) {
                val sel = upstream == u.id
                OptionCard(
                    u.name.substringBefore(" ("),
                    upstreamSub(u.name, lang()) +
                        if (sel && testResult != null) " · $testResult" else "",
                    selected = sel,
                    onClick = { upstream = u.id; applyUpstream() },
                )
            }
            OptionCard(tr("Custom", "Personnalisé", "Eigener"), tr("Your own server", "Votre propre serveur", "Dein eigener Server"), selected = upstream == Upstreams.CUSTOM, onClick = { upstream = Upstreams.CUSTOM })
            if (upstream == Upstreams.CUSTOM) {
                Field(custom, { custom = it }, tr("udp 9.9.9.9  or  doh https://host/dns-query 1.2.3.4", "udp 9.9.9.9  ou  doh https://hote/dns-query 1.2.3.4", "udp 9.9.9.9  oder  doh https://host/dns-query 1.2.3.4"), Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri, onDone = ::applyUpstream)
                Text(
                    tr(
                        "DoH needs the server's IP addresses after the URL, so SonderShield can reach it without asking itself.",
                        "Le DoH a besoin des adresses IP du serveur après l'URL, pour que SonderShield le joigne sans se demander à lui-même.",
                        "DoH braucht die IP-Adressen des Servers nach der URL, damit SonderShield ihn erreicht, ohne sich selbst zu fragen.",
                    ),
                    color = Palette.faint, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (upstream == Upstreams.CUSTOM) PillButton(tr("Save", "Enregistrer", "Speichern")) { applyUpstream() }
                PillButton(tr("Test", "Tester", "Testen"), filled = false) {
                    testResult = trc(ctx, "testing…", "test…", "teste…")
                    scope.launch {
                        val spec = if (upstream == Upstreams.CUSTOM) custom.trim() else Upstreams.byId(upstream)!!.spec
                        testResult = withContext(Dispatchers.IO) {
                            try { trc(ctx, "answered in ", "réponse en ", "geantwortet in ") + "${ShieldState.engine.testUpstream(spec)} ms" } catch (e: Exception) { trc(ctx, "failed: ", "échec : ", "Fehler: ") + e.message }
                        }
                    }
                }
                if (upstream == Upstreams.CUSTOM) testResult?.let { Text(it, color = Palette.dim, fontSize = 13.sp) }
            }
        }

        // 02 Connection
        Section(2, tr("Connection", "Connexion", "Verbindung")) {
            ToggleCard(tr("Start on boot", "Démarrer avec le téléphone", "Beim Hochfahren starten"), tr("Turn protection back on after a restart, if it was on.", "Réactive la protection après un redémarrage, si elle était active.", "Schaltet den Schutz nach einem Neustart wieder ein, wenn er an war."), boot) {
                boot = it; prefs.startOnBoot = it
            }
            ToggleCard("DNS IPv6", tr("Also catch DNS sent over IPv6. Turn off only if a network misbehaves.", "Capte aussi le DNS envoyé en IPv6. À désactiver seulement si un réseau pose problème.", "Fängt auch DNS über IPv6 ab. Nur ausschalten, wenn ein Netz Probleme macht."), ipv6) {
                ipv6 = it; prefs.ipv6 = it
                ShieldVpnService.restartIfOn(ctx)
            }
            OptionCard(
                tr("Always-on VPN", "VPN permanent", "Dauerhaftes VPN"),
                tr("Protection that survives everything. Set SonderShield as always-on in VPN settings.", "Une protection qui résiste à tout. Réglez SonderShield en VPN permanent dans les réglages VPN.", "Schutz, der alles übersteht. Stelle SonderShield in den VPN-Einstellungen als dauerhaftes VPN ein."),
                onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
            OptionCard(
                tr("Battery", "Batterie", "Akku"),
                tr("On HyperOS set Battery saver to No restrictions, or the system may stop SonderShield.", "Sur HyperOS, réglez l'économiseur de batterie sur Aucune restriction, sinon le système peut arrêter SonderShield.", "Unter HyperOS den Energiesparmodus auf Keine Einschränkungen stellen, sonst kann das System SonderShield beenden."),
                onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
        }

        // 03 Tunnel
        Section(3, tr("Tunnel", "Tunnel", "Tunnel")) {
            val importWg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                val text = runCatching { readSmallText(ctx, uri) }.getOrNull()
                val problem = if (text == null) trc(ctx, "Could not read the file", "Impossible de lire le fichier", "Datei konnte nicht gelesen werden") else shield.Shield.checkWireGuard(text)
                if (!problem.isNullOrEmpty()) {
                    toast(ctx, trc(ctx, "Not a usable WireGuard config: ", "Configuration WireGuard inutilisable : ", "Keine brauchbare WireGuard-Konfiguration: ") + problem)
                } else {
                    prefs.wgConfig = text!!
                    prefs.wgName = displayName(ctx, uri)?.removeSuffix(".conf") ?: "WireGuard"
                    prefs.wgOn = true
                    wgName = prefs.wgName; wgOn = true
                    ShieldVpnService.restartIfOn(ctx)
                }
            }
            ToggleCard(
                tr("Root mode", "Mode root", "Root-Modus"),
                tr(
                    "Rooted phones only: filters DNS without a VPN, so another VPN can run alongside. Turn off Private DNS in Android settings.",
                    "Téléphones rootés uniquement : filtre le DNS sans VPN, un autre VPN peut donc tourner à côté. Désactivez le DNS privé dans les réglages Android.",
                    "Nur für gerootete Handys: filtert DNS ohne VPN, sodass ein anderes VPN parallel laufen kann. Privates DNS in den Android-Einstellungen ausschalten.",
                ),
                root,
            ) { on ->
                if (!on) {
                    root = false; prefs.rootMode = false
                    ShieldVpnService.restartIfOn(ctx)
                } else {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { com.verisonder.sondershield.core.Root.available() }
                        if (ok) {
                            root = true; prefs.rootMode = true
                            ShieldVpnService.restartIfOn(ctx)
                        } else {
                            toast(ctx, trc(ctx, "No root access. Is the phone rooted, and was SonderShield allowed?", "Pas d'accès root. Le téléphone est-il rooté, et SonderShield autorisé ?", "Kein Root-Zugriff. Ist das Handy gerootet und wurde SonderShield erlaubt?"))
                        }
                    }
                }
            }
            AnimatedVisibility(!root, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    ToggleCard(
                        tr("Full tunnel", "Tunnel complet", "Voller Tunnel"),
                        tr(
                            "Send all traffic through SonderShield, not only DNS. Needed for WireGuard and HTTPS filtering. Uses a little more battery.",
                            "Fait passer tout le trafic par SonderShield, pas seulement le DNS. Nécessaire pour WireGuard et le filtrage HTTPS. Consomme un peu plus de batterie.",
                            "Leitet den ganzen Datenverkehr durch SonderShield, nicht nur DNS. Nötig für WireGuard und HTTPS-Filterung. Braucht etwas mehr Akku.",
                        ),
                        full,
                    ) {
                        full = it; prefs.fullTunnel = it
                        if (!it) { httpsOn = false; prefs.httpsOn = false }
                        ShieldVpnService.restartIfOn(ctx)
                    }
                    AnimatedVisibility(full, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column {
                            if (wgName.isEmpty()) {
                                OptionCard(
                                    tr("Import WireGuard config", "Importer une config WireGuard", "WireGuard-Konfiguration importieren"),
                                    tr(
                                        "A .conf file from your VPN provider or your own server. Traffic then leaves through it, still filtered.",
                                        "Un fichier .conf de votre fournisseur VPN ou de votre serveur. Le trafic sort alors par lui, toujours filtré.",
                                        "Eine .conf-Datei von deinem VPN-Anbieter oder eigenen Server. Der Verkehr geht dann darüber hinaus, weiter gefiltert.",
                                    ),
                                    onClick = { importWg.launch(arrayOf("*/*")) },
                                ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
                            } else {
                                ToggleCard(
                                    "WireGuard · $wgName",
                                    if (wgOn) tr("Traffic goes out through this server.", "Le trafic sort par ce serveur.", "Der Verkehr geht über diesen Server hinaus.")
                                    else tr("Off: traffic goes out directly.", "Désactivé : le trafic sort directement.", "Aus: Der Verkehr geht direkt hinaus."),
                                    wgOn,
                                ) {
                                    wgOn = it; prefs.wgOn = it
                                    ShieldVpnService.restartIfOn(ctx)
                                }
                                OptionCard(tr("Remove WireGuard config", "Supprimer la config WireGuard", "WireGuard-Konfiguration entfernen"), wgName, onClick = {
                                    prefs.wgConfig = ""; prefs.wgName = ""; prefs.wgOn = false
                                    wgName = ""; wgOn = false
                                    ShieldVpnService.restartIfOn(ctx)
                                }) { Icon(Icons.trash, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
            }
        }

        // 04 HTTPS filtering
        Section(4, tr("HTTPS filtering", "Filtrage HTTPS", "HTTPS-Filterung")) {
            HttpsSection(
                prefs, root, httpsOn,
                onSwitch = { on ->
                    if (!on) {
                        httpsOn = false; prefs.httpsOn = false
                        ShieldVpnService.restartIfOn(ctx)
                    } else {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (prefs.caPem.isEmpty()) prefs.caPem = shield.Shield.generateCA()
                                if (prefs.httpsApps == null) prefs.httpsApps = com.verisonder.sondershield.core.Https.browsers(ctx).map { it.pkg }.toSet()
                            }
                            prefs.httpsOn = true; httpsOn = true
                            prefs.fullTunnel = true; full = true
                            // The page rules live in EasyList and uBlock's list: switch them on too
                            // (they download when the tunnel restarts).
                            val add = com.verisonder.sondershield.core.Catalog.pageListIds - prefs.enabledLists
                            if (add.isNotEmpty()) {
                                prefs.enabledLists = prefs.enabledLists + add
                                ShieldState.listsVersion.value++
                            }
                            ShieldVpnService.restartIfOn(ctx)
                        }
                    }
                },
            )
        }

        // 05 Theme
        Section(5, tr("Theme", "Thème", "Design")) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in ThemeMode.entries) {
                    if (m == ThemeMode.You && !ThemeMode.youAvailable) continue
                    Chip(when (lang()) { "fr" -> m.fr; "de" -> m.de; else -> m.label }, ThemeMode.of(themeId) == m) {
                        prefs.theme = m.id
                        ShieldState.theme.value = m.id
                    }
                }
            }
            if (ThemeMode.of(themeId) == ThemeMode.You) {
                Text(tr("Colours from your wallpaper, light or dark with the system.", "Couleurs de votre fond d'écran, clair ou sombre selon le système.", "Farben deines Hintergrundbilds, hell oder dunkel wie das System."), color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                AccentPicker(prefs)
            }
        }

        // 06 Backup and logs
        Section(6, tr("Backup and logs", "Sauvegarde et journaux", "Sicherung und Protokolle")) {
            val exportSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) toast(ctx, runCatching { Backup.export(ctx, uri) }.exceptionOrNull()?.message
                    ?: trc(ctx, "Settings saved", "Paramètres enregistrés", "Einstellungen gespeichert"))
            }
            val importSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                val err = runCatching { Backup.import(ctx, uri) }.exceptionOrNull()
                if (err != null) {
                    toast(ctx, if (err is IllegalArgumentException) trc(ctx, "Not a SonderShield backup", "Ce n'est pas une sauvegarde SonderShield", "Keine SonderShield-Sicherung") else err.message ?: trc(ctx, "Import failed", "Échec de l'import", "Import fehlgeschlagen"))
                } else {
                    ShieldState.theme.value = prefs.theme
                    ShieldState.accent.value = prefs.accent
                    UpdateWorker.schedule(ctx)
                    ShieldVpnService.restartIfOn(ctx)
                    toast(ctx, trc(ctx, "Settings restored", "Paramètres restaurés", "Einstellungen wiederhergestellt"))
                    onReload()
                }
            }
            val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                if (uri != null) toast(ctx, runCatching { Backup.exportLog(ctx, uri, ShieldState.log.value) }.exceptionOrNull()?.message
                    ?: trc(ctx, "Log saved", "Journal enregistré", "Protokoll gespeichert"))
            }
            OptionCard(
                tr("Export settings", "Exporter les paramètres", "Einstellungen exportieren"),
                tr("Lists, rules, apps, DNS server, theme. Not the stats or the log.", "Listes, règles, applis, serveur DNS, thème. Pas les statistiques ni le journal.", "Listen, Regeln, Apps, DNS-Server, Design. Nicht die Statistik oder das Protokoll."),
                onClick = { exportSettings.launch("sondershield-settings-${Backup.stamp()}.json") },
            ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
            OptionCard(
                tr("Import settings", "Importer des paramètres", "Einstellungen importieren"),
                tr("From a file exported here. Replaces what you have now.", "Depuis un fichier exporté ici. Remplace vos réglages actuels.", "Aus einer hier exportierten Datei. Ersetzt deine aktuellen Einstellungen."),
                onClick = { importSettings.launch(arrayOf("application/json", "text/plain", "*/*")) },
            ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
            OptionCard(
                tr("Export the log", "Exporter le journal", "Protokoll exportieren"),
                tr("The latest ${ShieldState.log.value.size} queries as a CSV file.", "Les ${ShieldState.log.value.size} dernières requêtes en fichier CSV.", "Die letzten ${ShieldState.log.value.size} Anfragen als CSV-Datei."),
                onClick = { exportLog.launch("sondershield-log-${Backup.stamp()}.csv") },
            ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
            var crash by remember { mutableStateOf(CrashLog.read(ctx)) }
            crash?.let { c ->
                OptionCard(
                    tr("Last crash", "Dernier plantage", "Letzter Absturz"),
                    c.lineSequence().take(2).joinToString(" · "),
                    subColor = Palette.warn,
                    onClick = {
                        ctx.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c), null)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                ) {
                    IconPill(Icons.trash, tr("Clear", "Effacer", "Löschen")) { CrashLog.clear(ctx); crash = null }
                }
                Text(
                    tr("Kept on this phone only. Tap to share it.", "Gardé sur ce téléphone uniquement. Touchez pour le partager.", "Nur auf diesem Handy gespeichert. Tippen zum Teilen."),
                    color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        // 07 Statistics
        Section(7, tr("Statistics", "Statistiques", "Statistik")) {
            OptionCard(tr("Blocked in total", "Bloqués au total", "Insgesamt blockiert"), ShieldState.stats.collectAsState().value.allBlocked.grouped())
            OptionCard(tr("Reset counters", "Remettre à zéro", "Zähler zurücksetzen"), tr("Today and all-time go back to zero. The log stays.", "Aujourd'hui et le total repartent de zéro. Le journal reste.", "Heute und Insgesamt fangen wieder bei null an. Das Protokoll bleibt."), onClick = { confirmReset = true }) {
                Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp))
            }
        }

        // 08 About
        Section(8, tr("About", "À propos", "Über")) {
            Block {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(30.dp, lit = true)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Wordmark(17)
                        Text(tr("Version ", "Version ", "Version ") + BuildConfig.VERSION_NAME, color = Palette.faint, fontSize = 12.sp)
                    }
                }
                Text(
                    tr(
                        "By Verisonder. Free and open source under GPL-3.0. Everything stays on this phone: no account, no analytics.",
                        "Par Verisonder. Libre et open source sous GPL-3.0. Tout reste sur ce téléphone : pas de compte, pas de statistiques envoyées.",
                        "Von Verisonder. Frei und quelloffen unter GPL-3.0. Alles bleibt auf diesem Handy: kein Konto, keine Statistik wird gesendet.",
                    ),
                    color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    tr(
                        "Block lists by StevenBlack, AdGuard, abuse.ch URLhaus, HaGeZi, OISD and EasyList, each under its own licence. Fonts: Unbounded and DM Sans (SIL Open Font License).",
                        "Listes par StevenBlack, AdGuard, abuse.ch URLhaus, HaGeZi, OISD et EasyList, chacune sous sa propre licence. Polices : Unbounded et DM Sans (SIL Open Font License).",
                        "Blocklisten von StevenBlack, AdGuard, abuse.ch URLhaus, HaGeZi, OISD und EasyList, jeweils unter eigener Lizenz. Schriften: Unbounded und DM Sans (SIL Open Font License).",
                    ),
                    color = Palette.faint, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
                PillButton(tr("Source code", "Code source", "Quellcode"), filled = false) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Verisonder/SonderShield")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Palette.panel,
            shape = RoundedCornerShape(24.dp),
            title = { Text(tr("Reset counters?", "Remettre à zéro ?", "Zähler zurücksetzen?"), style = display(20), color = Palette.text) },
            text = { Text(tr("Today's and all-time numbers go back to zero. The log stays.", "Les chiffres d'aujourd'hui et le total repartent de zéro. Le journal reste.", "Die Zahlen von heute und insgesamt fangen wieder bei null an. Das Protokoll bleibt."), color = Palette.dim) },
            confirmButton = { PillButton(tr("Reset", "Remettre à zéro", "Zurücksetzen")) { ShieldState.resetStats(); confirmReset = false } },
            dismissButton = { PillButton(tr("Cancel", "Annuler", "Abbrechen"), filled = false) { confirmReset = false } },
        )
    }
}

/**
 * Accent colour: a row of swatches, the last one a rainbow "custom" circle that opens a hue
 * bar for any other colour.
 */
@Composable
private fun AccentPicker(prefs: Prefs) {
    val current by ShieldState.accent.collectAsState()
    val isPreset = AccentChoices.any { it.second.toArgb() == current }
    var showCustom by remember { mutableStateOf(false) }
    var hue by remember { mutableFloatStateOf(hueOf(Color(current))) }
    fun pick(c: Color) {
        val argb = c.toArgb()
        prefs.accent = argb
        ShieldState.accent.value = argb
    }
    Text(tr("ACCENT", "COULEUR", "AKZENT"), color = Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((name, c) in AccentChoices) {
            Swatch(name, fill = Brush.linearGradient(listOf(c, c)), selected = c.toArgb() == current) {
                showCustom = false
                pick(c)
            }
        }
        // Custom: rainbow until a custom colour is in use, then that colour inside a rainbow ring.
        val rainbow = Brush.sweepGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.85f, 1f) })
        Swatch(
            tr("Custom colour", "Couleur perso", "Eigene Farbe"), fill = if (isPreset) rainbow else Brush.linearGradient(listOf(Color(current), Color(current))),
            selected = !isPreset || showCustom, ring = if (isPreset) null else rainbow,
        ) {
            showCustom = !showCustom
            if (showCustom && isPreset) { hue = hueOf(Color(current)); pick(Color.hsv(hue, 0.85f, 1f)) }
        }
    }
    AnimatedVisibility(
        visible = showCustom,
        enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) + fadeIn(),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
    ) {
        Column(Modifier.padding(top = 16.dp)) {
            Text(tr("Drag to pick any colour", "Glissez pour choisir une couleur", "Ziehen, um eine beliebige Farbe zu wählen"), color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            HueBar(hue) { h -> hue = h; pick(Color.hsv(h, 0.85f, 1f)) }
        }
    }
}

/** A colour circle drawn on a canvas so its edge and selection ring stay smooth. */
@Composable
private fun Swatch(name: String, fill: Brush, selected: Boolean, ring: Brush? = null, onClick: () -> Unit) {
    val ringWidth by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "ring")
    val ringColor = Palette.text
    Canvas(
        Modifier.size(44.dp).pressScale().clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = name; this.selected = selected },
    ) {
        val r = size.minDimension / 2
        val stroke = 2.5.dp.toPx()
        val gap = 3.dp.toPx()
        // Unselected: the colour fills the whole circle. Selected: it shrinks to leave a gap
        // and a ring (white, or rainbow for a custom colour) appears around it.
        val inner = r - (stroke + gap) * ringWidth
        if (ringWidth > 0f) {
            if (ring != null) drawCircle(ring, radius = r - stroke / 2, style = Stroke(stroke))
            else drawCircle(ringColor, radius = r - stroke / 2, style = Stroke(stroke), alpha = ringWidth)
        }
        drawCircle(fill, radius = inner)
    }
}

/** Rainbow track with a round thumb; tap or drag anywhere on it. */
@Composable
private fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    val thumbBorder = Palette.text
    val desc = tr("Custom colour", "Couleur perso", "Eigene Farbe")
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(Unit) { detectTapGestures { onHue((it.x / size.width).coerceIn(0f, 1f) * 359f) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onHue((change.position.x / size.width).coerceIn(0f, 1f) * 359f)
                }
            }
            .semantics { contentDescription = desc },
    ) {
        val thumbR = size.height / 2 - 2.dp.toPx()
        val left = thumbR
        val right = size.width - thumbR
        val trackH = 12.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.85f, 1f) }, startX = left, endX = right),
            topLeft = Offset(left, (size.height - trackH) / 2), size = Size(right - left, trackH),
            cornerRadius = CornerRadius(trackH / 2),
        )
        val x = left + (right - left) * (hue / 359f)
        val c = Offset(x, size.height / 2)
        drawCircle(Color.Black.copy(alpha = 0.25f), radius = thumbR + 1.dp.toPx(), center = c)
        drawCircle(thumbBorder, radius = thumbR, center = c)
        drawCircle(Color.hsv(hue, 0.85f, 1f), radius = thumbR - 3.dp.toPx(), center = c)
    }
}

private fun hueOf(c: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return hsv[0]
}

/** A config file is a few hundred bytes; refuse anything that isn't. */
private fun readSmallText(ctx: android.content.Context, uri: Uri): String? =
    ctx.contentResolver.openInputStream(uri)?.use { s ->
        val b = s.readNBytesCompat(64 * 1024 + 1)
        if (b.size > 64 * 1024) null else b.decodeToString()
    }

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private fun displayName(ctx: android.content.Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

private fun toast(ctx: android.content.Context, text: String) =
    android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()

/** "Cloudflare (encrypted)" -> "Encrypted" / "Chiffré". */
private fun upstreamSub(name: String, lang: String): String {
    val raw = name.substringAfter("(", "").removeSuffix(")")
    val t = when (lang) {
        "fr" -> raw.replace("encrypted", "chiffré").replace("plain", "non chiffré").replace("blocks malware", "bloque les malwares")
            .replace("unfiltered", "non filtré")
        "de" -> raw.replace("encrypted", "verschlüsselt").replace("plain", "unverschlüsselt").replace("blocks malware", "blockiert Malware")
            .replace("unfiltered", "ungefiltert")
        else -> raw
    }
    return t.replaceFirstChar { it.uppercase() }
}


