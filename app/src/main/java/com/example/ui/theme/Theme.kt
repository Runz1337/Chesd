package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ElegantDarkColorScheme = darkColorScheme(
    primary = ElegantPrimary,
    background = ElegantBackground,
    surface = ElegantSurface,
    surfaceVariant = ElegantSurfaceVariant,
    onBackground = ElegantOnBackground,
    onSurface = ElegantOnBackground,
    onSurfaceVariant = ElegantOnBackground,
    outline = ElegantOutline,
    outlineVariant = ElegantOutlineVariant,
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ElegantDarkColorScheme,
        typography = Typography,
        content = content
    )
}
