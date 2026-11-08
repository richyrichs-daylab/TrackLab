package com.example.audiomix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomix.R
import com.example.audiomix.data.CELL_BASE
import com.example.audiomix.data.Clip
import com.example.audiomix.data.Sounds
import com.example.audiomix.data.TrackType
import com.example.audiomix.data.patternCells
import kotlin.math.abs

private const val CELL_W_DP = 34
private const val CELL_GAP_DP = 4
private const val LANE_H_DP = 52
private const val HEADER_H_DP = 28
private const val LABEL_W_DP = 160

private val EFFECT_ICONS = intArrayOf(
    R.drawable.ic_drum,
    R.drawable.ic_hihat,
    R.drawable.ic_hihat2,
    R.drawable.ic_knaller,
    R.drawable.ic_clap,
    R.drawable.ic_snap,
    R.drawable.ic_pen,
    R.drawable.ic_krach,
    R.drawable.ic_zap
)

private fun laneIcon(type: TrackType, lane: Int): Int =
    if (type == TrackType.SYNTH) {
        R.drawable.ic_keyboard
    } else {
        EFFECT_ICONS[lane.coerceIn(0, EFFECT_ICONS.size - 1)]
    }

/**
 * Die Musikrolle: 16 Felder pro Takt, gesetzte Felder sind pink.
 * Zeigt genau so viele Felder, wie der Block lang ist.
 */
@Composable
fun PatternRoll(
    type: TrackType,
    clip: Clip,
    muted: Boolean,
    onToggle: (lane: Int, cell: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val names = Sounds.laneNames(type)
    val laneCount = names.size
    val cells = patternCells(clip.lengthBar, clip.stretch)
    val cellBars = 1.0 / (16.0 * clip.stretch)
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val latestToggle = rememberUpdatedState(onToggle)

    val activeColor = if (muted) Color(0xFFBDBDBD) else Color(0xFFE8607A)
    val lightCell = colors.onBackground.copy(alpha = 0.07f)
    val darkCell = colors.onBackground.copy(alpha = 0.17f)

    val totalWidth = (cells * (CELL_W_DP + CELL_GAP_DP)).dp
    val canvasHeight = (HEADER_H_DP + laneCount * LANE_H_DP).dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 250.dp)
            .background(colors.timelineBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(vScroll)
                .padding(vertical = 6.dp)
        ) {
            Row {
                // Symbol und Name der Tonzeilen
                Column(Modifier.width(LABEL_W_DP.dp)) {
                    Spacer(Modifier.height(HEADER_H_DP.dp))
                    names.forEachIndexed { lane, name ->
                        Row(
                            modifier = Modifier.height(LANE_H_DP.dp).padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(30.dp).clip(CircleShape).background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(laneIcon(type, lane)),
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = name,
                                color = colors.onBackground,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Felder (horizontal scrollbar)
                Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                    Canvas(
                        modifier = Modifier
                            .width(totalWidth)
                            .height(canvasHeight)
                            .pointerInput(cells, laneCount) {
                                detectTapGestures { off ->
                                    val cw = (CELL_W_DP + CELL_GAP_DP).dp.toPx()
                                    val headerH = HEADER_H_DP.dp.toPx()
                                    val laneH = LANE_H_DP.dp.toPx()
                                    if (off.y >= headerH) {
                                        val lane = ((off.y - headerH) / laneH).toInt()
                                        val cell = (off.x / cw).toInt()
                                        if (lane in 0 until laneCount && cell in 0 until cells) {
                                            latestToggle.value(lane, cell)
                                        }
                                    }
                                }
                            }
                    ) {
                        val cw = (CELL_W_DP + CELL_GAP_DP).dp.toPx()
                        val cellW = CELL_W_DP.dp.toPx()
                        val headerH = HEADER_H_DP.dp.toPx()
                        val laneH = LANE_H_DP.dp.toPx()

                        // Taktzahlen (wie in der Taktzeile)
                        for (i in 0 until cells) {
                            val pos = clip.startBar + i * cellBars
                            val rounded = Math.round(pos).toDouble()
                            if (abs(pos - rounded) < 1e-6) {
                                val label = measurer.measure(
                                    (rounded.toInt() + 1).toString(),
                                    style = TextStyle(color = colors.rulerText, fontSize = 15.sp)
                                )
                                drawText(label, topLeft = Offset(i * cw + 2.dp.toPx(), 4.dp.toPx()))
                            }
                        }

                        // Felder
                        for (lane in 0 until laneCount) {
                            val y = headerH + lane * laneH + 3.dp.toPx()
                            for (cell in 0 until cells) {
                                val on = (lane * CELL_BASE + cell) in clip.pattern
                                val color = when {
                                    on -> activeColor
                                    (cell / 4) % 2 == 0 -> lightCell
                                    else -> darkCell
                                }
                                drawRoundRect(
                                    color,
                                    Offset(cell * cw, y),
                                    Size(cellW, laneH - 6.dp.toPx()),
                                    CornerRadius(6.dp.toPx())
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
