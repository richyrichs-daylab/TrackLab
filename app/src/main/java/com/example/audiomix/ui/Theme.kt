package com.example.audiomix.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class AppColors(
    val background: Color,
    val onBackground: Color,
    val card: Color,
    val cardText: Color
)

val LightColors = AppColors(
    background = Color.White,
    onBackground = Color.Black,
    card = Color(0xFFCCCCCC),
    cardText = Color(0xFF0044CC)
)

val DarkColors = AppColors(
    background = Color.Black,
    onBackground = Color.White,
    card = Color(0xFF2B2B2B),
    cardText = Color(0xFF8FB1FF)
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

@Composable
fun AudioMixTheme(darkMode: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkMode) DarkColors else LightColors
    val view = LocalView.current

    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkMode
        }
    }

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkMode) darkColorScheme() else lightColorScheme(),
            content = content
        )
    }
}
