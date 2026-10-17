package com.verisonder.sondershield.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Rules")
        ChipRow {
            Chip("Block", !allowTab) { allowTab = false }
            Chip("Allow", allowTab) { allowTab = true }
        }
        Text(
            if (allowTab) "Always allowed, even when a list blocks them. Covers subdomains."
            else "Always blocked. Covers subdomains: example.com also blocks ads.example.com.",
            color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(input, { input = it; error = null }, "Add a domain", Modifier.weight(1f), keyboard = KeyboardType.Uri, onDone = ::add)
            IconButton(onClick = ::add) { Icon(Icons.plus, "Add", tint = Palette.accent) }
        }
        error?.let { Text(it, color = Palette.blocked, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }

        // Check what happens to a domain, with the reason.
        SectionLabel("Test a domain")
        Field(test, { test = it }, "e.g. graph.facebook.com", Modifier.fillMaxWidth().padding(horizontal = 16.dp), keyboard = KeyboardType.Uri)
        if (test.isNotBlank()) {
            val r = ShieldState.engine.check(test.trim()).split('|')
            val (verdict, reason, match) = Triple(r.getOrElse(0) { "" }, r.getOrElse(1) { "" }, r.getOrElse(2) { "" })
            Text(
                when (verdict) {
                    "blocked" -> "Blocked by $reason ($match)"
                    "allowed" -> if (reason.isNotEmpty()) "Allowed by $reason ($match)" else "Allowed: no list or rule matches"
                    else -> "Not a valid domain"
                },
                color = if (verdict == "blocked") Palette.blocked else if (verdict == "allowed") Palette.accent else Palette.dim,
                fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        SectionLabel(if (allowTab) "Allowed (${rules.size})" else "Blocked (${rules.size})")
        if (rules.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No rules yet. You can also add them from the Log.", color = Palette.dim, fontSize = 13.sp)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rules) { r ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (allowTab) Palette.accent else Palette.blocked)
                    Spacer(Modifier.width(12.dp))
                    Text(r, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (allowTab) allow = removeLine(allow, r) else block = removeLine(block, r)
                        save()
                    }) { Icon(Icons.close, "Remove", tint = Palette.dim, modifier = Modifier.size(18.dp)) }
                }
                HorizontalDivider(color = Palette.line.copy(alpha = 0.5f), thickness = 0.5.dp)
            }
        }
    }
}
