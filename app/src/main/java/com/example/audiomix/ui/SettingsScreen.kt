package com.example.audiomix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(darkMode: Boolean, onDarkModeChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Einstellungen",
            color = colors.onBackground,
            fontSize = 40.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "Dark mode?",
            style = TextStyle(
                brush = Brush.horizontalGradient(
                    listOf(Color(0xFFE8607A), Color(0xFF8E2C75))
                ),
                fontSize = 32.sp,
                fontWeight = FontWeight.Black
            )
        )
        Spacer(Modifier.height(16.dp))
        Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
        Spacer(Modifier.weight(1.4f))
    }
}
