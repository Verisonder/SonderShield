package com.verisonder.sondershield.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.security.KeyChain
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.core.Https
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.Prefs
import com.verisonder.sondershield.service.ShieldVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * HTTPS filtering: the switch, the certificate (save, install, checked each time the app
 * comes back from Settings), the apps it applies to, and your never-filter sites.
 */
@Composable
fun HttpsSection(prefs: Prefs, root: Boolean, on: Boolean, onSwitch: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    if (root) {
        Text(
            tr(
                "Not available in root mode: HTTPS filtering needs the full tunnel.",
                "Indisponible en mode root : le filtrage HTTPS a besoin du tunnel complet.",
                "Im Root-Modus nicht verfügbar: HTTPS-Filterung braucht den vollen Tunnel.",
            ),
            color = Palette.faint, fontSize = 13.sp,
        )
        return
    }
    ToggleCard(
        tr("Filter inside pages", "Filtrer dans les pages", "In Seiten filtern"),
        tr(
            "Hides ad spaces and anti-adblock walls in the browsers you pick. Banking, payment and government sites are never touched.",
            "Masque les emplacements de pub et les murs anti-bloqueur dans les navigateurs choisis. Les sites de banque, de paiement et de l'administration ne sont jamais touchés.",
            "Blendet Werbeflächen und Adblock-Sperren in den gewählten Browsern aus. Banken, Zahlungsdienste und Behörden werden nie angefasst.",
        ),
        on, onSwitch,
    )
    AnimatedVisibility(on, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column {
            val resumed by ShieldState.resumed.collectAsState()
            var installed by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(resumed, on) {
                if (on) {
                    // The CA may be generated right after the switch; give it a moment.
                    repeat(10) {
                        if (prefs.caPem.isNotEmpty()) return@repeat
                        delay(200)
                    }
                    installed = withContext(Dispatchers.IO) { prefs.caPem.isNotEmpty() && Https.installed(prefs.caPem) }
                }
            }
            val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                val ok = runCatching {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(shield.Shield.certOf(prefs.caPem).toByteArray()) }
                }.isSuccess
                Toast.makeText(
                    ctx,
                    if (ok) trc(ctx, "Certificate saved. Now install it.", "Certificat enregistré. Installez-le maintenant.", "Zertifikat gespeichert. Jetzt installieren.")
                    else trc(ctx, "Could not save the certificate", "Impossible d'enregistrer le certificat", "Zertifikat konnte nicht gespeichert werden"),
                    Toast.LENGTH_SHORT,
                ).show()
            }

            when (installed) {
                true -> OptionCard(
                    tr("Certificate installed", "Certificat installé", "Zertifikat installiert"),
                    tr(
                        "Android trusts SonderShield's certificate. It was made on this phone and never leaves it.",
                        "Android fait confiance au certificat de SonderShield. Il a été créé sur ce téléphone et ne le quitte jamais.",
                        "Android vertraut dem Zertifikat von SonderShield. Es wurde auf diesem Handy erstellt und verlässt es nie.",
                    ),
                ) { Icon(Icons.check, null, tint = Palette.accentText, modifier = Modifier.size(22.dp)) }
                false -> {
                    Text(
                        tr(
                            "Browsers only accept filtered pages once SonderShield's certificate is installed. Until then those sites pass through unfiltered.",
                            "Les navigateurs n'acceptent les pages filtrées qu'une fois le certificat de SonderShield installé. D'ici là, ces sites passent sans filtrage.",
                            "Browser akzeptieren gefilterte Seiten erst, wenn das Zertifikat von SonderShield installiert ist. Bis dahin laufen die Seiten ungefiltert durch.",
                        ),
                        color = Palette.warn, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp),
                    )
                    if (Build.VERSION.SDK_INT < 30) {
                        OptionCard(
                            tr("Install the certificate", "Installer le certificat", "Zertifikat installieren"),
                            tr("Android asks for a name and your screen lock.", "Android demande un nom et votre verrouillage d'écran.", "Android fragt nach einem Namen und deiner Displaysperre."),
                            onClick = {
                                val der = Https.certDer(prefs.caPem) ?: return@OptionCard
                                ctx.startActivity(
                                    KeyChain.createInstallIntent().putExtra(KeyChain.EXTRA_CERTIFICATE, der).putExtra(KeyChain.EXTRA_NAME, "SonderShield")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                        ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
                    } else {
                        OptionCard(
                            tr("1. Save the certificate", "1. Enregistrer le certificat", "1. Zertifikat speichern"),
                            tr("Pick a folder, Downloads is fine.", "Choisissez un dossier, Téléchargements convient.", "Ordner wählen, Downloads passt."),
                            onClick = { save.launch("SonderShield-CA.crt") },
                        ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
                        OptionCard(
                            tr("2. Install it", "2. L'installer", "2. Installieren"),
                            tr(
                                "In Settings search \"CA certificate\" (or Security › Encryption & credentials › Install a certificate › CA certificate), then pick the saved file.",
                                "Dans les réglages, cherchez « certificat CA » (ou Sécurité › Chiffrement et identifiants › Installer un certificat › Certificat CA), puis choisissez le fichier.",
                                "In den Einstellungen nach „CA-Zertifikat“ suchen (oder Sicherheit › Verschlüsselung & Anmeldedaten › Zertifikat installieren › CA-Zertifikat) und die Datei wählen.",
                            ),
                            onClick = {
                                runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    .onFailure { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            },
                        ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
                    }
                }
                null -> Unit
            }

            // Apps
            val browsers = remember { Https.browsers(ctx) }
            var picked by remember { mutableStateOf(prefs.httpsApps.orEmpty()) }
            Text(
                tr("FILTERED APPS", "APPLIS FILTRÉES", "GEFILTERTE APPS"), color = Palette.accentText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            if (browsers.isEmpty()) {
                Text(tr("No browser found on this phone.", "Aucun navigateur trouvé sur ce téléphone.", "Kein Browser auf diesem Handy gefunden."), color = Palette.faint, fontSize = 13.sp)
            }
            for (b in browsers) {
                val sel = b.pkg in picked
                val sub = if (Https.isFirefox(b.pkg)) tr(
                    "Firefox: Settings › About Firefox, tap the logo 5 times, then Settings › Secret settings › Use third party CA certificates.",
                    "Firefox : Paramètres › À propos de Firefox, touchez le logo 5 fois, puis Paramètres › Paramètres secrets › Utiliser les certificats CA tiers.",
                    "Firefox: Einstellungen › Über Firefox, 5-mal aufs Logo tippen, dann Einstellungen › Geheime Einstellungen › CA-Zertifikate von Drittanbietern verwenden.",
                ) else b.pkg
                ToggleCard(b.label, sub, sel) {
                    picked = if (it) picked + b.pkg else picked - b.pkg
                    prefs.httpsApps = picked
                    ShieldVpnService.restartIfOn(ctx)
                }
            }

            // Your never-filter sites
            var edit by remember { mutableStateOf(false) }
            var skip by remember { mutableStateOf(prefs.httpsSkip) }
            val count = skip.lines().count { it.isNotBlank() }
            Spacer(Modifier.height(4.dp))
            OptionCard(
                tr("Never filter", "Ne jamais filtrer", "Nie filtern"),
                tr(
                    "$count of your sites, plus banks, payments and government built in. Sites whose app refuses the certificate are skipped by themselves.",
                    "$count de vos sites, plus banques, paiements et administrations intégrés. Les sites dont l'appli refuse le certificat sont ignorés d'eux-mêmes.",
                    "$count eigene Seiten, dazu eingebaut Banken, Zahlungsdienste und Behörden. Seiten, deren App das Zertifikat ablehnt, werden von selbst übersprungen.",
                ),
                onClick = { edit = true },
            ) { Icon(Icons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp)) }
            if (edit) {
                var text by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { edit = false },
                    containerColor = Palette.panel,
                    shape = RoundedCornerShape(24.dp),
                    title = { Text(tr("Never filter", "Ne jamais filtrer", "Nie filtern"), style = display(20), color = Palette.text) },
                    text = {
                        Column {
                            for (d in skip.lines().filter { it.isNotBlank() }) {
                                OptionCard(d) {
                                    IconPill(Icons.trash, tr("Remove", "Retirer", "Entfernen")) {
                                        skip = removeLine(skip, d)
                                    }
                                }
                            }
                            Field(text, { text = it.trim() }, tr("example.com", "exemple.fr", "beispiel.de"), Modifier.fillMaxWidth(), keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
                        }
                    },
                    confirmButton = {
                        PillButton(tr("Save", "Enregistrer", "Speichern")) {
                            if (text.isNotBlank()) skip = addLine(skip, text.lowercase().removePrefix("https://").substringBefore('/'))
                            prefs.httpsSkip = skip
                            edit = false
                            ShieldVpnService.restartIfOn(ctx)
                        }
                    },
                    dismissButton = { PillButton(tr("Cancel", "Annuler", "Abbrechen"), filled = false) { edit = false } },
                )
            }
        }
    }
}
