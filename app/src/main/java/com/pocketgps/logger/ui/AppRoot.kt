package com.pocketgps.logger.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketgps.logger.ServiceLocator
import com.pocketgps.logger.ui.main.MainScreen
import com.pocketgps.logger.ui.savedlocations.SavedLocationsScreen
import com.pocketgps.logger.ui.settings.SettingsScreen

object Routes {
    const val SETUP = "setup"
    const val MAIN = "main"
    const val SAVED = "saved"
    const val SETTINGS = "settings"
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    val startDestination = remember {
        val modelReady = ServiceLocator.modelManager.isReady()
        val micGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val locationGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (modelReady && micGranted && locationGranted) Routes.MAIN else Routes.SETUP
    }

    val showBottomBar = route == Routes.MAIN ||
        route == Routes.SAVED ||
        route == Routes.SETTINGS

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.MAIN,
                        onClick = { navController.navigateTopLevel(Routes.MAIN) },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Main") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.SAVED,
                        onClick = { navController.navigateTopLevel(Routes.SAVED) },
                        icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                        label = { Text("Saved") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.SETTINGS,
                        onClick = { navController.navigateTopLevel(Routes.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SETUP) {
                SetupScreen(
                    onDone = {
                        navController.navigate(Routes.MAIN) {
                            // Clear the whole back stack (works whether setup was the start
                            // destination or pushed from Settings).
                            popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.MAIN) {
                MainScreen(
                    onOpenSetup = {
                        navController.navigate(Routes.SETUP) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.SAVED) {
                SavedLocationsScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenSetup = {
                        navController.navigate(Routes.SETUP) { launchSingleTop = true }
                    },
                )
            }
        }
    }
}

private fun androidx.navigation.NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(Routes.MAIN) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
