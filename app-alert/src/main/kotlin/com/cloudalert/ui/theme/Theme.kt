package com.cloudalert.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7043),
    secondary = Color(0xFFFF8A65),
    error = Color(0xFFFF5252),
    background = Color(0xFF12161C),
    surface = Color(0xFF1B2027),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFD84315),
    secondary = Color(0xFFF4511E),
    error = Color(0xFFC62828),
)

@Composable
fun CloudAlertTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
