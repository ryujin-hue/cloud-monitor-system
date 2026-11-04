package com.cloudmonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    secondary = Color(0xFF80DEEA),
    error = Color(0xFFFF7043),
    background = Color(0xFF12161C),
    surface = Color(0xFF1B2027),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00838F),
    secondary = Color(0xFF0097A7),
    error = Color(0xFFD84315),
)

@Composable
fun CloudMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
