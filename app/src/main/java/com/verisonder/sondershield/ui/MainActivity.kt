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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Palette.bg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Palette.card.toArgb()),
        )
        if (intent?.getBooleanExtra(EXTRA_TURN_ON, false) == true) turnOn()

        setContent {
            ShieldTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.Home) }
                BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }
                Scaffold(
                    containerColor = Palette.bg,
                    bottomBar = {
                        NavigationBar(containerColor = Palette.card, tonalElevation = 0.dp) {
                            for (t in Tab.entries) {
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(t.icon(), null, modifier = Modifier.size(22.dp)) },
                                    label = { Text(t.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Palette.accent, selectedTextColor = Palette.accent,
                                        indicatorColor = Palette.accentSoft,
                                        unselectedIconColor = Palette.dim, unselectedTextColor = Palette.dim,
                                    ),
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().background(Palette.bg).padding(pad)) {
                        when (tab) {
                            Tab.Home -> HomeScreen(onToggle = ::toggle, goTo = { tab = it })
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

private fun Tab.icon() = when (this) {
    Tab.Home -> Icons.home
    Tab.Log -> Icons.log
    Tab.Rules -> Icons.rules
    Tab.Apps -> Icons.apps
    Tab.Settings -> Icons.settings
}
