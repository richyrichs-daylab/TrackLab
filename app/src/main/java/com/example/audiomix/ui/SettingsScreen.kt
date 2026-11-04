package com.example.audiomix.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomix.data.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onBackupRestored: () -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val headingStyle = TextStyle(
        brush = Brush.horizontalGradient(
            listOf(Color(0xFFE8607A), Color(0xFF8E2C75))
        ),
        fontSize = 32.sp,
        fontWeight = FontWeight.Black
    )

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                Toast.makeText(context, "Backup wird eingelesen …", Toast.LENGTH_SHORT).show()
                val count = withContext(Dispatchers.IO) { Backup.restore(context, uri) }
                val message = when {
                    count < 0 -> "Das Backup konnte nicht gelesen werden"
                    count == 0 -> "Keine neuen Projekte gefunden"
                    count == 1 -> "1 Projekt wiederhergestellt"
                    else -> "$count Projekte wiederhergestellt"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                onBackupRestored()
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
    ) {
        val third = maxHeight / 3

        // ---- Titelzeile mit Zurück-Pfeil (wie auf der Projektseite) ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "Einstellungen",
                    color = colors.onBackground,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Text(
                text = "↑",
                color = colors.onBackground,
                fontSize = 40.sp,
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(8.dp)
            )
        }

        // ---- Dark mode: Mitte bei einem Drittel der Höhe ----
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = third)
                .graphicsLayer { translationY = -size.height / 2f },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Dark mode?", style = headingStyle)
            Spacer(Modifier.height(16.dp))
            Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
        }

        // ---- Backup: Mitte bei zwei Dritteln der Höhe ----
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = third * 2)
                .graphicsLayer { translationY = -size.height / 2f },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Backup", style = headingStyle)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Importieren",
                    color = colors.onBackground,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clickable {
                            picker.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream"
                                )
                            )
                        }
                        .padding(vertical = 8.dp)
                )
                Text(
                    text = " / ",
                    color = colors.onBackground,
                    fontSize = 24.sp
                )
                Text(
                    text = "Exportieren",
                    color = colors.onBackground,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clickable {
                            Toast.makeText(context, "Backup wird erstellt …", Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val name = withContext(Dispatchers.IO) { Backup.export(context) }
                                val message = if (name != null) {
                                    "Backup gespeichert in Downloads: $name"
                                } else {
                                    "Kein Backup erstellt (gibt es schon Projekte?)"
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}
