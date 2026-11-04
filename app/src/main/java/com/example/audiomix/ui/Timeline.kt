package com.example.audiomix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomix.data.AudioImport
import com.example.audiomix.data.Clip
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

private const val MODE_TRIM_START = 0
private const val MODE_TRIM_END = 1
private const val MODE_MOVE = 2

private class DragTarget(
    val mode: Int,
    val trackId: String,
    val type: TrackType,
    val clip: Clip
)

/** Zoom (Pixel pro Takt), seitliche Verschiebung und der Clip, der gerade gezogen wird. */
class TimelineState(initialBarPx: Float) {
    var barPx by mutableFloatStateOf(initialBarPx)
    var scrollPx by mutableFloatStateOf(0f)
    var ghostTrackId by mutableStateOf<String?>(null)
    var ghostClip by mutableStateOf<Clip?>(null)

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

/** Berechnet, wie der Clip während des Ziehens aussieht. */
private fun computeGhost(t: DragTarget, deltaBars: Double, div: Int): Clip {
    val c = t.clip
    val minLen = 1.0 / 16.0
    return when (t.mode) {
        MODE_MOVE -> c.copy(startBar = snapBar(c.startBar + deltaBars, div))

        MODE_TRIM_START -> {
            val edge = snapBar(c.startBar + deltaBars, div)
            var delta = edge - c.startBar
            var lo = -c.startBar
            if (t.type == TrackType.AUDIO) lo = maxOf(lo, -c.offsetBar)
            val hi = c.lengthBar - minLen
            delta = minOf(maxOf(delta, lo), hi)
            c.copy(
                startBar = c.startBar + delta,
                lengthBar = c.lengthBar - delta,
                offsetBar = if (t.type == TrackType.AUDIO) c.offsetBar + delta else c.offsetBar
            )
        }

        MODE_TRIM_END -> {
            val edge = snapBar(c.endBar + deltaBars, div)
            var len = edge - c.startBar
            val maxLen = if (t.type == TrackType.AUDIO && c.sourceLengthBar > 0.0) {
                c.sourceLengthBar - c.offsetBar
            } else {
                Double.MAX_VALUE
            }
            len = minOf(maxOf(len, minLen), maxLen)
            c.copy(lengthBar = len)
        }

        else -> c
    }
}

@Composable
fun Timeline(
    editor: ProjectEditor,
    state: TimelineState,
    peaks: Map<String, ByteArray>,
    modifier: Modifier = Modifier
) {
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

    /** Was liegt unter dem Finger: ein Griff des ausgewählten Clips oder ein Clip zum Verschieben? */
    fun findTarget(p: Offset, rowsTop: Float, rowH: Float, grab: Float): DragTarget? {
        if (p.y < rowsTop) return null
        val index = ((p.y - rowsTop) / rowH).toInt()
        val track = editor.tracks.getOrNull(index) ?: return null
        val barPx = state.barPx
        val scroll = state.scrollPx

        if (track.id == editor.selectedTrackId) {
            val selected = track.clips.firstOrNull { it.id == editor.selectedClipId }
            if (selected != null) {
                val left = (selected.startBar * barPx - scroll).toFloat()
                val right = (selected.endBar * barPx - scroll).toFloat()
                if (abs(p.x - left) <= grab) {
                    return DragTarget(MODE_TRIM_START, track.id, track.type, selected)
                }
                if (abs(p.x - right) <= grab) {
                    return DragTarget(MODE_TRIM_END, track.id, track.type, selected)
                }
            }
        }
        val bar = ((p.x + scroll) / barPx).toDouble()
        val clip = track.clips.firstOrNull { bar >= it.startBar && bar < it.endBar } ?: return null
        return DragTarget(MODE_MOVE, track.id, track.type, clip)
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
                    // 1) Griffe ziehen und Clips verschieben (greift zuerst ein)
                    .pointerInput(Unit) {
                        val rowsTop = (HANDLE_DP + RULER_DP).dp.toPx()
                        val rowH = ROW_DP.dp.toPx()
                        val grab = 24.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            val target = findTarget(down.position, rowsTop, rowH, grab)
                                ?: return@awaitEachGesture

                            if (target.mode == MODE_MOVE) {
                                // Erst gedrückt halten, dann verschieben
                                val slop = viewConfiguration.touchSlop
                                val cancelled = withTimeoutOrNull<Boolean>(
                                    viewConfiguration.longPressTimeoutMillis
                                ) {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || change.changedToUpIgnoreConsumed()) {
                                            return@withTimeoutOrNull true
                                        }
                                        if ((change.position - down.position).getDistance() > slop) {
                                            return@withTimeoutOrNull true
                                        }
                                    }
                                    false
                                }
                                if (cancelled != null) return@awaitEachGesture
                                editor.selectTrack(target.trackId)
                                editor.selectClip(target.clip.id)
                            } else {
                                down.consume()
                            }

                            val startX = down.position.x
                            state.ghostTrackId = target.trackId
                            state.ghostClip = target.clip
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (change.changedToUpIgnoreConsumed()) break
                                val deltaBars = ((change.position.x - startX) / state.barPx).toDouble()
                                state.ghostClip = computeGhost(target, deltaBars, state.division(this))
                            }
                            val ghost = state.ghostClip
                            state.ghostClip = null
                            state.ghostTrackId = null
                            if (ghost != null && ghost != target.clip) {
                                editor.replaceClip(target.trackId, ghost)
                            }
                        }
                    }
                    // 2) Antippen
                    .pointerInput(Unit) {
                        val rowsTop = (HANDLE_DP + RULER_DP).dp.toPx()
                        val rowH = ROW_DP.dp.toPx()
                        detectTapGestures(
                            onTap = { off -> regionTap(off, rowsTop, rowH, false) },
                            onLongPress = { off -> regionTap(off, rowsTop, rowH, true) }
                        )
                    }
                    // 3) Scrollen und Zoomen
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
                val ghost = state.ghostClip
                val secondsPerBar = 240.0 / editor.project.bpm

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
                    // Taktzahl nur zeichnen, wenn sie sichtbar ist (sonst stürzt die App ab)
                    if (isBar && x > -40.dp.toPx() && x < w) {
                        val label = textMeasurer.measure(
                            (i / div + 1).toString(),
                            style = TextStyle(color = colors.rulerText, fontSize = 15.sp)
                        )
                        drawText(
                            label,
                            topLeft = Offset(x + 4.dp.toPx(), handleH + 3.dp.toPx())
                        )
                    }
                }

                // Clips
                currentTracks.forEachIndexed { r, t ->
                    val y = rowsTop + r * rowH
                    t.clips.forEach { original ->
                        val c = if (ghost != null && ghost.id == original.id) ghost else original
                        val left = (c.startBar * barPx - scroll).toFloat()
                        val width = (c.lengthBar * barPx).toFloat()
                        if (left + width < 0f || left > w) return@forEach

                        val base = if (t.type == TrackType.AUDIO) Color(0xFFE8C14B) else Color(0xFFF5A04A)
                        val color = if (c.id == editor.selectedClipId) lerp(base, Color.Gray, 0.4f) else base
                        val top = y + 6.dp.toPx()
                        val clipH = rowH - 12.dp.toPx()
                        drawRoundRect(
                            color,
                            Offset(left, top),
                            Size(width, clipH),
                            CornerRadius(12.dp.toPx())
                        )

                        if (t.type == TrackType.DRUM) {
                            if (barPx / 4f >= 8.dp.toPx()) {
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
                        } else {
                            val data = c.sourceFile?.let { peaks[it] }
                            if (data != null && data.isNotEmpty()) {
                                val cy = top + clipH / 2f
                                val x0 = maxOf(left, 0f)
                                val x1 = minOf(left + width, w)
                                val stepPx = 3.dp.toPx()
                                var x = x0
                                while (x < x1) {
                                    val barInClip = (x - left) / barPx
                                    val sec = (c.offsetBar + barInClip) * secondsPerBar
                                    val idx = (sec * AudioImport.PEAKS_PER_SECOND).toInt()
                                    if (idx >= 0 && idx < data.size) {
                                        val amp = (data[idx].toInt() and 0xFF) / 255f
                                        val half = maxOf(1.5.dp.toPx(), amp * clipH / 2f * 0.9f)
                                        drawLine(
                                            Color(0xFF7A5C10),
                                            Offset(x, cy - half),
                                            Offset(x, cy + half),
                                            2.dp.toPx()
                                        )
                                    }
                                    x += stepPx
                                }
                            }
                        }
                    }
                }

                // Halbkreis-Griffe am ausgewählten Clip
                val selIndex = currentTracks.indexOfFirst { it.id == editor.selectedTrackId }
                if (selIndex >= 0 && editor.selectedClipId != null) {
                    val original = currentTracks[selIndex].clips
                        .firstOrNull { it.id == editor.selectedClipId }
                    if (original != null) {
                        val sc = if (ghost != null && ghost.id == original.id) ghost else original
                        val cy = rowsTop + selIndex * rowH + rowH / 2f
                        val l = (sc.startBar * barPx - scroll).toFloat()
                        val r = (sc.endBar * barPx - scroll).toFloat()
                        drawHandle(l, cy, 14.dp.toPx(), true)
                        drawHandle(r, cy, 14.dp.toPx(), false)
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

private fun DrawScope.drawHandle(cx: Float, cy: Float, radius: Float, leftSide: Boolean) {
    val startAngle = if (leftSide) 90f else 270f
    drawArc(
        Color.Black,
        startAngle,
        180f,
        true,
        Offset(cx - radius, cy - radius),
        Size(radius * 2f, radius * 2f)
    )
    val inner = radius - 3.dp.toPx()
    drawArc(
        Color(0xFFE8607A),
        startAngle,
        180f,
        true,
        Offset(cx - inner, cy - inner),
        Size(inner * 2f, inner * 2f)
    )
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
