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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
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
import com.verisonder.sondershield.service.ShieldVpnService

enum class Tab(val label: String) { Home("Home"), Log("Log"), Rules("Rules"), Apps("Apps"), Settings("Settings") }

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TURN_ON = "turn_on"
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            ShieldVpnService.start(this)
        } else {
            ShieldState.problem.value = "VPN permission was not given. SonderShield needs it to filter DNS on this phone."
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShieldState.init(this)
        if (intent?.getBooleanExtra(EXTRA_TURN_ON, false) == true) turnOn()

        setContent {
            val themeId by ShieldState.theme.collectAsState()
            val colors = colorsFor(ThemeMode.of(themeId))
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
                var tab by rememberSaveable { mutableStateOf(Tab.Home) }
                BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }
                Scaffold(
                    containerColor = Palette.bg,
                    bottomBar = { BottomBar(tab) { tab = it } },
                ) { pad ->
                    Box(Modifier.fillMaxSize().background(Palette.bg).padding(pad)) {
                        when (tab) {
                            Tab.Home -> HomeScreen(onToggle = ::toggle)
                            Tab.Log -> LogScreen()
                            Tab.Rules -> RulesScreen()
                            Tab.Apps -> AppsScreen()
                            Tab.Settings -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TURN_ON, false)) turnOn()
    }

    private fun toggle() {
        if (ShieldState.phase.value == com.verisonder.sondershield.core.Phase.OFF) turnOn() else ShieldVpnService.stop(this)
    }

    private fun turnOn() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else ShieldVpnService.start(this)
    }
}

/**
 * Bottom bar: the current tab is a filled pill with its icon and name; the others show
 * their icon only. Each slot is a full fifth of the width and the bar's full height.
 */
@Composable
private fun BottomBar(current: Tab, onPick: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.bg)) {
        HorizontalDivider(color = Palette.line, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in Tab.entries) {
                val on = t == current
                Box(
                    Modifier.weight(if (on) 1.7f else 1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(t) }
                        .semantics { role = Role.Tab; selected = on; contentDescription = t.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
                            .background(if (on) Palette.accent else Color.Transparent)
                            .padding(horizontal = if (on) 14.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(t.icon(), null, tint = if (on) Palette.onAccent else Palette.faint, modifier = Modifier.size(21.dp))
                        if (on) {
                            Spacer(Modifier.width(7.dp))
                            Text(t.label, color = Palette.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun Tab.icon() = when (this) {
    Tab.Home -> Icons.home
    Tab.Log -> Icons.log
    Tab.Rules -> Icons.rules
    Tab.Apps -> Icons.apps
    Tab.Settings -> Icons.settings
}
