package com.example.audiomix.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowInfoBar(
    trackId: String,
    name: String,
    bpmText: String,
    bpmEditable: Boolean,
    onRename: (String) -> Unit,
    onBpmCommit: (String) -> Unit,
    onBpmLocked: () -> Unit,
    onDuplicate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current

    var editingName by remember(trackId) { mutableStateOf(false) }
    var nameValue by remember(trackId, editingName) {
        mutableStateOf(TextFieldValue(name, TextRange(name.length)))
    }
    var editingBpm by remember(trackId) { mutableStateOf(false) }
    var bpmValue by remember(trackId, editingBpm) {
        mutableStateOf(TextFieldValue(bpmText, TextRange(bpmText.length)))
    }
    val nameFocus = remember { FocusRequester() }
    val bpmFocus = remember { FocusRequester() }
    LaunchedEffect(trackId, editingName) { if (editingName) nameFocus.requestFocus() }
    LaunchedEffect(trackId, editingBpm) { if (editingBpm) bpmFocus.requestFocus() }

    val style = TextStyle(
        color = colors.onBackground,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
    )
    val dimmedStyle = style.copy(color = colors.onBackground.copy(alpha = 0.55f))
    val smallStyle = TextStyle(
        color = colors.onBackground,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.rowBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- Name (links) ----
        if (editingName) {
            BasicTextField(
                value = nameValue,
                onValueChange = { nameValue = it },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(colors.onBackground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    editingName = false
                    onRename(nameValue.text)
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(nameFocus)
            )
        } else {
            Text(
                text = name,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = { editingName = true },
                        onLongClick = { editingName = true }
                    )
            )
        }

        Spacer(Modifier.width(10.dp))

        // ---- Duplizieren (links neben der BPM) ----
        Text(
            text = "Duplizieren",
            style = smallStyle,
            maxLines = 1,
            modifier = Modifier
                .clickable(onClick = onDuplicate)
                .padding(vertical = 4.dp)
        )

        Spacer(Modifier.width(12.dp))

        // ---- BPM (rechts) ----
        if (!bpmEditable) {
            Text(
                text = "$bpmText bpm",
                style = dimmedStyle,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onBpmLocked)
            )
        } else if (editingBpm) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = bpmValue,
                    onValueChange = { v ->
                        if (v.text.length <= 6 && v.text.all { it.isDigit() || it == '.' || it == ',' }) {
                            bpmValue = v
                        }
                    },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.onBackground),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        editingBpm = false
                        onBpmCommit(bpmValue.text)
                    }),
                    modifier = Modifier
                        .width(64.dp)
                        .focusRequester(bpmFocus)
                )
                Text(text = " bpm", style = style)
            }
        } else {
            Text(
                text = "$bpmText bpm",
                style = style,
                maxLines = 1,
                modifier = Modifier.combinedClickable(
                    onClick = { editingBpm = true },
                    onLongClick = { editingBpm = true }
                )
            )
        }
    }
}
