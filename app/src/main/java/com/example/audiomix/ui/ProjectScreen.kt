package com.example.audiomix.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.audiomix.data.Project
import com.example.audiomix.data.ProjectEditor

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectScreen(
    project: Project,
    onSave: (Project) -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val density = LocalDensity.current

    val editor = remember(project.id) { ProjectEditor(project, onSave) }
    val timeline = remember { TimelineState(with(density) { 66.dp.toPx() }) }

    var editingName by remember { mutableStateOf(false) }
    var nameText by remember(editingName) {
        mutableStateOf(
            TextFieldValue(editor.project.name, TextRange(editor.project.name.length))
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(editingName) { if (editingName) focus.requestFocus() }

    var playing by remember { mutableStateOf(false) }
    var plusMenu by remember { mutableStateOf(false) }
    var showBpm by remember { mutableStateOf(false) }

    // Abspielen: der Cursor läuft passend zur BPM-Zahl (Ton folgt in einem späteren Schritt)
    LaunchedEffect(playing) {
        if (playing) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val secondsPerBar = 4 * 60.0 / editor.project.bpm
                editor.cursorBar += (now - last) / 1_000_000_000.0 / secondsPerBar
                last = now
                val end = editor.endBar()
                if (editor.cursorBar >= end) {
                    editor.cursorBar = end
                    playing = false
                    break
                }
            }
        }
    }

    val division = timeline.division(density)
    val gridLabel = if (division == 1) "1" else "1/$division"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
    ) {
        // ---- Titelzeile ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (editingName) {
                    BasicTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = colors.onBackground,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(colors.onBackground),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            editingName = false
                            editor.rename(nameText.text)
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                    )
                } else {
                    Text(
                        text = editor.project.name,
                        color = colors.onBackground,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { editingName = true }
                        )
                    )
                }
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

        // ---- Steuerzeile ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "<",
                color = colors.onBackground.copy(alpha = if (editor.canUndo) 1f else 0.25f),
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .clickable(enabled = editor.canUndo) { editor.undo() }
                    .padding(horizontal = 8.dp)
            )
            Text(
                text = ">",
                color = colors.onBackground.copy(alpha = if (editor.canRedo) 1f else 0.25f),
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .clickable(enabled = editor.canRedo) { editor.redo() }
                    .padding(horizontal = 8.dp)
            )
            Text(
                text = gridLabel,
                color = colors.onBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.weight(1f))
            PillButton(
                text = "${editor.project.bpm} bpm",
                onClick = {},
                onLongClick = { showBpm = true }
            )
            PillButton(
                text = "ex.",
                onClick = {
                    val message = if (editor.endBar() <= 0.0) {
                        "Noch kein Audio vorhanden"
                    } else {
                        "Export folgt im nächsten Schritt"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            )
            Box {
                PlaceholderButton(label = "+", size = 44.dp, onClick = { plusMenu = true })
                DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Dateien") },
                        onClick = {
                            plusMenu = false
                            Toast.makeText(
                                context,
                                "MP3-Import kommt im nächsten Schritt",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Trommel") },
                        onClick = {
                            plusMenu = false
                            editor.addDrumClip()
                        }
                    )
                }
            }
        }

        // ---- Zeitleiste ----
        Timeline(editor = editor, state = timeline, modifier = Modifier.weight(1f))

        // ---- Unterer Bereich: Mülleimer + Play/Pause ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(colors.timelineBg)
        ) {
            if (editor.selectedTrackId != null) {
                Text(
                    text = "🗑️",
                    fontSize = 44.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                        .clickable { editor.deleteSelection() }
                        .padding(8.dp)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .clickable {
                        if (playing) {
                            playing = false
                        } else {
                            val end = editor.endBar()
                            if (end <= 0.0) {
                                Toast.makeText(context, "Noch kein Audio vorhanden", Toast.LENGTH_SHORT).show()
                            } else {
                                if (editor.cursorBar >= end) editor.cursorBar = 0.0
                                playing = true
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (playing) "⏸" else "▶",
                        fontSize = 40.sp,
                        color = if (playing) Color(0xFFFF9800) else Color(0xFFB02060)
                    )
                }
            }
        }
    }

    if (showBpm) {
        Dialog(onDismissRequest = { showBpm = false }) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(horizontal = 40.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${editor.project.bpm} bpm",
                    color = Color.Black,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
