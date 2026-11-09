package com.megwil.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MegwilColors = darkColorScheme(
    primary = Color(0xFF00E5FF),
    background = Color(0xFF0B0F19),
    surface = Color(0xFF111827),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun MegwilAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MegwilColors,
        content = content
    )
}
