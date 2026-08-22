package com.justenough.planner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF315D51),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEAE4),
    background = Color(0xFFFBF8F1),
    surface = Color(0xFFFBF8F1),
    surfaceVariant = Color(0xFFEAE5DA),
)
private val DarkColors = darkColorScheme(primary = Color(0xFF97CDBC), background = Color(0xFF101412), surface = Color(0xFF101412))

@Composable
fun JustEnoughTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = MaterialTheme.typography, content = content)
}
