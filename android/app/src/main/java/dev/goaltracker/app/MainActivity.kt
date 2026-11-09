package dev.goaltracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Store(applicationContext)
        setContent {
            JarvestTheme { App(store) }
        }
    }

    override fun onStart() {
        super.onStart()
        store.runAutoDelete()
    }
}

@Composable
private fun App(store: Store) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(settingsOpen) { settingsOpen = false }
    if (settingsOpen) SettingsScreen(store, onBack = { settingsOpen = false })
    else ListScreen(store, onSettings = { settingsOpen = true })
}
