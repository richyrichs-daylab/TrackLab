package com.example.audiomix.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomix.data.AudioFiles
import com.example.audiomix.data.AudioImport
import com.example.audiomix.data.Exporter
import com.example.audiomix.data.MixPlayer
import com.example.audiomix.data.MixRenderer
import com.example.audiomix.data.Project
import com.example.audiomix.data.ProjectEditor
import com.example.audiomix.data.StretchFiles
import com.example.audiomix.data.TrackType
import com.example.audiomix.data.formatBpm
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()

    val editor = remember(project.id) { ProjectEditor(project, onSave) }
    val timeline = remember { TimelineState(with(density) { 66.dp.toPx() }) }
    val peaks = remember(project.id) { mutableStateMapOf<String, ByteArray>() }
    val player = remember { MixPlayer() }

    var editingName by remember { mutableStateOf(false) }
    var nameText by remember(editingName) {
        mutableStateOf(
            TextFieldValue(editor.project.name, TextRange(editor.project.name.length))
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(editingName) { if (editingName) focus.requestFocus() }

    var editingBpm by remember { mutableStateOf(false) }
    var bpmText by remember(editingBpm) {
        mutableStateOf(
            TextFieldValue(editor.project.bpm.toString(), TextRange(editor.project.bpm.toString().length))
        )
    }
    val bpmFocus = remember { FocusRequester() }
    LaunchedEffect(editingBpm) { if (editingBpm) bpmFocus.requestFocus() }

    var playing by remember { mutableStateOf(false) }
    var restartKey by remember { mutableIntStateOf(0) }
    var plusMenu by remember { mutableStateOf(false) }
    var pendingBar by remember { mutableDoubleStateOf(0.0) }

    /** Fortschritt in Prozent, solange gearbeitet wird (null = nichts los). */
    var busyPercent by remember { mutableStateOf<Int?>(null) }

    /** Neues Projekt-Tempo, zu dem gerade noch eine Rückfrage offen ist. */
    var bpmQuestion by remember { mutableStateOf<Int?>(null) }

    var pendingImport by remember { mutableStateOf<AudioImport.Imported?>(null) }
    var importTempoText by remember { mutableStateOf("") }
    var importTarget by remember { mutableDoubleStateOf(100.0) }

    DisposableEffect(Unit) {
        onDispose { player.stop() }
    }

    fun speedRangeOk(targetBpm: Double, sourceBpm: Double): Boolean =
        targetBpm / sourceBpm in 0.25..4.0

    /** Räumt gedehnte Dateien weg, die nicht mehr gebraucht werden. */
    fun cleanupStretched() {
        val projectBpm = editor.project.bpm
        val keep = editor.tracks
            .filter { it.type == TrackType.AUDIO }
            .flatMap { t ->
                t.clips.mapNotNull { c ->
                    c.sourceFile?.let { StretchFiles.fileNameFor(it, t.tempo(projectBpm) / c.sourceBpm) }
                }
            }
            .toSet()
        scope.launch(Dispatchers.IO) { StretchFiles.cleanup(context, project.id, keep) }
    }

    /** Erzeugt die gedehnten Dateien (mit Lade-Overlay) und führt danach [onSuccess] aus. */
    fun runStretchJob(needed: List<Pair<String, Double>>, onSuccess: () -> Unit) {
        busyPercent = 0
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                var done = 0
                var allOk = true
                for (item in needed) {
                    val file = StretchFiles.ensure(
                        context,
                        project.id,
                        item.first,
                        item.second,
                        { f -> busyPercent = ((done + f) / needed.size * 100f).toInt() },
                        { !isActive }
                    )
                    if (file == null) {
                        allOk = false
                        break
                    }
                    done++
                }
                allOk
            }
            if (ok) {
                onSuccess()
                cleanupStretched()
            } else {
                Toast.makeText(context, "Die Änderung ist fehlgeschlagen", Toast.LENGTH_LONG).show()
            }
            busyPercent = null
        }
    }

    // ---- Projekt-BPM ändern ----
    fun runBpmChange(newBpm: Int, includeDiffering: Boolean) {
        playing = false
        val projectBpm = editor.project.bpm
        val p = projectBpm.toDouble()
        val changed = editor.tracks.filter {
            it.type == TrackType.AUDIO &&
                (includeDiffering || abs(it.tempo(projectBpm) - p) < 0.0005)
        }
        val changedIds = changed.map { it.id }.toSet()
        val clips = changed.flatMap { it.clips }.filter { it.sourceFile != null }

        if (clips.isNotEmpty()) {
            val lo = clips.maxOf { it.sourceBpm * 0.25 }
            val hi = clips.minOf { it.sourceBpm * 4.0 }
            if (newBpm < lo || newBpm > hi) {
                val low = maxOf(6, ceil(lo).toInt())
                val high = minOf(400, floor(hi).toInt())
                Toast.makeText(
                    context,
                    "Mit den betroffenen Audio-Dateien sind nur $low–$high bpm möglich",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        val needed = clips
            .map { Pair(it.sourceFile!!, newBpm / it.sourceBpm) }
            .distinctBy { "${it.first}|${StretchFiles.key(it.second)}" }

        if (needed.isEmpty()) {
            editor.applyProjectBpm(newBpm, changedIds)
            cleanupStretched()
            return
        }
        runStretchJob(needed) { editor.applyProjectBpm(newBpm, changedIds) }
    }

    fun startBpmChange(newBpm: Int) {
        if (newBpm == editor.project.bpm) return
        val projectBpm = editor.project.bpm
        val p = projectBpm.toDouble()
        val hasDiffering = editor.tracks.any {
            it.type == TrackType.AUDIO && abs(it.tempo(projectBpm) - p) > 0.0005
        }
        if (hasDiffering) {
            bpmQuestion = newBpm
        } else {
            runBpmChange(newBpm, false)
        }
    }

    fun commitBpmField() {
        editingBpm = false
        val typed = bpmText.text.toIntOrNull() ?: return
        val value = typed.coerceIn(6, 400)
        if (value != typed) {
            Toast.makeText(context, "BPM auf 6–400 begrenzt", Toast.LENGTH_SHORT).show()
        }
        startBpmChange(value)
    }

    // ---- BPM einer einzelnen Zeile ändern ----
    fun commitRowBpm(trackId: String, text: String) {
        val typed = text.replace(',', '.').toDoubleOrNull() ?: return
        val value = typed.coerceIn(6.0, 400.0)
        val track = editor.tracks.firstOrNull { it.id == trackId } ?: return
        if (abs(value - track.tempo(editor.project.bpm)) < 0.0005) return
        playing = false

        val clips = track.clips.filter { it.sourceFile != null }
        if (clips.isNotEmpty()) {
            val lo = clips.maxOf { it.sourceBpm * 0.25 }
            val hi = clips.minOf { it.sourceBpm * 4.0 }
            if (value < lo || value > hi) {
                val low = maxOf(6, ceil(lo).toInt())
                val high = minOf(400, floor(hi).toInt())
                Toast.makeText(
                    context,
                    "Für diese Zeile sind nur $low–$high bpm möglich",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        val needed = clips
            .map { Pair(it.sourceFile!!, value / it.sourceBpm) }
            .distinctBy { "${it.first}|${StretchFiles.key(it.second)}" }

        if (needed.isEmpty()) {
            editor.applyRowBpm(trackId, value)
            cleanupStretched()
            return
        }
        runStretchJob(needed) { editor.applyRowBpm(trackId, value) }
    }

    // ---- MP3 importieren (nach der Tempo-Frage) ----
    fun confirmImport(imported: AudioImport.Imported, adapt: Boolean) {
        val projectBpm = editor.project.bpm
        val selected = editor.tracks.firstOrNull {
            it.id == editor.selectedTrackId && it.type == TrackType.AUDIO
        }
        val typed = importTempoText.replace(',', '.').toDoubleOrNull()
        if (typed == null || typed < 6.0 || typed > 400.0) {
            Toast.makeText(context, "Bitte ein Tempo zwischen 6 und 400 eingeben", Toast.LENGTH_SHORT).show()
            return
        }

        val rowBpm: Double
        val join: Boolean
        if (adapt) {
            if (!speedRangeOk(importTarget, typed)) {
                Toast.makeText(context, "Das Tempo weicht zu stark ab (höchstens Faktor 4)", Toast.LENGTH_LONG).show()
                return
            }
            rowBpm = importTarget
            join = selected != null
        } else {
            rowBpm = typed
            join = selected != null && abs(selected.tempo(projectBpm) - typed) < 0.0005
            if (selected != null && !join) {
                Toast.makeText(
                    context,
                    "Anderes Tempo als die gewählte Zeile: Die Datei kommt in eine neue Zeile",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        pendingImport = null

        val startBar = pendingBar
        val speed = rowBpm / typed
        if (StretchFiles.isOriginal(speed)) {
            editor.addAudioClip(imported.fileName, imported.durationSec, startBar, typed, rowBpm, join)
            return
        }
        runStretchJob(listOf(Pair(imported.fileName, speed))) {
            editor.addAudioClip(imported.fileName, imported.durationSec, startBar, typed, rowBpm, join)
        }
    }

    // Dateien-App für den MP3-Import
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                Toast.makeText(context, "MP3 wird importiert …", Toast.LENGTH_SHORT).show()
                val imported = withContext(Dispatchers.IO) {
                    AudioImport.importMp3(context, uri, project.id)
                }
                if (imported == null) {
                    Toast.makeText(
                        context,
                        "Diese Datei konnte nicht gelesen werden (nur MP3 möglich)",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val selected = editor.tracks.firstOrNull {
                        it.id == editor.selectedTrackId && it.type == TrackType.AUDIO
                    }
                    importTarget = selected?.tempo(editor.project.bpm) ?: editor.project.bpm.toDouble()
                    importTempoText = formatBpm(importTarget)
                    pendingImport = imported
                }
            }
        }
    }

    // Wellenform-Daten der Audio-Clips laden
    LaunchedEffect(editor.project.tracks) {
        val needed = editor.tracks
            .flatMap { it.clips }
            .mapNotNull { it.sourceFile }
            .distinct()
            .filter { it !in peaks }
        for (name in needed) {
            val data = withContext(Dispatchers.IO) {
                AudioFiles.loadPeaks(context, project.id, name)
            }
            if (data != null) peaks[name] = data
        }
    }

    // Abspielen: Ton läuft, der Cursor folgt dem Ton
    LaunchedEffect(playing, restartKey) {
        if (playing) {
            val renderer = MixRenderer(context, project.id, editor.tracks, editor.project.bpm)
            try {
                val ok = withContext(Dispatchers.IO) { renderer.prepare() }
                if (!ok) {
                    Toast.makeText(context, "Eine Audio-Datei fehlt oder ist unlesbar", Toast.LENGTH_LONG).show()
                    playing = false
                    return@LaunchedEffect
                }
                val startBar = editor.cursorBar
                player.start(renderer, (startBar * renderer.framesPerBar).toLong())
                var lastSet = startBar
                while (true) {
                    withFrameNanos { }
                    if (editor.cursorBar != lastSet) {
                        // Der Nutzer hat den Cursor verschoben: von dort aus neu starten
                        restartKey += 1
                        return@LaunchedEffect
                    }
                    val newBar = minOf(
                        startBar + player.playedFrames() / renderer.framesPerBar,
                        editor.endBar()
                    )
                    editor.cursorBar = newBar
                    lastSet = newBar
                    if (player.finished) {
                        editor.cursorBar = editor.endBar()
                        playing = false
                        return@LaunchedEffect
                    }
                }
            } finally {
                player.stop()
                renderer.close()
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
        // ---- Titelzeile (bleibt auch während des Ladens bedienbar) ----
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

        Box(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {

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

                    if (editingBpm) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = bpmText,
                                onValueChange = { v ->
                                    if (v.text.length <= 3 && v.text.all { it.isDigit() }) bpmText = v
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = Color(0xFFE8607A),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black
                                ),
                                cursorBrush = SolidColor(Color(0xFFE8607A)),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { commitBpmField() }),
                                modifier = Modifier
                                    .width(40.dp)
                                    .focusRequester(bpmFocus)
                            )
                            Text(
                                text = " bpm",
                                color = Color(0xFFE8607A),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    } else {
                        PillButton(
                            text = "${editor.project.bpm} bpm",
                            onClick = { editingBpm = true },
                            onLongClick = { editingBpm = true }
                        )
                    }

                    PillButton(
                        text = "ex.",
                        onClick = {
                            if (editor.endBar() <= 0.0) {
                                Toast.makeText(context, "Noch kein Audio vorhanden", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Export läuft …", Toast.LENGTH_SHORT).show()
                                val snapshot = editor.project
                                scope.launch {
                                    val name = withContext(Dispatchers.IO) { Exporter.export(context, snapshot) }
                                    val message = if (name != null) {
                                        "Gespeichert in Downloads: $name"
                                    } else {
                                        "Export fehlgeschlagen"
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                    Box {
                        PlaceholderButton(label = "+", size = 44.dp, onClick = { plusMenu = true })
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Dateien") },
                                onClick = {
                                    plusMenu = false
                                    pendingBar = editor.cursorBar
                                    picker.launch(arrayOf("audio/*"))
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
                Timeline(
                    editor = editor,
                    state = timeline,
                    peaks = peaks,
                    modifier = Modifier.weight(1f)
                )

                // ---- Unterer Bereich: Zeilen-Leiste, Mülleimer, Play/Pause ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.timelineBg)
                ) {
                    val selectedRow = editor.tracks.withIndex()
                        .firstOrNull { it.value.id == editor.selectedTrackId }
                    if (selectedRow != null) {
                        val row = selectedRow.value
                        RowInfoBar(
                            trackId = row.id,
                            name = row.displayName(selectedRow.index),
                            bpmText = formatBpm(row.tempo(editor.project.bpm)),
                            bpmEditable = row.type == TrackType.AUDIO,
                            onRename = { editor.renameTrack(row.id, it) },
                            onBpmCommit = { commitRowBpm(row.id, it) },
                            onBpmLocked = {
                                Toast.makeText(
                                    context,
                                    "Trommeln folgen immer dem Projekt-Tempo",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
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
                                            Toast.makeText(
                                                context,
                                                "Noch kein Audio vorhanden",
                                                Toast.LENGTH_SHORT
                                            ).show()
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
            }

            // ---- Lade-Overlay: blockiert die Bearbeitung, der Zurück-Pfeil oben bleibt aktiv ----
            val percent = busyPercent
            if (percent != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background.copy(alpha = 0.82f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$percent %",
                            color = colors.onBackground,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Beim Schließen vor dem Ende der Bearbeitung bricht die BPM-Änderung ab.",
                            color = colors.onBackground,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 40.dp)
                        )
                    }
                }
            }
        }
    }

    // ---- Rückfrage: abweichende Zeilen beim Ändern der Projekt-BPM ----
    val question = bpmQuestion
    if (question != null) {
        val projectBpm = editor.project.bpm
        val differing = editor.tracks.withIndex().filter {
            it.value.type == TrackType.AUDIO &&
                abs(it.value.tempo(projectBpm) - projectBpm) > 0.0005
        }
        val questionText = if (differing.size == 1) {
            "Soll die BPM von „${differing[0].value.displayName(differing[0].index)}“ auch geändert werden?"
        } else {
            "Soll die BPM von mehreren Musikzeilen auch geändert werden?"
        }
        AlertDialog(
            onDismissRequest = { bpmQuestion = null },
            title = { Text("BPM auf $question ändern") },
            text = { Text(questionText) },
            confirmButton = {
                TextButton(onClick = {
                    bpmQuestion = null
                    runBpmChange(question, true)
                }) { Text("Ja") }
            },
            dismissButton = {
                TextButton(onClick = {
                    bpmQuestion = null
                    runBpmChange(question, false)
                }) { Text("Nein") }
            }
        )
    }

    // ---- Tempo-Frage nach dem MP3-Import ----
    val pending = pendingImport
    if (pending != null) {
        AlertDialog(
            onDismissRequest = {
                pendingImport = null
                scope.launch(Dispatchers.IO) {
                    StretchFiles.deleteAll(context, project.id, pending.fileName)
                }
            },
            title = { Text("An das Tempo anpassen?") },
            text = {
                Column {
                    Text(
                        "Soll die Datei an das vorhandene Tempo (${formatBpm(importTarget)} bpm) " +
                            "angepasst werden? „Nein“ lässt sie im Originaltempo. " +
                            "Außerhalb tippen bricht den Import ab."
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importTempoText,
                        onValueChange = { importTempoText = it },
                        label = { Text("Tempo der Datei (bpm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmImport(pending, true) }) { Text("Ja, anpassen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport(pending, false) }) { Text("Nein") }
            }
        )
    }
}
