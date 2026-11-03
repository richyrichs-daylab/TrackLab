package com.example.audiomix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomix.data.ProjectEditor
import com.example.audiomix.data.Track
import com.example.audiomix.data.TrackType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

const val HANDLE_DP = 28
const val RULER_DP = 40
const val ROW_DP = 84
const val HEADER_DP = 64
const val MIN_LINE_DP = 16
const val MIN_BAR_DP = 24
const val MAX_BAR_DP = 600

/** Zoom (Pixel pro Takt) und seitliche Verschiebung der Zeitleiste. */
class TimelineState(initialBarPx: Float) {
    var barPx by mutableFloatStateOf(initialBarPx)
    var scrollPx by mutableFloatStateOf(0f)

    /** Wie fein das sichtbare Raster gerade ist: 1, 2, 4, 8 oder 16 Striche pro Takt. */
    fun division(density: Density): Int {
        val minLinePx = with(density) { MIN_LINE_DP.dp.toPx() }
        for (d in intArrayOf(16, 8, 4, 2)) {
            if (barPx / d >= minLinePx) return d
        }
        return 1
    }
}

fun snapBar(bar: Double, division: Int): Double =
    (round(bar * division) / division).coerceAtLeast(0.0)

@Composable
fun Timeline(editor: ProjectEditor, state: TimelineState, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val tracks = editor.tracks

    fun regionTap(off: Offset, rowsTop: Float, rowH: Float, long: Boolean) {
        val bar = ((off.x + state.scrollPx) / state.barPx).toDouble()
        if (off.y < rowsTop) {
            if (!long) editor.cursorBar = snapBar(bar, state.division(density))
            return
        }
        val index = ((off.y - rowsTop) / rowH).toInt()
        val track = editor.tracks.getOrNull(index)
        if (track == null) {
            editor.clearSelection()
            return
        }
        if (editor.selectedTrackId != track.id) {
            editor.selectTrack(track.id)
            return
        }
        if (long) return
        val clip = track.clips.firstOrNull { bar >= it.startBar && bar < it.endBar }
        if (clip != null && editor.selectedClipId != clip.id) {
            editor.selectClip(clip.id)
        } else {
            editor.selectClip(null)
        }
    }

    Row(modifier = modifier.fillMaxWidth().background(colors.timelineBg)) {

        // Kreise am Zeilenanfang (immer sichtbar)
        Column(Modifier.width(HEADER_DP.dp)) {
            Spacer(Modifier.height((HANDLE_DP + RULER_DP).dp))
            tracks.forEach { track ->
                TrackBadge(track, selected = track.id == editor.selectedTrackId)
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val rowsTop = (HANDLE_DP + RULER_DP).dp.toPx()
                        val rowH = ROW_DP.dp.toPx()
                        detectTapGestures(
                            onTap = { off -> regionTap(off, rowsTop, rowH, false) },
                            onLongPress = { off -> regionTap(off, rowsTop, rowH, true) }
                        )
                    }
                    .pointerInput(Unit) {
                        val minBar = MIN_BAR_DP.dp.toPx()
                        val maxBar = MAX_BAR_DP.dp.toPx()
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val newBar = (state.barPx * zoom).coerceIn(minBar, maxBar)
                            val factor = newBar / state.barPx
                            state.scrollPx =
                                ((state.scrollPx + centroid.x) * factor - centroid.x - pan.x)
                                    .coerceAtLeast(0f)
                            state.barPx = newBar
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val handleH = HANDLE_DP.dp.toPx()
                val rulerH = RULER_DP.dp.toPx()
                val rowH = ROW_DP.dp.toPx()
                val rowsTop = handleH + rulerH
                val barPx = state.barPx
                val scroll = state.scrollPx
                val div = state.division(this)
                val step = barPx / div
                val currentTracks = editor.tracks
                val rowsBottom = rowsTop + currentTracks.size * rowH

                // Hintergründe
                drawRect(colors.ruler, Offset(0f, handleH), Size(w, rulerH))
                if (currentTracks.isNotEmpty()) {
                    drawRect(colors.rowBg, Offset(0f, rowsTop), Size(w, rowsBottom - rowsTop))
                }
                currentTracks.forEachIndexed { r, t ->
                    val y = rowsTop + r * rowH
                    if (t.id == editor.selectedTrackId) {
                        drawRect(
                            colors.onBackground.copy(alpha = 0.10f),
                            Offset(0f, y),
                            Size(w, rowH)
                        )
                    }
                    drawLine(
                        colors.line.copy(alpha = 0.5f),
                        Offset(0f, y + rowH),
                        Offset(w, y + rowH),
                        1f
                    )
                }

                // Taktraster und Taktzahlen
                val first = floor(scroll / step).toInt().coerceAtLeast(0)
                val last = ceil((scroll + w) / step).toInt()
                for (i in first..last) {
                    val x = i * step - scroll
                    val isBar = i % div == 0
                    val tickTop = if (isBar) handleH else handleH + rulerH * 0.6f
                    drawLine(
                        colors.line,
                        Offset(x, tickTop),
                        Offset(x, rowsTop),
                        if (isBar) 2f else 1f
                    )
                    if (currentTracks.isNotEmpty()) {
                        drawLine(
                            colors.line.copy(alpha = if (isBar) 0.55f else 0.25f),
                            Offset(x, rowsTop),
                            Offset(x, rowsBottom),
                            1f
                        )
                    }
                    if (isBar) {
                        drawText(
                            textMeasurer,
                            (i / div + 1).toString(),
                            topLeft = Offset(x + 4.dp.toPx(), handleH + 3.dp.toPx()),
                            style = TextStyle(color = colors.rulerText, fontSize = 15.sp)
                        )
                    }
                }

                // Clips
                currentTracks.forEachIndexed { r, t ->
                    val y = rowsTop + r * rowH
                    t.clips.forEach { c ->
                        val left = (c.startBar * barPx - scroll).toFloat()
                        val width = (c.lengthBar * barPx).toFloat()
                        if (left + width < 0f || left > w) return@forEach
                        val base = if (t.type == TrackType.AUDIO) Color(0xFFE8C14B) else Color(0xFFF5A04A)
                        val color = if (c.id == editor.selectedClipId) lerp(base, Color.Gray, 0.4f) else base
                        drawRoundRect(
                            color,
                            Offset(left, y + 6.dp.toPx()),
                            Size(width, rowH - 12.dp.toPx()),
                            CornerRadius(12.dp.toPx())
                        )
                        if (t.type == TrackType.DRUM && barPx / 4f >= 8.dp.toPx()) {
                            var beat = ceil(c.startBar * 4.0 - 1e-9) / 4.0
                            while (beat < c.endBar - 1e-9) {
                                val hx = (beat * barPx - scroll).toFloat()
                                drawRoundRect(
                                    Color(0xFF7A4A1E),
                                    Offset(hx - 2.dp.toPx(), y + 24.dp.toPx()),
                                    Size(4.dp.toPx(), rowH - 48.dp.toPx()),
                                    CornerRadius(2.dp.toPx())
                                )
                                beat += 0.25
                            }
                        }
                    }
                }

                // Vertikalcursor mit Ziehgriff
                val cx = (editor.cursorBar * barPx - scroll).toFloat()
                if (cx >= -20.dp.toPx() && cx <= w + 20.dp.toPx()) {
                    val gradient = Brush.verticalGradient(
                        listOf(
                            Color(0xFFF0E0E0),
                            Color(0xFFE8607A),
                            Color(0xFF8E2C75),
                            Color(0xFF2A1A2E)
                        ),
                        startY = 0f,
                        endY = h
                    )
                    // Linie
                    drawRoundRect(
                        Color.Black,
                        Offset(cx - 4.dp.toPx(), handleH),
                        Size(8.dp.toPx(), h - handleH),
                        CornerRadius(4.dp.toPx())
                    )
                    drawRoundRect(
                        gradient,
                        Offset(cx - 1.75.dp.toPx(), handleH),
                        Size(3.5.dp.toPx(), h - handleH),
                        CornerRadius(2.dp.toPx())
                    )
                    // Griff (dickere Stelle oben)
                    drawRoundRect(
                        Color.Black,
                        Offset(cx - 11.dp.toPx(), 0f),
                        Size(22.dp.toPx(), handleH + 4.dp.toPx()),
                        CornerRadius(8.dp.toPx())
                    )
                    drawRoundRect(
                        gradient,
                        Offset(cx - 7.dp.toPx(), 4.dp.toPx()),
                        Size(14.dp.toPx(), handleH - 4.dp.toPx()),
                        CornerRadius(5.dp.toPx())
                    )
                }
            }

            // Ziehbereich für den Cursor-Griff (oberster Streifen)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(HANDLE_DP.dp)
                    .pointerInput(Unit) {
                        val grab = 40.dp.toPx()
                        var active = false
                        detectDragGestures(
                            onDragStart = { start ->
                                val cx = (editor.cursorBar * state.barPx - state.scrollPx).toFloat()
                                active = abs(start.x - cx) <= grab
                            },
                            onDragEnd = {
                                if (active) {
                                    editor.cursorBar = snapBar(editor.cursorBar, state.division(this))
                                }
                                active = false
                            },
                            onDragCancel = { active = false },
                            onDrag = { change, _ ->
                                if (active) {
                                    change.consume()
                                    editor.cursorBar =
                                        ((change.position.x + state.scrollPx) / state.barPx)
                                            .toDouble()
                                            .coerceAtLeast(0.0)
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { off ->
                            val bar = ((off.x + state.scrollPx) / state.barPx).toDouble()
                            editor.cursorBar = snapBar(bar, state.division(this))
                        }
                    }
            )
        }
    }
}

@Composable
private fun TrackBadge(track: Track, selected: Boolean) {
    val ring = if (track.type == TrackType.AUDIO) Color(0xFFFFE600) else Color(0xFF5B21B6)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_DP.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(ring.copy(alpha = 0.35f))
            )
        }
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(ring)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (track.type == TrackType.AUDIO) "📁" else "🥁",
                fontSize = 22.sp
            )
        }
    }
}
