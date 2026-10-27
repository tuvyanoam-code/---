@file:OptIn(ExperimentalLayoutApi::class)

package com.tuvyanoam.soundpilot.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.container
import com.tuvyanoam.soundpilot.service.AutomationService
import com.tuvyanoam.soundpilot.ui.screens.AssistantScreen
import com.tuvyanoam.soundpilot.ui.screens.HomeScreen
import com.tuvyanoam.soundpilot.ui.screens.LearnScreen
import com.tuvyanoam.soundpilot.ui.screens.PlacesScreen
import com.tuvyanoam.soundpilot.ui.screens.RulesScreen
import com.tuvyanoam.soundpilot.ui.screens.SettingsScreen
import com.tuvyanoam.soundpilot.ui.theme.SoundPilotTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var requestedTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedTab = intent?.getStringExtra(EXTRA_TAB)
        setContent {
            SoundPilotTheme {
                AppRoot(requestedTab) { requestedTab = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab = intent.getStringExtra(EXTRA_TAB)
    }

    override fun onStart() {
        super.onStart()
        val app = container
        lifecycleScope.launch {
            // While the app is visible Android lets the service add the microphone; also catch up on location.
            if (app.storage.settings.get().automationEnabled) AutomationService.start(this@MainActivity, AutomationService.ACTION_REFRESH)
            if (!app.geofences.sync()) app.geofences.refreshPresence()
            app.coordinator.evaluate("app_opened")
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = "home"
        const val TAB_RULES = "rules"
        const val TAB_PLACES = "places"
        const val TAB_ASSISTANT = "assistant"
        const val TAB_LEARN = "learn"
    }
}

private data class Tab(val id: String, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(MainActivity.TAB_HOME, R.string.tab_home, Icons.Filled.Home),
    Tab(MainActivity.TAB_RULES, R.string.tab_rules, Icons.Filled.Tune),
    Tab(MainActivity.TAB_PLACES, R.string.tab_places, Icons.Filled.Place),
    Tab(MainActivity.TAB_ASSISTANT, R.string.tab_assistant, Icons.AutoMirrored.Filled.Chat),
    Tab(MainActivity.TAB_LEARN, R.string.tab_learn, Icons.Filled.Lightbulb),
)

@Composable
private fun AppRoot(requestedTab: String?, onTabConsumed: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(MainActivity.TAB_HOME) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            if (tabs.any { it.id == requestedTab }) tab = requestedTab
            settingsOpen = false
            onTabConsumed()
        }
    }

    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        SettingsScreen(onBack = { settingsOpen = false })
        return
    }
    if (tab != MainActivity.TAB_HOME) BackHandler { tab = MainActivity.TAB_HOME }

    val openSettings = { settingsOpen = true }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Hidden while typing so the keyboard sits right under the text field.
            if (!WindowInsets.isImeVisible) {
                NavigationBar {
                    tabs.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item.id,
                            onClick = { tab = item.id },
                            icon = { Icon(item.icon, null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                MainActivity.TAB_RULES -> RulesScreen(openSettings)
                MainActivity.TAB_PLACES -> PlacesScreen(openSettings)
                MainActivity.TAB_ASSISTANT -> AssistantScreen(openSettings)
                MainActivity.TAB_LEARN -> LearnScreen(openSettings)
                else -> HomeScreen(openSettings, openTab = { tab = it })
            }
        }
    }
}
