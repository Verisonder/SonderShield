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
            error = "Enter a domain like ads.example.com"
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
            ScreenHeader("Rules", kicker = "Yours win")
            ChipRow(Modifier.padding(top = 4.dp)) {
                Chip("Block", !allowTab) { allowTab = false }
                Chip("Allow", allowTab) { allowTab = true }
            }
            Text(
                if (allowTab) "Always allowed, even when a list blocks them. Covers subdomains."
                else "Always blocked. Covers subdomains: example.com also blocks ads.example.com.",
                color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = Gutter), verticalAlignment = Alignment.CenterVertically) {
                Field(input, { input = it; error = null }, "Add a domain", Modifier.weight(1f), keyboard = KeyboardType.Uri, onDone = ::add)
                Spacer(Modifier.width(8.dp))
                PillButton("Add", onClick = ::add)
            }
            error?.let { Text(it, color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(horizontal = Gutter, vertical = 6.dp)) }

            // Check what happens to a domain, and why.
            Section(1, "Test a domain", first = true) {
                Field(test, { test = it }, "e.g. graph.facebook.com", Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri)
                if (test.isNotBlank()) {
                    val r = ShieldState.engine.check(test.trim()).split('|')
                    val verdict = r.getOrElse(0) { "" }
                    val reason = r.getOrElse(1) { "" }
                    val match = r.getOrElse(2) { "" }
                    val blockedNow = verdict == "blocked"
                    Block(Modifier.padding(top = 8.dp), color = if (blockedNow) Palette.accent else Palette.panel) {
                        Text(
                            when (verdict) {
                                "blocked" -> "Blocked"
                                "allowed" -> "Allowed"
                                else -> "Not a valid domain"
                            },
                            style = display(18), color = if (blockedNow) Palette.onAccent else Palette.text,
                        )
                        if (verdict == "blocked" || verdict == "allowed") {
                            Text(
                                if (reason.isNotEmpty()) "by ${reasonLabel(ctx, reason)} · $match" else "No list or rule matches it.",
                                color = if (blockedNow) Palette.onAccent else Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }

            Section(2, if (allowTab) "Allowed (${rules.size})" else "Blocked (${rules.size})") {
                if (rules.isEmpty()) {
                    Text("No rules yet. You can also add them from the Log.", color = Palette.faint, fontSize = 13.sp, modifier = Modifier.padding(bottom = 16.dp))
                }
            }
        }
        items(rules) { r ->
            Box(Modifier.padding(horizontal = Gutter)) {
                OptionCard(r, if (allowTab) "always allowed" else "always blocked") {
                    IconPill(Icons.close, "Remove $r") {
                        if (allowTab) allow = removeLine(allow, r) else block = removeLine(block, r)
                        save()
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
