package com.verisonder.sondershield.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.BuildConfig
import com.verisonder.sondershield.core.AppUpdate
import kotlinx.coroutines.launch

/**
 * Settings › Updates. Already shows a new version when the automatic check found one;
 * "Check now" asks GitHub again; "Update now" downloads the APK and opens the installer.
 */
@Composable
fun UpdateSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by AppUpdate.available.collectAsState()
    val progress by AppUpdate.progress.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Version ", "Version ", "Version ") + BuildConfig.VERSION_NAME, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        info != null -> tr("Version ${info!!.version} is available.", "La version ${info!!.version} est disponible.", "Version ${info!!.version} ist verfügbar.")
                        checking -> tr("Checking…", "Vérification…", "Wird geprüft…")
                        status != null -> status!!
                        else -> tr("Checks by itself and tells you when there's a new version.", "Vérifie tout seul et vous prévient d'une nouvelle version.", "Prüft selbst und sagt Bescheid, wenn es eine neue Version gibt.")
                    },
                    color = if (info != null) Palette.accentText else Palette.dim, fontSize = 13.sp, lineHeight = 18.sp,
                    fontWeight = if (info != null) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (info == null) {
                Spacer(Modifier.width(10.dp))
                PillButton(tr("Check now", "Vérifier", "Prüfen"), filled = false, enabled = !checking) {
                    checking = true
                    scope.launch {
                        status = try {
                            if (AppUpdate.check(ctx) == null) trc(ctx, "You're on the latest version.", "Vous avez la dernière version.", "Du hast die neueste Version.") else null
                        } catch (e: Exception) {
                            trc(ctx, "Couldn't reach GitHub. Try again later.", "Impossible de joindre GitHub. Réessayez plus tard.", "GitHub nicht erreichbar. Versuch es später.")
                        }
                        checking = false
                    }
                }
            }
        }
        AnimatedVisibility(info != null, enter = expandVertically() + fadeIn()) {
            val i = info ?: return@AnimatedVisibility
            Column(Modifier.padding(top = 12.dp)) {
                val notes = i.notes.lines().map { it.trim().removePrefix("#").removePrefix("-").removePrefix("*").trim() }
                    .filter { it.isNotEmpty() }.take(6).joinToString("\n")
                if (notes.isNotEmpty()) {
                    Text(notes, color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                }
                val p = progress
                if (p != null) {
                    val shown by animateFloatAsState(p, tween(250), label = "dl")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Palette.line)) {
                            Box(Modifier.fillMaxWidth(shown.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(Palette.accent))
                        }
                        Text("${(p * 100).toInt()}%", color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                    }
                } else {
                    PillButton(tr("Update now", "Mettre à jour", "Jetzt aktualisieren")) {
                        status = null
                        scope.launch {
                            try {
                                AppUpdate.install(ctx, i)
                            } catch (e: Exception) {
                                status = trc(ctx, "Download failed. Try again.", "Le téléchargement a échoué. Réessayez.", "Download fehlgeschlagen. Versuch es erneut.")
                            }
                        }
                    }
                    status?.let { Text(it, color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
}
