package com.cim.securecall.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF2EC),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF0E5A73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF2EC),
    onSecondaryContainer = Color(0xFF00201C),
    background = Color(0xFFF7FAF9),
    surface = Color(0xFFF7FAF9),
    surfaceVariant = Color(0xFFDDE7E4),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFA7F3E6),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF003547),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFA7F3E6),
    background = Color(0xFF0E1514),
    surface = Color(0xFF0E1514),
    surfaceVariant = Color(0xFF3F4947),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun KalloqTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
