package com.pocketgps.logger.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.pocketgps.logger.ui.AppRoot
import com.pocketgps.logger.ui.theme.PocketGpsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge via WindowCompat (core), same effect as enableEdgeToEdge().
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            PocketGpsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}
