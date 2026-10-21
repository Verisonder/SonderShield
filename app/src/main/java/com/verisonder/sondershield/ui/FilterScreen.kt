package com.verisonder.sondershield.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.core.BlockList
import com.verisonder.sondershield.core.ListGroup
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.ListStore
import com.verisonder.sondershield.data.Prefs
import com.verisonder.sondershield.service.UpdateWorker
import kotlinx.coroutines.launch

/** The Filter tab: profiles, block lists (grouped, searchable) and the live log. */
@Composable
fun FilterScreen() {
    // Profiles | Lists | Log: tap a chip or swipe sideways. The header and chips stay put; only
    // the content under them slides. Each pane hands its header text to the one header here.
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val view = pager.targetPage
    fun go(i: Int) { scope.launch { pager.animateScrollToPage(i) } }
    val heads = remember { mutableStateMapOf<Int, PaneHead>() }
    Column(Modifier.fillMaxSize()) {
        val head = heads[view]
        Column(Modifier.animateContentSize()) {
            ScreenHeader(tr("Filter", "Filtre", "Filter"), kicker = head?.kicker ?: " ", sub = head?.sub ?: " ", action = head?.action ?: {})
        }
        ChipRow(Modifier.padding(top = 4.dp)) {
            Chip(tr("Profiles", "Profils", "Profile"), view == 0) { go(0) }
            Chip(tr("Lists", "Listes", "Listen"), view == 1) { go(1) }
            Chip(tr("Log", "Journal", "Protokoll"), view == 2) { go(2) }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 2, verticalAlignment = Alignment.Top) { page ->
            val header: @Composable (String, String, @Composable RowScope.() -> Unit) -> Unit = { kicker, sub, action ->
                SideEffect { heads[page] = PaneHead(kicker, sub, action) }
            }
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    0 -> ProfilesPane(header)
                    1 -> ListsPane(header)
                    else -> LogScreen(header)
                }
            }
        }
    }
}

private class PaneHead(val kicker: String, val sub: String, val action: @Composable RowScope.() -> Unit)

private fun groupName(g: ListGroup, lang: String) = when (g) {
    ListGroup.ADS -> when (lang) { "fr" -> "BLOCAGE DES PUBS"; "de" -> "WERBEBLOCKER"; else -> "AD BLOCKING" }
    ListGroup.PRIVACY -> when (lang) { "fr" -> "VIE PRIVÉE"; "de" -> "PRIVATSPHÄRE"; else -> "PRIVACY" }
    ListGroup.SECURITY -> when (lang) { "fr" -> "SÉCURITÉ"; "de" -> "SICHERHEIT"; else -> "SECURITY" }
    ListGroup.FAMILY -> when (lang) { "fr" -> "PROTECTION FAMILLE"; "de" -> "FAMILIENSCHUTZ"; else -> "FAMILY SAFETY" }
    ListGroup.REGIONAL -> when (lang) { "fr" -> "RÉGIONAL"; "de" -> "REGIONAL"; else -> "REGIONAL" }
    ListGroup.CUSTOM -> when (lang) { "fr" -> "PERSONNALISÉES"; "de" -> "EIGENE"; else -> "CUSTOM" }
}

