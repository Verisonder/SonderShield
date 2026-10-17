package com.verisonder.sondershield.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.core.AppNames
import com.verisonder.sondershield.core.LogEntry
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val log by ShieldState.log.collectAsState()
    val phase by ShieldState.phase.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 blocked, 2 allowed
    var picked by remember { mutableStateOf<LogEntry?>(null) }

    val shown = remember(log, query, filter) {
        log.asSequence()
            .filter { filter == 0 || (filter == 1 && it.verdict == 1) || (filter == 2 && it.verdict != 1) }
            .filter { query.isBlank() || it.domain.contains(query.trim(), ignoreCase = true) || AppNames.label(ctx, it.uid)?.contains(query.trim(), ignoreCase = true) == true }
            .take(1000).toList()
    }
    val blockedCount = remember(log) { log.count { it.verdict == 1 } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            tr("Log", "Journal", "Protokoll"), kicker = if (phase == Phase.OFF) tr("Paused", "En pause", "Pausiert") else tr("Live", "En direct", "Live"),
            sub = tr("${log.size.grouped()} latest queries · ${blockedCount.grouped()} blocked", "${log.size.grouped()} dernières requêtes · ${blockedCount.grouped()} bloquées", "${log.size.grouped()} letzte Anfragen · ${blockedCount.grouped()} blockiert"),
        ) {
            if (log.isNotEmpty()) IconPill(Icons.trash, tr("Clear log", "Vider le journal", "Protokoll leeren")) { ShieldState.clearLog() }
        }
        Field(query, { query = it }, tr("Search domains or apps", "Rechercher un domaine ou une appli", "Domains oder Apps suchen"), Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 10.dp), leading = Icons.search)
        ChipRow(Modifier.padding(top = 6.dp, bottom = 4.dp)) {
            Chip(tr("All", "Tout", "Alle"), filter == 0) { filter = 0 }
            Chip(tr("Blocked", "Bloqués", "Blockiert"), filter == 1) { filter = 1 }
            Chip(tr("Allowed", "Autorisés", "Erlaubt"), filter == 2) { filter = 2 }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (phase == Phase.OFF) tr("Turn protection on to see DNS queries here.", "Activez la protection pour voir les requêtes DNS ici.", "Schalte den Schutz ein, um hier DNS-Anfragen zu sehen.") else tr("Nothing yet. Open an app or a website.", "Rien pour l'instant. Ouvrez une appli ou un site.", "Noch nichts. Öffne eine App oder Website."),
                    color = Palette.faint, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { e -> Box(Modifier.animateItem()) { LogRow(e) { picked = e } } }
            }
        }
    }

    picked?.let { e ->
        val prefs = Prefs(ctx)
        AlertDialog(
            onDismissRequest = { picked = null },
            containerColor = Palette.panel,
            shape = RoundedCornerShape(24.dp),
            title = { Text(e.domain, style = display(18, FontWeight.Bold), color = Palette.text) },
            text = {
                Column {
                    Text(
                        when (e.verdict) {
                            1 -> tr("Blocked by ", "Bloqué par ", "Blockiert durch ") + reasonLabel(ctx, e.reason)
                            2 -> tr("Failed: ", "Échec : ", "Fehler: ") + e.reason
                            else -> tr("Allowed", "Autorisé", "Erlaubt") + if (e.reason.isNotEmpty()) " (${e.reason})" else ""
                        },
                        color = if (e.verdict == 1) Palette.accentText else Palette.dim, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    )
                    Text("${e.typeName} · ${time(e.time)}${if (e.ms > 0) " · ${e.ms} ms" else ""}", color = Palette.faint, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    AppNames.label(ctx, e.uid)?.let { Text(tr("Asked by ", "Demandé par ", "Angefragt von ") + it, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }
                    if (e.verdict == 1 && isListReason(e.reason)) {
                        Text(tr("Allowing it adds an allow rule, which wins over every list.", "L'autoriser ajoute une règle d'autorisation, qui passe avant toutes les listes.", "Erlauben fügt eine Erlauben-Regel hinzu, die vor jeder Liste gilt."), color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            },
            confirmButton = {
                if (e.verdict == 1) {
                    PillButton(tr("Allow", "Autoriser", "Erlauben")) {
                        prefs.allowRules = addLine(prefs.allowRules, e.domain)
                        prefs.blockRules = removeLine(prefs.blockRules, e.domain)
                        ShieldState.applyRules()
                        picked = null
                        Toast.makeText(ctx, trc(ctx, "Allowed ", "Autorisé : ", "Erlaubt: ") + e.domain, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    PillButton(tr("Block", "Bloquer", "Blockieren")) {
                        prefs.blockRules = addLine(prefs.blockRules, e.domain)
                        prefs.allowRules = removeLine(prefs.allowRules, e.domain)
                        ShieldState.applyRules()
                        picked = null
                        Toast.makeText(ctx, trc(ctx, "Blocked ", "Bloqué : ", "Blockiert: ") + e.domain, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            dismissButton = {
                PillButton(tr("Copy", "Copier", "Kopieren"), filled = false) {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("domain", e.domain))
                    picked = null
                }
            },
        )
    }
}

@Composable
private fun LogRow(e: LogEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val blocked = e.verdict == 1
    val appName = remember(e.uid) { AppNames.label(ctx, e.uid) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The bar on the left: accent for blocked, amber for failed, grey otherwise.
            Box(
                Modifier.width(6.dp).height(38.dp).clip(RoundedCornerShape(3.dp))
                    .background(when (e.verdict) { 1 -> Palette.accent; 2 -> Palette.warn; else -> Palette.card2 }),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.domain, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(time(e.time))
                        if (appName != null) { append(" · "); append(appName) } else { append(" · "); append(e.typeName) }
                        if (blocked) { append(" · "); append(reasonLabel(ctx, e.reason)) }
                    },
                    color = Palette.faint, fontSize = 12.sp, maxLines = 1,
                )
            }
            Spacer(Modifier.width(10.dp))
            when {
                blocked -> Text(
                    tr("BLOCKED", "BLOQUÉ", "BLOCKIERT"), color = Palette.onAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.clip(CircleShape).background(Palette.accent).padding(horizontal = 9.dp, vertical = 5.dp),
                )
                else -> Text(
                    when { e.verdict == 2 -> tr("failed", "échec", "Fehler"); e.reason == "cached" -> tr("cached", "en cache", "Cache"); else -> "${e.ms} ms" },
                    color = if (e.verdict == 2) Palette.warn else Palette.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).border(1.5.dp, Palette.line, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.7f)))
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun time(ms: Long): String = synchronized(timeFmt) { timeFmt.format(Date(ms)) }

fun addLine(text: String, line: String): String {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return if (lines.any { it.equals(line, ignoreCase = true) }) lines.joinToString("\n") else (lines + line).joinToString("\n")
}

fun removeLine(text: String, line: String): String =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.equals(line, ignoreCase = true) }.joinToString("\n")
