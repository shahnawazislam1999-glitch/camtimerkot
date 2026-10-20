package com.presencetimer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF000000)
val Panel = Color(0xFF080808)
val Border = Color(0xFF222222)
val Muted = Color(0xFF8A8A8A)
val Accent = Color(0xFF7C4DFF)
val Green = Color(0xFF35D07F)
val RedC = Color(0xFFFF5570)
val BrandRed = Color(0xFFFF2D3F)
val Blue = Color(0xFF4DA3FF)

@Composable
fun PresenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, background = Bg, surface = Bg, onSurface = Color.White,
            onBackground = Color.White
        ),
        content = content
    )
}
