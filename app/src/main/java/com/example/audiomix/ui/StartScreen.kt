package com.example.audiomix.ui

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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomix.data.Project

@Composable
fun StartScreen(
    projects: List<Project>,
    onNewProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = LocalAppColors.current
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<Project?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFFDC4358), Color(0xFF77EBDC)))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "STARTSEITE",
                color = Color(0xFF00FFFF),
                fontSize = 40.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(24.dp))
            PlaceholderButton(label = "+", size = 110.dp, onClick = onNewProject)
            Spacer(Modifier.height(32.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        editing = editingId == project.id,
                        colors = colors,
                        onOpen = { onOpenProject(project.id) },
                        onStartEdit = { editingId = project.id },
                        onCommit = { newName ->
                            editingId = null
                            onRename(project.id, newName)
                        },
                        onDeleteClick = { deleteCandidate = project },
                        onExportClick = { onExport(project.id) }
                    )
                }
            }

            Text(
                text = "⚙️",
                fontSize = 44.sp,
                modifier = Modifier
                    .clickable(onClick = onOpenSettings)
                    .padding(16.dp)
            )
        }
    }

    deleteCandidate?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Projekt löschen?") },
            text = { Text("„${project.name}“ wird endgültig gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    editingId = null
                    onDelete(project.id)
                }) { Text("Löschen", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Abbrechen") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project,
    editing: Boolean,
    colors: AppColors,
    onOpen: () -> Unit,
    onStartEdit: () -> Unit,
    onCommit: (String) -> Unit,
    onDeleteClick: () -> Unit,
    onExportClick: () -> Unit
) {
    var text by remember(editing) {
        mutableStateOf(TextFieldValue(project.name, TextRange(project.name.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card)
            .combinedClickable(
                onClick = { if (!editing) onOpen() },
                onLongClick = { if (!editing) onStartEdit() }
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (editing) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.cardText, fontSize = 22.sp),
                cursorBrush = SolidColor(colors.cardText),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit(text.text) }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
            )
            Text(
                text = "🗑️",
                fontSize = 28.sp,
                modifier = Modifier
                    .clickable(onClick = onDeleteClick)
                    .padding(6.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "📁",
                fontSize = 28.sp,
                modifier = Modifier
                    .clickable(onClick = onExportClick)
                    .padding(6.dp)
            )
        } else {
            Text(
                text = project.name,
                color = colors.cardText,
                fontSize = 22.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatDuration(project.durationMs),
                color = colors.cardText,
                fontSize = 22.sp
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
