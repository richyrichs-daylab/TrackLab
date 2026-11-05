package com.example.audiomix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val SLIDER_MARGIN_DP = 36

private fun percentFromX(x: Float, width: Float, margin: Float): Int {
    val fraction = ((x - margin) / (width - 2f * margin)).coerceIn(0f, 1f)
    return Math.round(fraction * 40f) * 5
}

/** Lautstärke-Regler von 0 bis 200 Prozent in 5er-Schritten. */
@Composable
fun VolumeSlider(percent: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val latest = rememberUpdatedState(onChange)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .pointerInput(Unit) {
                val margin = SLIDER_MARGIN_DP.dp.toPx()
                detectTapGestures { off ->
                    latest.value(percentFromX(off.x, size.width.toFloat(), margin))
                }
            }
            .pointerInput(Unit) {
                val margin = SLIDER_MARGIN_DP.dp.toPx()
                detectDragGestures { change, _ ->
                    change.consume()
                    latest.value(percentFromX(change.position.x, size.width.toFloat(), margin))
                }
            }
    ) {
        val margin = SLIDER_MARGIN_DP.dp.toPx()
        val left = margin
        val right = size.width - margin
        val cy = size.height * 0.62f

        // Farbverlauf wie beim Vertikalcursor (bleibt immer gleich)
        val gradient = Brush.horizontalGradient(
            listOf(
                Color(0xFFF0E0E0),
                Color(0xFFE8607A),
                Color(0xFF8E2C75),
                Color(0xFF2A1A2E)
            ),
            startX = left,
            endX = right
        )
        drawRoundRect(
            Color.Black,
            Offset(left - 3.dp.toPx(), cy - 4.dp.toPx()),
            Size(right - left + 6.dp.toPx(), 8.dp.toPx()),
            CornerRadius(4.dp.toPx())
        )
        drawRoundRect(
            gradient,
            Offset(left, cy - 1.75.dp.toPx()),
            Size(right - left, 3.5.dp.toPx()),
            CornerRadius(2.dp.toPx())
        )

        // Striche mit Beschriftung bei 0 %, 100 % und 200 %
        val labels = listOf("0%", "100%", "200%")
        for (i in 0..2) {
            val x = left + (right - left) * i / 2f
            drawLine(
                colors.onBackground,
                Offset(x, cy - 14.dp.toPx()),
                Offset(x, cy + 14.dp.toPx()),
                2.dp.toPx()
            )
            val layout = measurer.measure(
                labels[i],
                style = TextStyle(color = colors.onBackground, fontSize = 14.sp)
            )
            drawText(
                layout,
                topLeft = Offset(
                    x - layout.size.width / 2f,
                    cy - 14.dp.toPx() - layout.size.height - 2.dp.toPx()
                )
            )
        }

        // pinker Regler
        val thumbX = left + (right - left) * percent / 200f
        drawCircle(Color.Black, 13.dp.toPx(), Offset(thumbX, cy))
        drawCircle(Color(0xFFE8607A), 10.dp.toPx(), Offset(thumbX, cy))
    }
}
