package com.pocketgps.logger.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Green = Color(0xFF1B5E20)
private val GreenLight = Color(0xFF4C8C4A)
private val Amber = Color(0xFFF57C00)
private val Slate = Color(0xFF37474F)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0B3D0F),
    secondary = Slate,
    secondaryContainer = Color(0xFFE0E7EA),
    tertiary = Amber,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2E5D30),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFB0BEC5),
    secondaryContainer = Color(0xFF455A64),
    tertiary = Color(0xFFFFB74D),
    error = Color(0xFFF2B8B5),
)

@Composable
fun PocketGpsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
