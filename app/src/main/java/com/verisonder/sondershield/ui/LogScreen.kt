package com.verisonder.sondershield.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var filter by rememberSaveable { mutableStateOf(0) } // 0 all, 1 blocked, 2 allowed
    var picked by remember { mutableStateOf<LogEntry?>(null) }

    val shown = remember(log, query, filter) {
        log.asSequence()
            .filter { filter == 0 || (filter == 1 && it.verdict == 1) || (filter == 2 && it.verdict != 1) }
            .filter { query.isBlank() || it.domain.contains(query.trim(), ignoreCase = true) }
            .take(1000).toList()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Log") {
            IconButton(onClick = { ShieldState.clearLog() }) { Icon(Icons.trash, "Clear log", tint = Palette.dim) }
        }
        Field(query, { query = it }, "Search domains", Modifier.fillMaxWidth().padding(horizontal = 16.dp), leading = Icons.search)
        ChipRow(Modifier.padding(top = 6.dp)) {
            Chip("All", filter == 0) { filter = 0 }
            Chip("Blocked", filter == 1) { filter = 1 }
            Chip("Allowed", filter == 2) { filter = 2 }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (phase == Phase.OFF) "Turn protection on to see DNS queries here." else "Nothing yet. Open an app or a website.",
                    color = Palette.dim, fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { e ->
                    LogRow(e) { picked = e }
                    HorizontalDivider(color = Palette.line.copy(alpha = 0.5f), thickness = 0.5.dp)
                }
            }
        }
    }

    picked?.let { e ->
        val prefs = Prefs(ctx)
        AlertDialog(
            onDismissRequest = { picked = null },
            containerColor = Palette.card,
            title = { Text(e.domain, fontSize = 17.sp, color = Palette.text) },
            text = {
                Column {
                    Text(
                        when (e.verdict) { 1 -> "Blocked by ${e.reason}"; 2 -> "Failed: ${e.reason}"; else -> if (e.reason.isNotEmpty()) "Allowed (${e.reason})" else "Allowed" },
                        color = if (e.verdict == 1) Palette.blocked else Palette.dim, fontSize = 14.sp,
                    )
                    Text("${e.typeName} · ${time(e.time)}${if (e.ms > 0) " · ${e.ms} ms" else ""}", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    if (e.verdict == 1 && e.reason == "block list") {
                        Text("Allowing it adds an allow rule, which wins over every list.", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            },
            confirmButton = {
                if (e.verdict == 1) {
                    AccentButton("Allow") {
                        prefs.allowRules = addLine(prefs.allowRules, e.domain)
                        prefs.blockRules = removeLine(prefs.blockRules, e.domain)
                        ShieldState.applyRules()
                        picked = null
                        Toast.makeText(ctx, "Allowed ${e.domain}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AccentButton("Block") {
                        prefs.blockRules = addLine(prefs.blockRules, e.domain)
                        prefs.allowRules = removeLine(prefs.allowRules, e.domain)
                        ShieldState.applyRules()
                        picked = null
                        Toast.makeText(ctx, "Blocked ${e.domain}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            dismissButton = {
                AccentButton("Copy") {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("domain", e.domain))
                    picked = null
                }
            },
        )
    }
}

@Composable
private fun LogRow(e: LogEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(when (e.verdict) { 1 -> Palette.blocked; 2 -> Palette.warn; else -> Palette.accent })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.domain, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${e.typeName} · ${time(e.time)}", color = Palette.dim, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                e.verdict == 1 -> e.reason.removePrefix("your ")
                e.verdict == 2 -> "failed"
                e.reason == "cached" -> "cached"
                else -> "${e.ms} ms"
            },
            color = if (e.verdict == 1) Palette.blocked.copy(alpha = 0.8f) else Palette.dim, fontSize = 12.sp,
        )
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