@Composable
private fun ListsPane(header: @Composable (String, String, @Composable RowScope.() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { ListStore(ctx) }
    val scope = rememberCoroutineScope()
    val phase by ShieldState.phase.collectAsState()
    val listsVersion by ShieldState.listsVersion.collectAsState()
    val domains by ShieldState.domains.collectAsState()
    // The lists in use now (a profile's, or your own picks). Switching one here makes them
    // your own picks.
    var enabled by remember(listsVersion) { mutableStateOf(prefs.activeLists()) }
    var active by remember(listsVersion) { mutableStateOf(prefs.activeProfile()) }
    var lists by remember(listsVersion) { mutableStateOf(prefs.allLists()) }
    var updating by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var addList by remember { mutableStateOf(false) }
    var hours by remember { mutableIntStateOf(prefs.updateHours) }
    val lang = lang()

    fun changed(download: List<BlockList> = emptyList()) {
        prefs.enabledLists = enabled
        prefs.profileId = com.verisonder.sondershield.core.Profiles.OWN
        active = prefs.activeProfile()
        scope.launch {
            if (download.isNotEmpty()) {
                updating = true
                for (l in download) store.update(l)
                updating = false
                tick++
            }
            if (phase == Phase.ON) ShieldState.applyConfig()
        }
    }

    val q = query.trim()
    val shown = lists.filter {
        q.isEmpty() || it.name.contains(q, true) || it.url.contains(q, true) || listAbout(it, lang).contains(q, true)
    }
    val groups = shown.groupBy { if (it.custom) ListGroup.CUSTOM else it.group }

    Column(Modifier.fillMaxSize()) {
        header(
            tr("${enabled.size} lists on", "${enabled.size} listes actives", "${enabled.size} Listen aktiv"),
            if (domains > 0) tr("${domains.grouped()} domains blocked now.", "${domains.grouped()} domaines bloqués en ce moment.", "${domains.grouped()} Domains werden gerade blockiert.")
            else tr("Lists download when protection starts.", "Les listes se téléchargent au démarrage de la protection.", "Listen werden beim Start des Schutzes geladen."),
        ) {}
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = Gutter).padding(top = 10.dp)) {
                    Field(query, { query = it }, tr("Search lists", "Rechercher une liste", "Listen suchen"), Modifier.fillMaxWidth(), leading = Icons.search)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton(if (updating) tr("Updating…", "Mise à jour…", "Aktualisiere…") else tr("Update all", "Tout mettre à jour", "Alle aktualisieren"), filled = false, enabled = !updating) {
                            scope.launch {
                                updating = true
                                val did = store.updateEnabled(force = true)
                                updating = false
                                tick++
                                if (did && phase == Phase.ON) ShieldState.applyConfig()
                            }
                        }
                        PillButton(tr("Add a list", "Ajouter une liste", "Liste hinzufügen"), filled = false) { addList = true }
                        if (updating) CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    }
                    // Automatic updates
                    Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Auto update", "Mise à jour auto", "Auto-Update"), color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        for ((h, label) in listOf(0 to tr("Off", "Non", "Aus"), 12 to "12 h", 24 to "24 h", 48 to "48 h")) {
                            Chip(label, hours == h) {
                                hours = h
                                prefs.updateHours = h
                                UpdateWorker.schedule(ctx)
                            }
                        }
                    }
                    Text(tr("On Wi-Fi only, when the battery isn't low.", "En Wi-Fi uniquement, quand la batterie n'est pas faible.", "Nur im WLAN, wenn der Akku nicht schwach ist."), color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    if (active.first != com.verisonder.sondershield.core.Profiles.OWN) {
                        val name = profileName(prefs.profileById(active.first), lang)
                        Block(Modifier.padding(top = 14.dp)) {
                            Text(
                                if (active.second) tr(
                                    "A schedule has $name on right now, so its lists are in use. Lists you switch here apply when it ends.",
                                    "Une plage horaire active $name, ses listes sont utilisées. Vos changements ici s'appliqueront à la fin.",
                                    "Ein Zeitplan hat gerade $name aktiv, also gelten dessen Listen. Änderungen hier gelten danach.",
                                ) else tr(
                                    "These are the lists of the $name profile. Switching one here changes to your own picks.",
                                    "Ce sont les listes du profil $name. En changer une ici passe à vos choix.",
                                    "Das sind die Listen des Profils $name. Änderst du hier eine, gilt deine eigene Auswahl.",
                                ),
                                color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp,
                            )
                        }
                    }
                }
            }
            for (g in ListGroup.entries) {
                val ls = groups[g] ?: continue
                item(key = "g-$g") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 26.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(groupName(g, lang), color = Palette.accentText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                        val n = ls.count { it.id in enabled }
                        Text(tr("$n on", "$n actives", "$n aktiv"), color = Palette.faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                items(ls, key = { it.id }) { l ->
                    // tick: metadata is read again after a download.
                    val meta = remember(tick, listsVersion, l.id) { prefs.listMeta(l.id) }
                    ListRow(
                        l, l.id in enabled, meta, lang,
                        onFlip = { v ->
                            enabled = if (v) enabled + l.id else enabled - l.id
                            changed(if (v && !store.file(l.id).isFile) listOf(l) else emptyList())
                        },
                        onRemove = {
                            prefs.customLists = prefs.customLists.filter { it.id != l.id }
                            prefs.customProfiles = prefs.customProfiles.map { it.copy(lists = it.lists - l.id) }
                            store.delete(l.id)
                            enabled = enabled - l.id
                            lists = prefs.allLists()
                            changed()
                        },
                    )
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(tr("No list matches.", "Aucune liste ne correspond.", "Keine Liste passt."), color = Palette.faint, fontSize = 14.sp, modifier = Modifier.padding(Gutter))
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }

    if (addList) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        val ok = name.isNotBlank() && url.startsWith("https://")
        AlertDialog(
            onDismissRequest = { addList = false },
            containerColor = Palette.panel,
            shape = RoundedCornerShape(24.dp),
            title = { Text(tr("Add a block list", "Ajouter une liste", "Blockliste hinzufügen"), style = display(20), color = Palette.text) },
            text = {
                Column {
                    Field(name, { name = it }, tr("Name", "Nom", "Name"), Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Field(url, { url = it.trim() }, tr("https://… (hosts, AdBlock or domains)", "https://… (hosts, AdBlock ou domaines)", "https://… (hosts, AdBlock oder Domains)"), Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri)
                }
            },
            confirmButton = {
                PillButton(tr("Add", "Ajouter", "Hinzufügen"), enabled = ok) {
                    prefs.customLists = prefs.customLists + BlockList("", name.trim().replace("|", " "), url, url)
                    lists = prefs.allLists()
                    val added = lists.last()
                    enabled = enabled + added.id
                    addList = false
                    changed(listOf(added))
                }
            },
            dismissButton = { PillButton(tr("Cancel", "Annuler", "Abbrechen"), filled = false) { addList = false } },
        )
    }
}

@Composable
private fun ListRow(l: BlockList, on: Boolean, meta: com.verisonder.sondershield.data.ListMeta, lang: String, onFlip: (Boolean) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onFlip(!on) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.name, color = if (on) Palette.text else Palette.dim, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Tag(if (l.custom) tr("Custom", "Perso", "Eigene") else tr("Built-in", "Intégrée", "Integriert"))
                }
                Text(listAbout(l, lang), color = Palette.dim, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
                val status = when {
                    meta.error.isNotEmpty() -> tr("Error: ", "Erreur : ", "Fehler: ") + meta.error
                    meta.updated > 0 -> "${meta.entries.grouped()} " + tr("rules", "règles", "Regeln") + " · " + ago(meta.updated, lang)
                    on -> tr("Downloads when protection starts", "Téléchargée au démarrage de la protection", "Wird beim Start des Schutzes geladen")
                    else -> tr("Not downloaded", "Pas téléchargée", "Nicht geladen")
                }
                Text(status, color = if (meta.error.isNotEmpty()) Palette.warn else Palette.faint, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text(l.url.removePrefix("https://"), color = Palette.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            if (l.custom) {
                IconPill(Icons.trash, tr("Remove list", "Retirer la liste", "Liste entfernen"), onRemove)
                Spacer(Modifier.width(6.dp))
            }
            Toggle(on) { onFlip(it) }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = Gutter).height(1.dp).background(Palette.line.copy(alpha = 0.7f)))
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text, color = Palette.dim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.card2).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
