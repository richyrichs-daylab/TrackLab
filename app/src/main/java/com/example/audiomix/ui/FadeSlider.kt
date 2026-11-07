package com.example.audiomix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val PINK = Color(0xFFFF2D85)
private val CYAN = Color(0xFF5CE1E6)
private const val FADE_MARGIN_DP = 28

private fun pickKnob(x: Float, w: Float, m: Float, i: Float, o: Float): Int {
    val px = m + (w - 2f * m) * i
    val cx = m + (w - 2f * m) * (1f - o)
    val dPink = abs(x - px)
    val dCyan = abs(x - cx)
    return if (dPink < dCyan) 0 else if (dCyan < dPink) 1 else if (x < px) 0 else 1
}

/**
 * Zwei Kreise auf einer Linie: pink = Ende des Einblendens, cyan = Beginn des Ausblendens.
 * [inFrac] und [outFrac] sind Anteile (0 bis 1) der Gesamtlänge.
 */
@Composable
fun FadeSlider(
    inFrac: Float,
    outFrac: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    heightDp: Int = 64,
    onFinished: () -> Unit = {}
) {
    val colors = LocalAppColors.current
    val state = rememberUpdatedState(Triple(inFrac, outFrac, onChange))
    val finished = rememberUpdatedState(onFinished)

    fun applyMove(x: Float, w: Float, m: Float, which: Int) {
        val (i, o, cb) = state.value
        val f = ((x - m) / (w - 2f * m)).coerceIn(0f, 1f)
        if (which == 0) cb(minOf(f, 1f - o), o) else cb(i, minOf(1f - f, 1f - i))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .pointerInput(Unit) {
                val m = FADE_MARGIN_DP.dp.toPx()
                var active = 0
                detectDragGestures(
                    onDragStart = { start ->
                        val (i, o, _) = state.value
                        active = pickKnob(start.x, size.width.toFloat(), m, i, o)
                        applyMove(start.x, size.width.toFloat(), m, active)
                    },
                    onDragEnd = { finished.value() },
                    onDragCancel = { finished.value() },
                    onDrag = { change, _ ->
                        change.consume()
                        applyMove(change.position.x, size.width.toFloat(), m, active)
                    }
                )
            }
            .pointerInput(Unit) {
                val m = FADE_MARGIN_DP.dp.toPx()
                detectTapGestures { off ->
                    val (i, o, _) = state.value
                    val which = pickKnob(off.x, size.width.toFloat(), m, i, o)
                    applyMove(off.x, size.width.toFloat(), m, which)
                    finished.value()
                }
            }
    ) {
        val m = FADE_MARGIN_DP.dp.toPx()
        val left = m
        val right = size.width - m
        val cy = size.height / 2f
        val px = left + (right - left) * inFrac
        val cx = left + (right - left) * (1f - outFrac)
        val stroke = 6.dp.toPx()

        drawLine(colors.onBackground, Offset(left, cy), Offset(right, cy), stroke, StrokeCap.Round)
        if (inFrac > 0f) drawLine(PINK, Offset(left, cy), Offset(px, cy), stroke, StrokeCap.Round)
        if (outFrac > 0f) drawLine(CYAN, Offset(cx, cy), Offset(right, cy), stroke, StrokeCap.Round)
        drawCircle(PINK, 15.dp.toPx(), Offset(px, cy))
        drawCircle(CYAN, 15.dp.toPx(), Offset(cx, cy))
    }
}
