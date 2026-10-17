package com.verisonder.sondershield.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.Prefs

@Composable
fun RulesScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var allowTab by rememberSaveable { mutableStateOf(false) }
    var block by remember { mutableStateOf(prefs.blockRules) }
    var allow by remember { mutableStateOf(prefs.allowRules) }
    var input by rememberSaveable { mutableStateOf("") }
    var test by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        prefs.blockRules = block
        prefs.allowRules = allow
        ShieldState.applyRules()
    }

    fun add() {
        val d = input.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (!Regex("^(\\*\\.)?[a-z0-9_-]+(\\.[a-z0-9_-]+)+$").matches(d)) {
            error = trc(ctx, "Enter a domain like ads.example.com", "Entrez un domaine comme ads.example.com")
            return
        }
        error = null
        if (allowTab) {
            allow = addLine(allow, d); block = removeLine(block, d)
        } else {
            block = addLine(block, d); allow = removeLine(allow, d)
        }
        input = ""
        save()
    }

    val rules = (if (allowTab) allow else block).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ScreenHeader(tr("Rules", "Règles"), kicker = tr("Yours win", "Les vôtres passent d'abord"))
            ChipRow(Modifier.padding(top = 4.dp)) {
                Chip(tr("Block", "Bloquer"), !allowTab) { allowTab = false }
                Chip(tr("Allow", "Autoriser"), allowTab) { allowTab = true }
            }
            Text(
                if (allowTab) tr("Always allowed, even when a list blocks them. Covers subdomains.", "Toujours autorisés, même si une liste les bloque. Inclut les sous-domaines.")
                else tr("Always blocked. Covers subdomains: example.com also blocks ads.example.com.", "Toujours bloqués. Inclut les sous-domaines : example.com bloque aussi ads.example.com."),
                color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = Gutter), verticalAlignment = Alignment.CenterVertically) {
                Field(input, { input = it; error = null }, tr("Add a domain", "Ajouter un domaine"), Modifier.weight(1f), keyboard = KeyboardType.Uri, onDone = ::add)
                Spacer(Modifier.width(8.dp))
                PillButton(tr("Add", "Ajouter"), onClick = ::add)
            }
            error?.let { Text(it, color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(horizontal = Gutter, vertical = 6.dp)) }

            // Check what happens to a domain, and why.
            Section(1, tr("Test a domain", "Tester un domaine"), first = true) {
                Field(test, { test = it }, tr("e.g. graph.facebook.com", "ex. graph.facebook.com"), Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri)
                if (test.isNotBlank()) {
                    val r = ShieldState.engine.check(test.trim()).split('|')
                    val verdict = r.getOrElse(0) { "" }
                    val reason = r.getOrElse(1) { "" }
                    val match = r.getOrElse(2) { "" }
                    val blockedNow = verdict == "blocked"
                    Block(Modifier.padding(top = 8.dp), color = if (blockedNow) Palette.accent else Palette.panel) {
                        Text(
                            when (verdict) {
                                "blocked" -> tr("Blocked", "Bloqué")
                                "allowed" -> tr("Allowed", "Autorisé")
                                else -> tr("Not a valid domain", "Domaine invalide")
                            },
                            style = display(18), color = if (blockedNow) Palette.onAccent else Palette.text,
                        )
                        if (verdict == "blocked" || verdict == "allowed") {
                            Text(
                                if (reason.isNotEmpty()) tr("by ", "par ") + reasonLabel(ctx, reason) + " · $match" else tr("No list or rule matches it.", "Aucune liste ni règle ne le concerne."),
                                color = if (blockedNow) Palette.onAccent else Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }

            Section(2, if (allowTab) tr("Allowed", "Autorisés") + " (${rules.size})" else tr("Blocked", "Bloqués") + " (${rules.size})") {
                if (rules.isEmpty()) {
                    Text(tr("No rules yet. You can also add them from the Log.", "Aucune règle. Vous pouvez aussi en ajouter depuis le Journal."), color = Palette.faint, fontSize = 13.sp, modifier = Modifier.padding(bottom = 16.dp))
                }
            }
        }
        items(rules) { r ->
            Box(Modifier.padding(horizontal = Gutter)) {
                OptionCard(r, if (allowTab) tr("always allowed", "toujours autorisé") else tr("always blocked", "toujours bloqué")) {
                    IconPill(Icons.close, tr("Remove", "Retirer") + " $r") {
                        if (allowTab) allow = removeLine(allow, r) else block = removeLine(block, r)
                        save()
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
