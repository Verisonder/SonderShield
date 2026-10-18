package com.verisonder.sondershield.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.verisonder.sondershield.core.Profile
import com.verisonder.sondershield.core.Profiles
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home's top-right chip: the profile in use. Tap to switch profile, or save the lists you
 * use now as a profile of your own.
 */
@Composable
internal fun ProfileChip() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val listsVersion by ShieldState.listsVersion.collectAsState()
    var rev by remember { mutableIntStateOf(0) }
    val active = remember(listsVersion, rev) { prefs.activeProfile() }
    val p = remember(active) { prefs.profileById(active.first) }
    var open by remember { mutableStateOf(false) }
    val lang = lang()
    val src = remember { MutableInteractionSource() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBar: androidx.compose.ui.unit.Dp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    // Re-read now and then: a schedule can switch the profile while Home is open.
    LaunchedEffect(Unit) {
        while (true) { delay(20_000); rev++ }
    }

    Row(
        Modifier.pressScale(src).clip(CircleShape).border(1.5.dp, Palette.line, CircleShape)
            .clickable(interactionSource = src, indication = androidx.compose.material3.ripple()) { open = true }
            .padding(start = 9.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = (p?.icon ?: "own") to chipName(p, lang),
            transitionSpec = {
                (slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(260)))
                    .togetherWith(slideOutVertically(tween(220)) { -it } + fadeOut(tween(180)))
            },
            label = "profileChip",
        ) { (icon, name) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(profileIcon(icon), null, tint = Palette.accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(name, color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
            }
        }
        if (active.second) {
            Spacer(Modifier.width(5.dp))
            Icon(Icons.clock, tr("On by schedule", "Par horaire", "Per Zeitplan"), tint = Palette.faint, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.width(4.dp))
        val turn by androidx.compose.animation.core.animateFloatAsState(if (open) -90f else 90f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "chev")
        Icon(Icons.chevron, null, tint = Palette.dim, modifier = Modifier.size(12.dp).rotate(turn))
    }

    if (open) ProfileSheet(navBar, onClose = { open = false; rev++ })
}

/** Short name for the chip, so it never pushes into the wordmark. */
private fun chipName(p: Profile?, lang: String): String =
    if (p == null || p.id == Profiles.OWN) when (lang) { "fr" -> "Mes listes"; "de" -> "Eigene"; else -> "Own picks" }
    else profileName(p, lang)

/** Slides up from the bottom under a swoosh: pick a profile, or make your own. */
@Composable
private fun ProfileSheet(navBar: androidx.compose.ui.unit.Dp, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val phase by ShieldState.phase.collectAsState()
    var rev by remember { mutableIntStateOf(0) }
    val profiles = remember(rev) { prefs.allProfiles() }
    val chosen = remember(rev) { prefs.profileId }
    val active = remember(rev) { prefs.activeProfile() }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val lang = lang()

    val shown = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { shown.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 360f)) }
        delay(140)
        sweep.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            launch { sweep.animateTo(0f, tween(180)) }
            shown.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
            onClose()
        }
    }
    fun pick(id: String) {
        if (id == chosen) { close(); return }
        prefs.profileId = id
        rev++
        applyProfileChange(ctx, scope, phase)
        // Let the check pop before the sheet goes.
        scope.launch { delay(420); close() }
    }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .graphicsLayer { translationY = (1f - shown.value) * size.height },
            ) {
                Swoosh(blade = Palette.accent, below = Palette.panel, progress = sweep.value)
                Column(Modifier.fillMaxWidth().background(Palette.panel).padding(bottom = navBar + 16.dp)) {
                    Column(Modifier.padding(horizontal = Gutter).padding(top = 4.dp, bottom = 10.dp)) {
                        Kicker(tr("Protection profile", "Profil de protection", "Schutzprofil"))
                        Text(tr("How much to block", "Que bloquer", "Wie viel blocken"), style = display(26), color = Palette.text, modifier = Modifier.padding(top = 10.dp))
                        Text(
                            tr("Only changes which block lists are on.", "Change seulement les listes actives.", "Ändert nur, welche Listen aktiv sind."),
                            color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                        if (active.second) {
                            Text(
                                tr("A schedule has ${profileName(prefs.profileById(active.first), lang)} on right now.",
                                    "Une plage horaire active ${profileName(prefs.profileById(active.first), lang)} en ce moment.",
                                    "Ein Zeitplan hat gerade ${profileName(prefs.profileById(active.first), lang)} aktiv."),
                                color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    val listScroll = rememberScrollState()
                    Column(
                        Modifier.heightIn(max = 400.dp).fadeEdges(listScroll).verticalScroll(listScroll)
                            .padding(horizontal = 14.dp).padding(top = 10.dp, bottom = 6.dp),
                    ) {
                        profiles.forEachIndexed { i, p ->
                            SheetRow(
                                p, prefs.profileLists(p).size, lang, p.id == chosen, active.second && active.first == p.id, i,
                                onPick = { menuFor = null; pick(p.id) },
                                menuOpen = menuFor == p.id,
                                onLongPress = if (p.custom) ({ menuFor = if (menuFor == p.id) null else p.id }) else null,
                                onEdit = { menuFor = null; editing = p },
                                onDelete = {
                                    menuFor = null
                                    deleteProfile(prefs, p.id)
                                    rev++
                                    applyProfileChange(ctx, scope, phase)
                                },
                            )
                        }
                        SheetRow(
                            Profile(Profiles.OWN, "", icon = "own", lists = prefs.enabledLists), prefs.enabledLists.size, lang,
                            chosen == Profiles.OWN, false, profiles.size, onPick = { menuFor = null; pick(Profiles.OWN) },
                            about = tr("Exactly the lists you switch on in Filter › Lists.", "Exactement les listes activées dans Filtre › Listes.", "Genau die Listen unter Filter › Listen."),
                        )
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 10.dp).reveal(true, profiles.size + 1), contentAlignment = Alignment.Center) {
                        PillButton(tr("+ Make your own", "+ Créer le vôtre", "+ Eigenes erstellen")) { creating = true }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        ProfileEditor(prefs, editing, lang,
            onDone = {
                creating = false; editing = null; rev++
                applyProfileChange(ctx, scope, phase)
            },
            onCancel = { creating = false; editing = null })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SheetRow(
    p: Profile, count: Int, lang: String, chosen: Boolean, bySchedule: Boolean, order: Int,
    onPick: () -> Unit, about: String? = null,
    menuOpen: Boolean = false, onLongPress: (() -> Unit)? = null, onEdit: () -> Unit = {}, onDelete: () -> Unit = {},
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val bg by animateColorAsState(if (chosen) Palette.accent else Palette.card2, tween(260), label = "rowBg")
    val fg by animateColorAsState(if (chosen) Palette.onAccent else Palette.text, tween(260), label = "rowFg")
    val sub = if (chosen) Palette.onAccent.copy(alpha = 0.8f) else Palette.dim
    val src = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).reveal(true, order).pressScale(src).clip(blockShape).background(bg)
            .combinedClickable(
                interactionSource = src, indication = androidx.compose.material3.ripple(), onClick = onPick,
                onLongClick = onLongPress?.let { f -> { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); f() } },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (chosen) Palette.onAccent.copy(alpha = 0.16f) else Palette.panel),
            contentAlignment = Alignment.Center,
        ) { Icon(profileIcon(p.icon), null, tint = fg, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profileName(p, lang), color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (bySchedule) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.clock, null, tint = sub, modifier = Modifier.size(13.dp))
                }
            }
            val line = about ?: profileAbout(p, lang)
            if (line.isNotEmpty()) Text(line, color = sub, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Text(tr("$count lists", "$count listes", "$count Listen"), color = sub, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(6.dp))
        SheetCheck(chosen)
    }
    if (onLongPress != null) RowActions(menuOpen, onEdit, onDelete, Modifier.padding(start = 56.dp, top = 10.dp), onDark = chosen)
    }
}

/** White check that pops in on the picked row. */
@Composable
private fun SheetCheck(on: Boolean) {
    val pop by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 520f), label = "pop")
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, Palette.faint.copy(alpha = 1f - pop.coerceIn(0f, 1f)), CircleShape))
        Box(
            Modifier.size(26.dp).graphicsLayer { scaleX = pop; scaleY = pop; alpha = pop.coerceIn(0f, 1f) }.clip(CircleShape).background(Palette.onAccent),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.check, null, tint = Palette.accent, modifier = Modifier.size(16.dp)) }
    }
}

/** Fades the top and bottom of a scrolling list instead of cutting rows off hard. */
private fun Modifier.fadeEdges(scroll: androidx.compose.foundation.ScrollState, edge: androidx.compose.ui.unit.Dp = 22.dp): Modifier =
    this.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val e = edge.toPx()
            val top = if (scroll.value > 0) e else 0f
            val bottom = if (scroll.value < scroll.maxValue) e else 0f
            if (top > 0f) drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = top),
                size = androidx.compose.ui.geometry.Size(size.width, top), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
            if (bottom > 0f) drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - bottom, endY = size.height),
                topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - bottom),
                size = androidx.compose.ui.geometry.Size(size.width, bottom), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
