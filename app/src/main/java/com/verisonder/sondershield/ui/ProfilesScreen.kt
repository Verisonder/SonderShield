package com.verisonder.sondershield.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.core.ListGroup
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.Profile
import com.verisonder.sondershield.core.Profiles
import com.verisonder.sondershield.core.Schedule
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.ListStore
import com.verisonder.sondershield.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** A profile's name in the app's language (yours keep the name you gave them). */
fun profileName(p: Profile?, lang: String): String = when {
    p == null || p.id == Profiles.OWN -> when (lang) { "fr" -> "Vos choix"; "de" -> "Eigene Auswahl"; else -> "Your own picks" }
    p.custom -> p.name
    else -> when (p.id) {
        "default" -> when (lang) { "fr" -> "Par défaut"; "de" -> "Standard"; else -> "Default" }
        "strict" -> when (lang) { "fr" -> "Strict"; "de" -> "Streng"; else -> "Strict" }
        "family" -> when (lang) { "fr" -> "Famille"; "de" -> "Familie"; else -> "Family" }
        "gaming" -> when (lang) { "fr" -> "Jeux"; "de" -> "Gaming"; else -> "Gaming" }
        "strict-family" -> when (lang) { "fr" -> "Famille stricte"; "de" -> "Familie streng"; else -> "Strict Family" }
        else -> p.name
    }
}

private fun profileAbout(p: Profile, lang: String): String = when (p.id) {
    "default" -> when (lang) { "fr" -> "Blocage équilibré : pubs et traqueurs courants"; "de" -> "Ausgewogen: übliche Werbung und Tracker"; else -> p.about }
    "strict" -> when (lang) { "fr" -> "Blocage poussé : pubs, traqueurs, statistiques, télémétrie"; "de" -> "Stark: Werbung, Tracker, Analyse, Telemetrie"; else -> p.about }
    "family" -> when (lang) { "fr" -> "Navigation sûre : pubs, contenu adulte, jeux d'argent"; "de" -> "Sicher surfen: Werbung, Erwachseneninhalte, Glücksspiel"; else -> p.about }
    "gaming" -> when (lang) { "fr" -> "Blocage léger : pubs seulement, le moins de jeux cassés"; "de" -> "Leicht: nur Werbung, am wenigsten kaputte Spiele"; else -> p.about }
    "strict-family" -> when (lang) { "fr" -> "Blocage maximal : pubs, traqueurs, contenu adulte et jeux d'argent"; "de" -> "Maximal: Werbung, Tracker, Erwachseneninhalte und Glücksspiel"; else -> p.about }
    else -> p.about
}

private fun profileIcon(key: String): ImageVector = when (key) {
    "shield" -> Icons.pShield
    "strict" -> Icons.pStrict
    "family" -> Icons.pFamily
    "game" -> Icons.pGame
    "lock" -> Icons.pLock
    "moon" -> Icons.pMoon
    "work" -> Icons.pWork
    "own" -> Icons.rules
    else -> Icons.pStar
}

private val ICON_KEYS = listOf("star", "moon", "work", "game", "family", "shield", "strict", "lock")
/** Monday first, as bits of [Schedule.days] (Sunday = 0). */
private val WEEK = listOf(1, 2, 3, 4, 5, 6, 0)

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

private fun dayShort(bit: Int, lang: String): String =
    DayOfWeek.of(if (bit == 0) 7 else bit).getDisplayName(TextStyle.SHORT, Locale(lang)).replaceFirstChar { it.uppercase() }.trimEnd('.')

private fun daysText(mask: Int, lang: String): String = when (mask) {
    127 -> when (lang) { "fr" -> "Tous les jours"; "de" -> "Jeden Tag"; else -> "Every day" }
    62 -> when (lang) { "fr" -> "En semaine"; "de" -> "Werktags"; else -> "Weekdays" }
    65 -> when (lang) { "fr" -> "Le week-end"; "de" -> "Am Wochenende"; else -> "Weekends" }
    else -> WEEK.filter { mask and (1 shl it) != 0 }.joinToString(", ") { dayShort(it, lang) }
}

/** Filter › Profiles: ready-made sets of lists for people who don't know which to pick,
 *  your own sets, and schedules that switch profile at set times. */
