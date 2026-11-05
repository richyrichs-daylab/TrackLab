package com.example.audiomix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomix.R
import com.example.audiomix.data.AudioImport
import com.example.audiomix.data.Clip
import com.example.audiomix.data.ProjectEditor
import com.example.audiomix.data.Track
import com.example.audiomix.data.TrackType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

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
private const val MODE_STRETCH_START = 3
private const val MODE_STRETCH_END = 4
private const val MODE_REPEAT = 5

private class DragTarget(
    val mode: Int,
    val trackId: String,
    val type: TrackType,
    val clip: Clip
)

/** Zoom, Verschiebung, der Clip, der gerade gezogen wird, und der Dehnen-Modus. */
class TimelineState(initialBarPx: Float, private val density: Density) {
    var barPx by mutableFloatStateOf(initialBarPx)
    var scrollPx by mutableFloatStateOf(0f)
    var scrollY by mutableFloatStateOf(0f)
    var viewHeightPx by mutableFloatStateOf(0f)
    var ghostTrackId by mutableStateOf<String?>(null)
    var ghostClip by mutableStateOf<Clip?>(null)
    var stretchMode by mutableStateOf(false)

    fun division(density: Density): Int {
        val minLinePx = with(density) { MIN_LINE_DP.dp.toPx() }
        for (d in intArrayOf(16, 8, 4, 2)) {
            if (barPx / d >= minLinePx) return d
        }
        return 1
    }

    /** Wie weit man senkrecht höchstens scrollen kann (mit etwas freier Fläche unter der letzten Zeile). */
    fun maxScrollY(rows: Int): Float {
        val rowsTop = with(density) { (HANDLE_DP + RULER_DP).dp.toPx() }
        val rowH = with(density) { ROW_DP.dp.toPx() }
        val content = rows * rowH + rowH * 0.7f
        return (content - (viewHeightPx - rowsTop)).coerceAtLeast(0f)
    }

    fun clampedScrollY(rows: Int): Float = scrollY.coerceIn(0f, maxScrollY(rows))
}

fun snapBar(bar: Double, division: Int): Double =
    (round(bar * division) / division).coerceAtLeast(0.0)

/** Kürzen, Verlängern und Verschieben. */
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
            val edge = snapBar(c.tileEnd + deltaBars, div)
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

/** Dehnen: Der Block wird kürzer oder länger, sein Tempo ändert sich entsprechend. */
private fun computeStretchGhost(mode: Int, c: Clip, deltaBars: Double, div: Int): Clip {
    val minLen = 1.0 / 16.0
    var len: Double
    if (mode == MODE_STRETCH_END) {
        val edge = snapBar(c.tileEnd + deltaBars, div)
        len = maxOf(minLen, edge - c.startBar)
    } else {
        val edge = minOf(snapBar(c.startBar + deltaBars, div), c.tileEnd - minLen)
        len = c.tileEnd - edge
    }
    var factor = (len / c.lengthBar).coerceIn(c.stretch / 4.0, c.stretch * 4.0)
    len = c.lengthBar * factor
    var start = if (mode == MODE_STRETCH_END) c.startBar else c.tileEnd - len
    if (start < 0.0) {
        len = c.tileEnd
        factor = len / c.lengthBar
        start = 0.0
    }
    return c.copy(
        startBar = start,
        lengthBar = len,
        offsetBar = c.offsetBar * factor,
        sourceLengthBar = c.sourceLengthBar * factor,
        stretch = c.stretch / factor
    )
}

