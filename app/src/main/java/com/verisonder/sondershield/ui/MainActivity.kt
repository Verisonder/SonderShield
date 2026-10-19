package com.verisonder.sondershield.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.verisonder.sondershield.core.ShieldState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.lifecycle.lifecycleScope
import com.verisonder.sondershield.core.AppUpdate
import com.verisonder.sondershield.service.ShieldVpnService

enum class Tab(val label: String, val fr: String, val de: String) {
    Home("Home", "Accueil", "Start"), Filter("Filter", "Filtre", "Filter"), Data("Data", "Données", "Daten"),
    Apps("Apps", "Applis", "Apps"), Settings("Settings", "Réglages", "Einstellungen")
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TURN_ON = "turn_on"
        const val EXTRA_OPEN_UPDATE = "open_update"
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            ShieldVpnService.start(this)
        } else {
            ShieldState.problem.value = trc(this, "VPN permission was not given. SonderShield needs it to filter DNS on this phone.", "L'autorisation VPN n'a pas été donnée. SonderShield en a besoin pour filtrer le DNS sur ce téléphone.", "Die VPN-Berechtigung wurde nicht erteilt. SonderShield braucht sie, um DNS auf diesem Handy zu filtern.")
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Bumped when something (like long-pressing the Quick Settings tile) should land on Home. */
    private val goHome = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun wantsHome(i: Intent?) = i?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShieldState.init(this)
        if (intent?.getBooleanExtra(EXTRA_TURN_ON, false) == true) turnOn()
        if (wantsHome(intent)) goHome.value++
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATE, false) == true) AppUpdate.openRequest.value++
        AppUpdate.restore(this)
        AppUpdate.schedule(this)

        setContent {
            val themeId by ShieldState.theme.collectAsState()
            val accentArgb by ShieldState.accent.collectAsState()
            val colors = colorsFor(ThemeMode.of(themeId), Color(accentArgb))
            // Status and navigation bars follow the theme: light icons on dark themes and back.
            LaunchedEffect(colors) {
                val bg = colors.bg.toArgb()
                val nav = colors.bg.toArgb()
                enableEdgeToEdge(
                    statusBarStyle = if (colors.dark) SystemBarStyle.dark(bg) else SystemBarStyle.light(bg, bg),
                    navigationBarStyle = if (colors.dark) SystemBarStyle.dark(nav) else SystemBarStyle.light(nav, nav),
                )
                window.decorView.setBackgroundColor(bg)
            }
            ShieldTheme(colors) {
                var onboarded by rememberSaveable { mutableStateOf(com.verisonder.sondershield.data.Prefs(this).onboarded) }
                if (!onboarded) {
                    OnboardingScreen(onAllowVpn = ::turnOn, onDone = {
                        com.verisonder.sondershield.data.Prefs(this).onboarded = true
                        onboarded = true
                    })
                    return@ShieldTheme
                }
                var tab by rememberSaveable { mutableStateOf(Tab.Home) }
                // Kept here so tapping Home while on Home can scroll it back to the top.
                val homeScroll = rememberScrollState()
                val uiScope = rememberCoroutineScope()
                BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }
                // Long-press on the tile: back to Home, at the top.
                val home by goHome.collectAsState()
                LaunchedEffect(home) {
                    if (home > 0) {
                        tab = Tab.Home
                        homeScroll.scrollTo(0)
                    }
                }
                // Opened for an update: Settings, scrolled to Updates.
                val openUpdate by AppUpdate.openRequest.collectAsState()
                LaunchedEffect(openUpdate) {
                    if (openUpdate > 0) tab = Tab.Settings
                }
                Scaffold(
                    containerColor = Palette.bg,
                    bottomBar = {
                        BottomBar(tab) { t ->
                            if (t == Tab.Home && tab == Tab.Home) uiScope.launch { homeScroll.animateScrollTo(0, spring(dampingRatio = 0.9f, stiffness = 300f)) }
                            tab = t
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().background(Palette.bg).padding(pad)) {
                        // Tab change: the new screen slides in from the side it lives on and fades up.
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 6 * dir } + fadeIn(tween(260)))
                                    .togetherWith(slideOutHorizontally(tween(260)) { -it / 10 * dir } + fadeOut(tween(180)))
                            },
                            label = "tabs",
                        ) { t ->
                            when (t) {
                                Tab.Home -> HomeScreen(onToggle = ::toggle, scroll = homeScroll)
                                Tab.Filter -> FilterScreen()
                                Tab.Data -> DataScreen()
                                Tab.Apps -> AppsScreen()
                                Tab.Settings -> SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShieldState.resumed.value++
        // Tells about a new version by itself (at most every few hours).
        lifecycleScope.launch { AppUpdate.autoCheck(this@MainActivity) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TURN_ON, false)) turnOn()
        if (wantsHome(intent)) goHome.value++
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) AppUpdate.openRequest.value++
    }

    private fun toggle() {
        when {
            ShieldState.phase.value == com.verisonder.sondershield.core.Phase.OFF -> turnOn()
            // Always-on VPN would just start it again: send the user to where it's switched off.
            ShieldState.alwaysOn.value -> try { startActivity(ShieldVpnService.vpnSettingsIntent()) } catch (e: Exception) { }
            else -> ShieldVpnService.stop(this)
        }
    }

    private fun turnOn() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val consent = if (com.verisonder.sondershield.data.Prefs(this).rootMode) null else VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else ShieldVpnService.start(this)
    }
}

/**
 * Bottom bar: the current tab is a filled pill with its icon and name; the others show
 * their icon only. Each slot is a full fifth of the width and the bar's full height.
 */
@Composable
private fun BottomBar(current: Tab, onPick: (Tab) -> Unit) {
    val hasUpdate = AppUpdate.available.collectAsState().value != null
    Column(Modifier.fillMaxWidth().background(Palette.bg)) {
        HorizontalDivider(color = Palette.line, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in Tab.entries) {
                val on = t == current
                val name = when (lang()) { "fr" -> t.fr; "de" -> t.de; else -> t.label }
                // The picked tab widens into a pill and its name slides out next to the icon.
                val weight by animateFloatAsState(if (on) 1.8f else 1f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "w")
                val pill by animateColorAsState(if (on) Palette.accent else Color.Transparent, tween(220), label = "pill")
                val tint by animateColorAsState(if (on) Palette.onAccent else Palette.faint, tween(220), label = "tint")
                // Each tap on a tab plays its icon's move (see TabIcons.kt).
                var plays by remember { mutableIntStateOf(0) }
                val press = remember { MutableInteractionSource() }
                val pressed by press.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "s")
                Box(
                    Modifier.weight(weight).fillMaxHeight()
                        .clickable(interactionSource = press, indication = null) { plays++; onPick(t) }
                        .semantics { role = Role.Tab; selected = on; contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier.scale(scale).height(44.dp).clip(RoundedCornerShape(22.dp)).background(pill)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            TabIcon(t, tint, plays, 21.dp)
                            // A new version is out: a dot on Settings.
                            if (t == Tab.Settings && hasUpdate) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(8.dp)
                                        .clip(CircleShape).background(if (on) Palette.onAccent else Palette.accent),
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = on,
                            enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 600f)) + fadeIn(tween(200, 60)),
                            exit = shrinkHorizontally(tween(160)) + fadeOut(tween(100)),
                        ) {
                            Row {
                                Spacer(Modifier.width(7.dp))
                                Text(name, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