@Composable
internal fun ProfilesPane(header: @Composable (String, String, @Composable RowScope.() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val phase by ShieldState.phase.collectAsState()
    val listsVersion by ShieldState.listsVersion.collectAsState()
    var rev by remember { mutableIntStateOf(0) }
    val profiles = remember(rev, listsVersion) { prefs.allProfiles() }
    val chosen = remember(rev, listsVersion) { prefs.profileId }
    val active = remember(rev, listsVersion) { prefs.activeProfile() }
    val schedules = remember(rev, listsVersion) { prefs.schedules }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var scheduling by remember { mutableStateOf<String?>(null) }
    val lang = lang()

    // Push the change to the engine: fetch lists the new profile needs, then reload.
    fun changed() {
        rev++
        scope.launch {
            if (phase == Phase.ON) {
                val store = ListStore(ctx)
                withContext(Dispatchers.IO) { for (l in store.missingEnabled()) store.update(l) }
                ShieldState.applyConfig()
            }
            ShieldState.listsVersion.value++
        }
    }

    val nowName = profileName(prefs.profileById(active.first), lang)
    Column(Modifier.fillMaxSize()) {
        header(
            tr("Using $nowName", "Profil : $nowName", "Aktiv: $nowName"),
            tr("Pick how much SonderShield blocks. You can change it any time.", "Choisissez ce que SonderShield bloque. Modifiable à tout moment.", "Wähle, wie viel SonderShield blockiert. Jederzeit änderbar."),
        ) {}
        LazyColumn(Modifier.fillMaxSize()) {
            if (active.second) {
                item {
                    Block(Modifier.padding(horizontal = Gutter).padding(top = 12.dp)) {
                        Text(
                            tr(
                                "A schedule has $nowName on right now. When it ends, ${profileName(prefs.profileById(chosen), lang)} comes back.",
                                "Une plage horaire active $nowName en ce moment. À la fin, ${profileName(prefs.profileById(chosen), lang)} revient.",
                                "Ein Zeitplan hat gerade $nowName aktiv. Danach gilt wieder ${profileName(prefs.profileById(chosen), lang)}.",
                            ),
                            color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                    }
                }
            }
            item { SectionLabel(tr("PROFILES", "PROFILS", "PROFILE")) }
            items(profiles.filter { !it.custom }, key = { it.id }) { p ->
                ProfileRow(p, prefs.profileLists(p).size, lang, p.id == chosen, active.second && active.first == p.id, schedules.count { it.profile == p.id && it.on },
                    onPick = { prefs.profileId = p.id; changed() }, onSchedule = { scheduling = p.id }, onEdit = null)
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("YOURS", "LES VÔTRES", "EIGENE"), color = Palette.accentText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                    PillButton(tr("+ New", "+ Nouveau", "+ Neu"), filled = false) { creating = true }
                }
            }
            items(profiles.filter { it.custom }, key = { it.id }) { p ->
                ProfileRow(p, p.lists.size, lang, p.id == chosen, active.second && active.first == p.id, schedules.count { it.profile == p.id && it.on },
                    onPick = { prefs.profileId = p.id; changed() }, onSchedule = { scheduling = p.id }, onEdit = { editing = p })
            }
            item(key = "own") {
                ProfileRow(
                    Profile(Profiles.OWN, "", icon = "own", lists = prefs.enabledLists), prefs.enabledLists.size, lang, chosen == Profiles.OWN, false, 0,
                    onPick = { prefs.profileId = Profiles.OWN; changed() }, onSchedule = null, onEdit = null,
                    about = tr("Exactly the lists you switch on in Lists.", "Exactement les listes activées dans Listes.", "Genau die Listen, die du unter Listen einschaltest."),
                )
            }
            item {
                Text(
                    tr(
                        "A profile only decides which block lists are on. Your rules and app settings stay as they are.",
                        "Un profil décide seulement des listes actives. Vos règles et réglages d'applis ne changent pas.",
                        "Ein Profil bestimmt nur, welche Listen aktiv sind. Deine Regeln und App-Einstellungen bleiben.",
                    ),
                    color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = Gutter, vertical = 16.dp),
                )
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }

    if (creating || editing != null) {
        ProfileEditor(prefs, editing, lang,
            onDone = { creating = false; editing = null; changed() },
            onCancel = { creating = false; editing = null })
    }
    scheduling?.let { pid ->
        ScheduleDialog(prefs, pid, profileName(prefs.profileById(pid), lang), lang,
            onChange = { changed() }, onClose = { scheduling = null })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Palette.accentText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(horizontal = Gutter).padding(top = 22.dp, bottom = 8.dp))
}

@Composable
private fun ProfileRow(
    p: Profile, count: Int, lang: String, chosen: Boolean, bySchedule: Boolean, scheduleCount: Int,
    onPick: () -> Unit, onSchedule: (() -> Unit)?, onEdit: (() -> Unit)?, about: String? = null,
) {
    val badge by animateColorAsState(if (chosen) Palette.accent else Palette.card2, label = "pbadge")
    val badgeFg = if (chosen) Palette.onAccent else Palette.text
    Column(Modifier.fillMaxWidth().clickable(onClick = onPick)) {
        Row(Modifier.fillMaxWidth().padding(start = Gutter, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
                Icon(profileIcon(p.icon), null, tint = badgeFg, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profileName(p, lang), color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (bySchedule) {
                        Spacer(Modifier.width(8.dp))
                        Text(tr("ON BY SCHEDULE", "PAR HORAIRE", "PER ZEITPLAN"), color = Palette.onAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.accent).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                val line = about ?: profileAbout(p, lang)
                if (line.isNotEmpty()) Text(line, color = Palette.dim, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
                val extra = if (scheduleCount > 0) " · " + tr("$scheduleCount schedule", "$scheduleCount horaire", "$scheduleCount Zeitplan") + (if (scheduleCount > 1) tr("s", "s", "e") else "") else ""
                Text(tr("$count lists", "$count listes", "$count Listen") + extra, color = Palette.faint, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
            }
            if (onEdit != null) RoundIcon(Icons.edit, tr("Edit", "Modifier", "Bearbeiten"), false, onEdit)
            if (onSchedule != null) RoundIcon(Icons.clock, tr("Schedule", "Horaires", "Zeitplan"), scheduleCount > 0, onSchedule)
            Spacer(Modifier.width(6.dp))
            CheckDot(chosen)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = Gutter).height(1.dp).background(Palette.line.copy(alpha = 0.7f)))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, lit: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = if (lit) Palette.accent else Palette.faint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun CheckDot(on: Boolean) {
    val bg by animateColorAsState(if (on) Palette.accent else androidx.compose.ui.graphics.Color.Transparent, label = "check")
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(bg).border(2.dp, if (on) Palette.accent else Palette.faint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.check, null, tint = Palette.onAccent, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun CheckBox(on: Boolean) {
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Palette.accent else androidx.compose.ui.graphics.Color.Transparent)
            .border(2.dp, if (on) Palette.accent else Palette.faint, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.check, null, tint = Palette.onAccent, modifier = Modifier.size(15.dp))
    }
}

private fun groupTitle(g: ListGroup, lang: String) = when (g) {
    ListGroup.ADS -> when (lang) { "fr" -> "BLOCAGE DES PUBS"; "de" -> "WERBEBLOCKER"; else -> "AD BLOCKING" }
    ListGroup.PRIVACY -> when (lang) { "fr" -> "VIE PRIVÉE"; "de" -> "PRIVATSPHÄRE"; else -> "PRIVACY" }
    ListGroup.SECURITY -> when (lang) { "fr" -> "SÉCURITÉ"; "de" -> "SICHERHEIT"; else -> "SECURITY" }
    ListGroup.FAMILY -> when (lang) { "fr" -> "PROTECTION FAMILLE"; "de" -> "FAMILIENSCHUTZ"; else -> "FAMILY SAFETY" }
    ListGroup.REGIONAL -> when (lang) { "fr" -> "RÉGIONAL"; "de" -> "REGIONAL"; else -> "REGIONAL" }
    ListGroup.CUSTOM -> when (lang) { "fr" -> "PERSONNALISÉES"; "de" -> "EIGENE"; else -> "CUSTOM" }
}

/** New profile (editing == null) or change one of yours. */
@Composable
private fun ProfileEditor(prefs: Prefs, editing: Profile?, lang: String, onDone: () -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var icon by remember { mutableStateOf(editing?.icon ?: "star") }
    var on by remember { mutableStateOf(editing?.lists ?: prefs.activeLists()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val all = remember { prefs.allLists() }
    val ok = name.isNotBlank() && on.isNotEmpty()
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        shape = RoundedCornerShape(24.dp),
        title = { Text(if (editing == null) tr("New profile", "Nouveau profil", "Neues Profil") else tr("Edit profile", "Modifier le profil", "Profil bearbeiten"), style = display(20), color = Palette.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Field(name, { name = it.take(30) }, tr("Name, e.g. Work", "Nom, ex. Travail", "Name, z. B. Arbeit"), Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in ICON_KEYS) {
                        val sel = k == icon
                        Box(
                            Modifier.size(42.dp).clip(CircleShape).background(if (sel) Palette.accent else Palette.card2).clickable { icon = k },
                            contentAlignment = Alignment.Center,
                        ) { Icon(profileIcon(k), null, tint = if (sel) Palette.onAccent else Palette.text, modifier = Modifier.size(21.dp)) }
                    }
                }
                val groups = all.groupBy { if (it.custom) ListGroup.CUSTOM else it.group }
                for (g in ListGroup.entries) {
                    val ls = groups[g] ?: continue
                    Text(groupTitle(g, lang), color = Palette.accentText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    for (l in ls) {
                        val c = l.id in on
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { on = if (c) on - l.id else on + l.id }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CheckBox(c)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(l.name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(listAbout(l, lang), color = Palette.dim, fontSize = 12.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                }
                if (editing != null) {
                    Spacer(Modifier.height(14.dp))
                    PillButton(if (confirmDelete) tr("Tap again to delete", "Touchez encore pour supprimer", "Nochmal tippen zum Löschen") else tr("Delete profile", "Supprimer le profil", "Profil löschen"), filled = false) {
                        if (!confirmDelete) { confirmDelete = true; return@PillButton }
                        prefs.customProfiles = prefs.customProfiles.filter { it.id != editing.id }
                        prefs.schedules = prefs.schedules.filter { it.profile != editing.id }
                        if (prefs.profileId == editing.id) prefs.profileId = Profiles.DEFAULT
                        onDone()
                    }
                }
            }
        },
        confirmButton = {
            PillButton(tr("Save", "Enregistrer", "Speichern"), enabled = ok) {
                val id = editing?.id ?: ("p-" + System.currentTimeMillis().toString(36))
                val p = Profile(id, name.trim(), icon = icon, lists = on, custom = true)
                prefs.customProfiles = if (editing == null) prefs.customProfiles + p else prefs.customProfiles.map { if (it.id == id) p else it }
                if (editing == null) prefs.profileId = id
                onDone()
            }
        },
        dismissButton = { PillButton(tr("Cancel", "Annuler", "Abbrechen"), filled = false, onClick = onCancel) },
    )
}

/** The times a profile switches on by itself. */
@Composable
private fun ScheduleDialog(prefs: Prefs, pid: String, name: String, lang: String, onChange: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(prefs.schedules) }
    var days by remember { mutableIntStateOf(62) }
    var start by remember { mutableIntStateOf(21 * 60) }
    var end by remember { mutableIntStateOf(7 * 60) }
    val is24 = DateFormat.is24HourFormat(ctx)
    fun save(v: List<Schedule>) { list = v; prefs.schedules = v; onChange() }
    fun pick(initial: Int, set: (Int) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> set(h * 60 + m) }, initial / 60, initial % 60, is24).show()
    }
    val mine = list.filter { it.profile == pid }
    val canAdd = days != 0 && start != end
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.panel,
        shape = RoundedCornerShape(24.dp),
        title = { Text(tr("Schedule $name", "Horaires : $name", "Zeitplan: $name"), style = display(20), color = Palette.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    tr("During these times $name is used, whatever profile you picked. Outside them your pick comes back.",
                        "Pendant ces horaires, $name est utilisé quel que soit votre profil. En dehors, votre choix revient.",
                        "In diesen Zeiten gilt $name, egal welches Profil du gewählt hast. Danach gilt wieder deine Wahl."),
                    color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp,
                )
                if (mine.isEmpty()) {
                    Text(tr("No schedule yet.", "Pas encore d'horaire.", "Noch kein Zeitplan."), color = Palette.faint, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                }
                for (s in mine) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Palette.card2).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(hm(s.start) + " – " + hm(s.end) + (if (s.end <= s.start) tr(" (next day)", " (lendemain)", " (Folgetag)") else ""), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(daysText(s.days, lang), color = Palette.dim, fontSize = 12.sp)
                        }
                        RoundIcon(Icons.trash, tr("Remove", "Retirer", "Entfernen"), false) { save(list.filter { it.id != s.id }) }
                        Toggle(s.on) { v -> save(list.map { if (it.id == s.id) it.copy(on = v) else it }) }
                    }
                }
                Text(tr("ADD A TIME", "AJOUTER UN HORAIRE", "ZEIT HINZUFÜGEN"), color = Palette.accentText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in WEEK) {
                        val sel = days and (1 shl b) != 0
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(if (sel) Palette.accent else Palette.card2).clickable { days = days xor (1 shl b) },
                            contentAlignment = Alignment.Center,
                        ) { Text(dayShort(b, lang).take(2), color = if (sel) Palette.onAccent else Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("From", "De", "Von"), color = Palette.dim, fontSize = 13.sp)
                    PillButton(hm(start), filled = false) { pick(start) { start = it } }
                    Text(tr("to", "à", "bis"), color = Palette.dim, fontSize = 13.sp)
                    PillButton(hm(end), filled = false) { pick(end) { end = it } }
                }
                Spacer(Modifier.height(12.dp))
                PillButton(tr("Add", "Ajouter", "Hinzufügen"), enabled = canAdd) {
                    save(list + Schedule("s-" + System.currentTimeMillis().toString(36), pid, days, start, end, true))
                }
            }
        },
        confirmButton = { PillButton(tr("Done", "Terminé", "Fertig"), onClick = onClose) },
    )
}