/** Wiederholen: Der Block wird bis zur Zugposition wiederholt. */
private fun computeRepeatGhost(c: Clip, edgeBar: Double, div: Int): Clip {
    val snapped = snapBar(edgeBar, div)
    val reps = ((snapped - c.tileEnd) / c.lengthBar).coerceIn(0.0, 64.0)
    return c.copy(repeats = reps)
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
    val rowsTopPx = with(density) { (HANDLE_DP + RULER_DP).dp.toPx() }
    val rowHPx = with(density) { ROW_DP.dp.toPx() }
    val clipTopPx = with(density) { 6.dp.toPx() }
    val circleGrabPx = with(density) { 20.dp.toPx() }

    fun regionTap(off: Offset, long: Boolean) {
        val bar = ((off.x + state.scrollPx) / state.barPx).toDouble()
        if (off.y < rowsTopPx) {
            if (!long) editor.cursorBar = snapBar(bar, state.division(density))
            return
        }
        if (state.stretchMode) return
        val sy = state.clampedScrollY(editor.tracks.size)
        val index = ((off.y - rowsTopPx + sy) / rowHPx).toInt()
        val track = editor.tracks.getOrNull(index)
        if (track == null) {
            // freie Fläche: wählt eine einzelne Zeile ab (bei mehreren passiert nichts)
            if (editor.selectedTrackIds.size <= 1) editor.clearSelection()
            return
        }
        val ids = editor.selectedTrackIds
        val isSelected = track.id in ids

        if (long) {
            if (ids.isEmpty()) {
                editor.selectTrack(track.id)
            } else if (ids.size >= 2 && !isSelected) {
                editor.addToSelection(track.id)
            }
            return
        }
        if (ids.size >= 2) {
            editor.toggleSelection(track.id)
            return
        }
        if (!isSelected) {
            editor.selectTrack(track.id)
            return
        }
        val clip = track.clips.firstOrNull { bar >= it.startBar && bar < it.endBar }
        if (clip != null && editor.selectedClipId != clip.id) {
            editor.selectClip(clip.id)
        } else {
            editor.selectClip(null)
        }
    }

    /** Was liegt unter dem Finger: Wiederholen-Kreis, Griff des gewählten Blocks oder ein Block? */
    fun findTarget(p: Offset, grab: Float): DragTarget? {
        if (p.y < rowsTopPx) return null
        val barPx = state.barPx
        val scroll = state.scrollPx
        val sy = state.clampedScrollY(editor.tracks.size)
        val current = editor.tracks

        if (!state.stretchMode) {
            for (r in current.indices) {
                val cy = rowsTopPx + r * rowHPx - sy + clipTopPx
                for (c in current[r].clips) {
                    val cx = (c.endBar * barPx - scroll).toFloat()
                    if (hypot(p.x - cx, p.y - cy) <= circleGrabPx) {
                        return DragTarget(MODE_REPEAT, current[r].id, current[r].type, c)
                    }
                }
            }
        }

        val index = ((p.y - rowsTopPx + sy) / rowHPx).toInt()
        val track = current.getOrNull(index) ?: return null
        if (track.id != editor.selectedTrackId) return null

        var selected = track.clips.firstOrNull { it.id == editor.selectedClipId }
        val ghost = state.ghostClip
        if (state.stretchMode && ghost != null && selected != null && ghost.id == selected.id) selected = ghost
        if (selected != null) {
            val left = (selected.startBar * barPx - scroll).toFloat()
            val right = (selected.tileEnd * barPx - scroll).toFloat()
            if (abs(p.x - left) <= grab) {
                return DragTarget(
                    if (state.stretchMode) MODE_STRETCH_START else MODE_TRIM_START,
                    track.id, track.type, selected
                )
            }
            if (abs(p.x - right) <= grab) {
                return DragTarget(
                    if (state.stretchMode) MODE_STRETCH_END else MODE_TRIM_END,
                    track.id, track.type, selected
                )
            }
        }
        if (state.stretchMode) return null
        val bar = ((p.x + scroll) / barPx).toDouble()
        val clip = track.clips.firstOrNull { bar >= it.startBar && bar < it.endBar } ?: return null
        return DragTarget(MODE_MOVE, track.id, track.type, clip)
    }

    Row(modifier = modifier.fillMaxWidth().background(colors.timelineBg)) {

        // Kreise am Zeilenanfang: immer sichtbar, scrollen senkrecht mit den Zeilen
        Column(Modifier.width(HEADER_DP.dp)) {
            Spacer(Modifier.height((HANDLE_DP + RULER_DP).dp))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                Column(
                    Modifier
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .offset { IntOffset(0, -state.clampedScrollY(editor.tracks.size).roundToInt()) }
                ) {
                    tracks.forEach { track ->
                        TrackBadge(track, selected = track.id in editor.selectedTrackIds)
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { state.viewHeightPx = it.height.toFloat() }
                    // 1) Griffe, Verschieben, Dehnen, Wiederholen (greift zuerst ein)
                    .pointerInput(Unit) {
                        val grab = 24.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            val target = findTarget(down.position, grab) ?: return@awaitEachGesture
                            val slop = viewConfiguration.touchSlop
                            val isRepeat = target.mode == MODE_REPEAT
                            val isStretch =
                                target.mode == MODE_STRETCH_START || target.mode == MODE_STRETCH_END

                            if (target.mode == MODE_MOVE) {
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
                                editor.selectClip(target.clip.id)
                            } else {
                                down.consume()
                            }

                            val startX = down.position.x
                            var moved = false
                            if (!isRepeat) {
                                state.ghostTrackId = target.trackId
                                state.ghostClip = target.clip
                            }
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (change.changedToUpIgnoreConsumed()) break
                                if (!moved && abs(change.position.x - startX) > slop) moved = true
                                val div = state.division(this)
                                if (isRepeat) {
                                    if (moved) {
                                        val edge = ((change.position.x + state.scrollPx) / state.barPx).toDouble()
                                        state.ghostTrackId = target.trackId
                                        state.ghostClip = computeRepeatGhost(target.clip, edge, div)
                                    }
                                } else {
                                    val deltaBars = ((change.position.x - startX) / state.barPx).toDouble()
                                    state.ghostClip = if (isStretch) {
                                        computeStretchGhost(target.mode, target.clip, deltaBars, div)
                                    } else {
                                        computeGhost(target, deltaBars, div)
                                    }
                                }
                            }

                            val ghost = state.ghostClip
                            if (isRepeat) {
                                state.ghostClip = null
                                state.ghostTrackId = null
                                if (!moved) {
                                    editor.addRepeat(target.trackId, target.clip.id)
                                } else if (ghost != null && ghost != target.clip) {
                                    editor.replaceClip(target.trackId, ghost)
                                }
                            } else if (!isStretch) {
                                state.ghostClip = null
                                state.ghostTrackId = null
                                if (ghost != null && ghost != target.clip) {
                                    editor.replaceClip(target.trackId, ghost)
                                }
                            }
                            // beim Dehnen bleibt der Entwurf stehen, bis X oder Haken gedrückt wird
                        }
                    }
                    // 2) Antippen und Gedrückthalten (Zeilen auswählen)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { off ->
                                // Bei genau einer gewählten Zeile fügt 2 Sekunden Gedrückthalten eine weitere hinzu
                                if (editor.selectedTrackIds.size == 1 &&
                                    !state.stretchMode && off.y >= rowsTopPx
                                ) {
                                    val sy = state.clampedScrollY(editor.tracks.size)
                                    val index = ((off.y - rowsTopPx + sy) / rowHPx).toInt()
                                    val pressed = editor.tracks.getOrNull(index)
                                    if (pressed != null && pressed.id !in editor.selectedTrackIds) {
                                        val result = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                                            tryAwaitRelease()
                                        }
                                        if (result == null) editor.addToSelection(pressed.id)
                                    }
                                }
                            },
                            onTap = { off -> regionTap(off, false) },
                            onLongPress = { off -> regionTap(off, true) }
                        )
                    }
                    // 3) Scrollen (seitlich und senkrecht) und Zoomen
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
                            val n = editor.tracks.size
                            state.scrollY = (state.clampedScrollY(n) - pan.y).coerceIn(0f, state.maxScrollY(n))
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
                val selectedIds = editor.selectedTrackIds
                val sy = state.clampedScrollY(currentTracks.size)
                val rowsBottom = rowsTop + currentTracks.size * rowH - sy
                val ghost = state.ghostClip
                val secondsPerBar = 240.0 / editor.project.bpm

                drawRect(colors.ruler, Offset(0f, handleH), Size(w, rulerH))

                // ---- Zeilenbereich (scrollt senkrecht, das Lineal bleibt stehen) ----
                clipRect(0f, rowsTop, w, h) {
                    if (currentTracks.isNotEmpty()) {
                        drawRect(colors.rowBg, Offset(0f, rowsTop - sy), Size(w, currentTracks.size * rowH))
                    }
                    currentTracks.forEachIndexed { r, t ->
                        val y = rowsTop + r * rowH - sy
                        if (t.id in selectedIds) {
                            drawRect(colors.onBackground.copy(alpha = 0.10f), Offset(0f, y), Size(w, rowH))
                        }
                        drawLine(
                            colors.line.copy(alpha = 0.5f),
                            Offset(0f, y + rowH),
                            Offset(w, y + rowH),
                            1f
                        )
                    }

                    val first = floor(scroll / step).toInt().coerceAtLeast(0)
                    val last = ceil((scroll + w) / step).toInt()
                    if (currentTracks.isNotEmpty()) {
                        for (i in first..last) {
                            val x = i * step - scroll
                            val isBar = i % div == 0
                            drawLine(
                                colors.line.copy(alpha = if (isBar) 0.55f else 0.25f),
                                Offset(x, rowsTop - sy),
                                Offset(x, rowsBottom),
                                1f
                            )
                        }
                    }

                    // Blöcke
                    currentTracks.forEachIndexed { r, t ->
                        val y = rowsTop + r * rowH - sy
                        if (y + rowH < rowsTop || y > h) return@forEachIndexed
                        val rowTempo = if (t.bpm > 0.0) t.bpm else editor.project.bpm.toDouble()

                        t.clips.forEach { original ->
                            val c = if (ghost != null && ghost.id == original.id) ghost else original
                            if (c.endBar * barPx - scroll < 0f || c.startBar * barPx - scroll > w) {
                                return@forEach
                            }
                            val gray = t.muted || c.muted
                            val base = when {
                                gray -> Color(0xFFBDBDBD)
                                t.type == TrackType.AUDIO -> Color(0xFFE8C14B)
                                t.type == TrackType.DRUM -> Color(0xFFF2C2FF)
                                else -> Color(0xFF8AD8FF)
                            }
                            val tickColor = when {
                                gray -> Color(0xFF757575)
                                t.type == TrackType.DRUM -> Color(0xFF8E2C9A)
                                else -> Color(0xFF2A6FA8)
                            }
                            val waveColor = if (gray) Color(0xFF757575) else Color(0xFF7A5C10)
                            val color =
                                if (c.id == editor.selectedClipId) lerp(base, Color.Gray, 0.4f) else base
                            val top = y + 6.dp.toPx()
                            val clipH = rowH - 12.dp.toPx()
                            val gap = if (c.repeats > 1e-9) 2.dp.toPx() else 0f

                            c.tiles().forEach { tile ->
                                val left = (tile.startBar * barPx - scroll).toFloat()
                                val width = (tile.lengthBar * barPx).toFloat()
                                if (left + width < 0f || left > w) return@forEach
                                drawRoundRect(
                                    color,
                                    Offset(left, top),
                                    Size(maxOf(width - gap, 1f), clipH),
                                    CornerRadius(12.dp.toPx())
                                )
                                if (t.type != TrackType.AUDIO) {
                                    val spacing = 1.0 / tile.stretch
                                    if (barPx * spacing / 4.0 >= 8.dp.toPx()) {
                                        val off = if (t.type == TrackType.HIHAT) 0.5 else 0.0
                                        var k = ceil(tile.startBar * 4.0 / spacing - off - 1e-9)
                                        while ((k + off) * spacing / 4.0 < tile.endBar - 1e-9) {
                                            val hx = ((k + off) * spacing / 4.0 * barPx - scroll).toFloat()
                                            drawRoundRect(
                                                tickColor,
                                                Offset(hx - 2.dp.toPx(), y + 24.dp.toPx()),
                                                Size(4.dp.toPx(), rowH - 48.dp.toPx()),
                                                CornerRadius(2.dp.toPx())
                                            )
                                            k += 1.0
                                        }
                                    }
                                } else {
                                    val data = tile.sourceFile?.let { peaks[it] }
                                    if (data != null && data.isNotEmpty()) {
                                        val cy = top + clipH / 2f
                                        val x0 = maxOf(left, 0f)
                                        val x1 = minOf(left + width, w)
                                        val stepPx = 3.dp.toPx()
                                        val speed = tile.speed(rowTempo)
                                        var x = x0
                                        while (x < x1) {
                                            val barInTile = (x - left) / barPx
                                            val sec = (tile.offsetBar + barInTile) * secondsPerBar * speed
                                            val idx = (sec * AudioImport.PEAKS_PER_SECOND).toInt()
                                            if (idx >= 0 && idx < data.size) {
                                                val amp = (data[idx].toInt() and 0xFF) / 255f
                                                val half = maxOf(1.5.dp.toPx(), amp * clipH / 2f * 0.9f)
                                                drawLine(
                                                    waveColor,
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

                            // Wiederholen-Kreis an der rechten oberen Ecke
                            if (!state.stretchMode) {
                                drawRepeatBadge(
                                    Offset((c.endBar * barPx - scroll).toFloat(), top),
                                    11.dp.toPx(),
                                    colors.background,
                                    colors.onBackground
                                )
                            }
                        }
                    }

                    // Halbkreis-Griffe am ausgewählten Block
                    val selIndex = currentTracks.indexOfFirst { it.id == editor.selectedTrackId }
                    if (selIndex >= 0 && editor.selectedClipId != null) {
                        val original = currentTracks[selIndex].clips.firstOrNull { it.id == editor.selectedClipId }
                        if (original != null) {
                            val sc = if (ghost != null && ghost.id == original.id) ghost else original
                            val cy = rowsTop + selIndex * rowH - sy + rowH / 2f
                            drawHandle((sc.startBar * barPx - scroll).toFloat(), cy, 14.dp.toPx(), true)
                            drawHandle((sc.tileEnd * barPx - scroll).toFloat(), cy, 14.dp.toPx(), false)
                        }
                    }
                }

                // ---- Taktraster im Lineal und Taktzahlen ----
                val first = floor(scroll / step).toInt().coerceAtLeast(0)
                val last = ceil((scroll + w) / step).toInt()
                for (i in first..last) {
                    val x = i * step - scroll
                    val isBar = i % div == 0
                    val tickTop = if (isBar) handleH else handleH + rulerH * 0.6f
                    drawLine(colors.line, Offset(x, tickTop), Offset(x, rowsTop), if (isBar) 2f else 1f)
                    if (isBar && x > -40.dp.toPx() && x < w) {
                        val label = textMeasurer.measure(
                            (i / div + 1).toString(),
                            style = TextStyle(color = colors.rulerText, fontSize = 15.sp)
                        )
                        drawText(label, topLeft = Offset(x + 4.dp.toPx(), handleH + 3.dp.toPx()))
                    }
                }

                // ---- Vertikalcursor mit Ziehgriff ----
                val cx = (editor.cursorBar * barPx - scroll).toFloat()
                if (cx >= -20.dp.toPx() && cx <= w + 20.dp.toPx()) {
                    val gradient = Brush.verticalGradient(
                        listOf(Color(0xFFF0E0E0), Color(0xFFE8607A), Color(0xFF8E2C75), Color(0xFF2A1A2E)),
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
        Color.Black, startAngle, 180f, true,
        Offset(cx - radius, cy - radius), Size(radius * 2f, radius * 2f)
    )
    val inner = radius - 3.dp.toPx()
    drawArc(
        Color(0xFFE8607A), startAngle, 180f, true,
        Offset(cx - inner, cy - inner), Size(inner * 2f, inner * 2f)
    )
}

/** Kreis mit rosa Wiederholungspfeil. */
private fun DrawScope.drawRepeatBadge(center: Offset, radius: Float, bg: Color, outline: Color) {
    drawCircle(bg, radius, center)
    drawCircle(outline.copy(alpha = 0.45f), radius, center, style = Stroke(width = 1.2.dp.toPx()))
    val brush = Brush.linearGradient(
        listOf(Color(0xFFFFB3C7), Color(0xFFE8607A), Color(0xFF8E2C75)),
        start = Offset(center.x - radius, center.y - radius),
        end = Offset(center.x + radius, center.y + radius)
    )
    val r = radius * 0.55f
    drawArc(
        brush, -50f, 270f, false,
        Offset(center.x - r, center.y - r), Size(r * 2f, r * 2f),
        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
    )
    val a = Math.toRadians(220.0)
    val ex = center.x + r * cos(a).toFloat()
    val ey = center.y + r * sin(a).toFloat()
    val tx = -sin(a).toFloat()
    val ty = cos(a).toFloat()
    val nx = cos(a).toFloat()
    val ny = sin(a).toFloat()
    val hs = radius * 0.42f
    val head = Path().apply {
        moveTo(ex + tx * hs, ey + ty * hs)
        lineTo(ex - tx * hs * 0.3f + nx * hs * 0.75f, ey - ty * hs * 0.3f + ny * hs * 0.75f)
        lineTo(ex - tx * hs * 0.3f - nx * hs * 0.75f, ey - ty * hs * 0.3f - ny * hs * 0.75f)
        close()
    }
    drawPath(head, brush)
}

@Composable
private fun TrackBadge(track: Track, selected: Boolean) {
    val ringColor = when {
        track.muted -> Color(0xFF9E9E9E)
        track.type == TrackType.AUDIO -> Color(0xFFFFE600)
        track.type == TrackType.DRUM -> Color(0xFFA820A8)
        else -> Color(0xFF00FFF0)
    }
    val glowColor = when {
        track.muted -> Color(0xFFBDBDBD)
        track.type == TrackType.AUDIO -> Color(0xFFFFE600)
        track.type == TrackType.DRUM -> Color(0xFFFF8CFF)
        else -> Color(0xFF4DA3FF)
    }
    val grayFilter = if (track.muted) {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    } else {
        null
    }

    Box(
        modifier = Modifier.fillMaxWidth().height(ROW_DP.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(Modifier.size(70.dp).clip(CircleShape).background(glowColor.copy(alpha = 0.22f)))
            Box(Modifier.size(62.dp).clip(CircleShape).background(glowColor.copy(alpha = 0.45f)))
        }
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(ringColor)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            when (track.type) {
                TrackType.AUDIO -> Text(
                    text = "📁",
                    fontSize = 22.sp,
                    modifier = Modifier.alpha(if (track.muted) 0.4f else 1f)
                )
                TrackType.DRUM -> Image(
                    painter = painterResource(R.drawable.ic_drum),
                    contentDescription = null,
                    colorFilter = grayFilter,
                    modifier = Modifier.size(28.dp)
                )
                TrackType.HIHAT -> Image(
                    painter = painterResource(R.drawable.ic_hihat),
                    contentDescription = null,
                    colorFilter = grayFilter,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
