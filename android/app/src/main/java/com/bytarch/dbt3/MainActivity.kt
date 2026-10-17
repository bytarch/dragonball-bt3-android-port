package com.bytarch.dbt3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Texture
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bytarch.dbt3.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bytarch.dbt3.ui.components.BusyBar
import com.bytarch.dbt3.ui.components.kiBackground
import com.bytarch.dbt3.ui.screens.BattlePassScreen
import com.bytarch.dbt3.ui.screens.BootScreen
import com.bytarch.dbt3.ui.screens.CheatsScreen
import com.bytarch.dbt3.ui.screens.ControlsScreen
import com.bytarch.dbt3.ui.screens.HomeScreen
import com.bytarch.dbt3.ui.screens.SettingsScreen
import com.bytarch.dbt3.ui.screens.UpdateDialog
import com.bytarch.dbt3.ui.theme.Dbt3Theme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A launcher for a game, not an app: the phone's own bars stay out of the way and come back on a swipe.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val ui by vm.uiState.collectAsStateWithLifecycle()
            Dbt3Theme(mode = ui.theme, dynamicColor = ui.dynamicColor) {
                Dbt3App(vm)
            }
        }
    }
}

private enum class Dest(val route: String, val label: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Rounded.Home),
    BATTLEPASS("battlepass", R.string.nav_battlepass, Icons.Rounded.Extension),
    CONTROLS("controls", R.string.nav_controls, Icons.Rounded.SportsEsports),
    CHEATS("cheats", R.string.nav_cheats, Icons.Rounded.LockOpen),
    SETTINGS("settings", R.string.nav_settings, Icons.Rounded.Settings),
}

@Composable
private fun Dbt3App(vm: AppViewModel) {
    val boot by vm.boot.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.runBoot() }
    if (!boot.done) {
        BootScreen(boot)
        return
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // files may have been changed from outside (file manager, adb) while the app was in the background
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAll(); vm.resumeUpdate() }
    UpdateDialog(vm)

    fun go(d: Dest) = nav.navigate(d.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.background(colors.background).kiBackground(colors.primary, colors.secondary),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { BusyBar(busy) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            NavHost(
                nav, startDestination = Dest.HOME.route,
                modifier = Modifier.widthIn(max = 1100.dp).fillMaxSize(),
            ) {
                composable(Dest.HOME.route) {
                    HomeScreen(
                        vm,
                        onOpenBattlePass = { go(Dest.BATTLEPASS) },
                        onOpenControls = { go(Dest.CONTROLS) },
                        onOpenCheats = { go(Dest.CHEATS) },
                        onOpenSettings = { go(Dest.SETTINGS) },
                    )
                }
                // there is no dock: every screen but the lobby carries its own way back to it
                composable(Dest.BATTLEPASS.route) { BattlePassScreen(vm) { go(Dest.HOME) } }
                composable(Dest.CONTROLS.route) { ControlsScreen(vm) { go(Dest.HOME) } }
                composable(Dest.CHEATS.route) { CheatsScreen(vm) { go(Dest.HOME) } }
                composable(Dest.SETTINGS.route) { SettingsScreen(vm) { go(Dest.HOME) } }
            }
        }
    }
}
