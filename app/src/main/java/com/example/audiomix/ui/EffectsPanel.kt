package com.example.audiomix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BAND_NAMES = listOf("Treble", "Mid", "Bass")
private val TABS = listOf("Tonhöhen", "Delay", "Reverb", "Radio", "3D")

private fun dbFromX(x: Float, width: Float, margin: Float): Int {
    val f = ((x - margin) / (width - 2f * margin)).coerceIn(0f, 1f)
    return Math.round(f * 24f) - 12
}

/** Regler von -12 bis +12 dB, Mitte = unverändert. */
@Composable
private fun GainSlider(db: Int, enabled: Boolean, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val latest = rememberUpdatedState(onChange)
    val active = rememberUpdatedState(enabled)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .pointerInput(Unit) {
                val m = 22.dp.toPx()
                detectTapGestures { off ->
                    if (active.value) latest.value(dbFromX(off.x, size.width.toFloat(), m))
                }
            }
            .pointerInput(Unit) {
                val m = 22.dp.toPx()
                detectDragGestures { change, _ ->
                    if (active.value) {
                        change.consume()
                        latest.value(dbFromX(change.position.x, size.width.toFloat(), m))
                    }
                }
            }
    ) {
        val m = 22.dp.toPx()
        val left = m
        val right = size.width - m
        val cy = size.height / 2f
        drawLine(colors.onBackground, Offset(left, cy), Offset(right, cy), 3.dp.toPx(), StrokeCap.Round)
        val x = left + (right - left) * (db + 12) / 24f
        drawCircle(
            if (enabled) Color(0xFFFF2D85) else Color(0xFFE2E2E2),
            15.dp.toPx(),
            Offset(x, cy)
        )
    }
}

/** An/Aus-Schalter: pinker App-Farbverlauf, Knopf schwarz (bzw. weiß im dunklen Modus). */
@Composable
private fun FxToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val gradient = Brush.horizontalGradient(
        listOf(Color(0xFFFFB3C7), Color(0xFFE8607A), Color(0xFF8E2C75))
    )
    val background: Brush = if (on) gradient else SolidColor(colors.onBackground.copy(alpha = 0.12f))

    Box(
        modifier = modifier
            .width(62.dp)
            .height(34.dp)
            .clip(CircleShape)
            .background(background)
            .border(2.dp, colors.onBackground, CircleShape)
            .clickable { onChange(!on) }
    ) {
        Box(
            Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(colors.onBackground)
        )
    }
}

/** Die Effekt-Auswahl: seitlich scrollbar mit nebligen Enden, rechts der An/Aus-Schalter. */
@Composable
private fun EffectTabs(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onLockedTab: (String) -> Unit
) {
    val colors = LocalAppColors.current
    val barColor = lerp(colors.timelineBg, colors.onBackground, 0.07f)
    val gray = colors.onBackground.copy(alpha = 0.35f)
    val scroll = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(barColor)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.horizontalScroll(scroll).padding(horizontal = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TABS.forEachIndexed { i, name ->
                    Text(
                        text = name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = if (i == 0) colors.onBackground else gray,
                        maxLines = 1,
                        modifier = Modifier.clickable { if (i != 0) onLockedTab(name) }
                    )
                }
            }
            // Nebel an beiden Enden
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(26.dp)
                    .height(32.dp)
                    .background(Brush.horizontalGradient(listOf(barColor, barColor.copy(alpha = 0f))))
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(26.dp)
                    .height(32.dp)
                    .background(Brush.horizontalGradient(listOf(barColor.copy(alpha = 0f), barColor)))
            )
        }
        FxToggle(on = enabled, onChange = onEnabledChange, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** Das Effekte-Fenster. Nur "Tonhöhen" ist verfügbar. */
@Composable
fun EffectsPanel(
    gains: List<Int>,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    fadeIn: List<Float>,
    fadeOut: List<Float>,
    verlaufBand: Int?,
    onVerlaufBand: (Int?) -> Unit,
    onGain: (Int, Int) -> Unit,
    onFade: (Int, Float, Float) -> Unit,
    onLockedTab: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val gray = colors.onBackground.copy(alpha = 0.35f)

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        EffectTabs(
            enabled = enabled,
            onEnabledChange = onEnabledChange,
            onLockedTab = onLockedTab
        )

        // Treble, Mid, Bass (abgedunkelt, wenn der Effekt aus ist)
        Column(modifier = Modifier.alpha(if (enabled) 1f else 0.4f)) {
            for (band in 0..2) {
                val inVerlauf = verlaufBand == band
                val greyed = verlaufBand != null && !inVerlauf
                Row(
                    modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = BAND_NAMES[band],
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        color = if (greyed) gray else colors.onBackground,
                        modifier = Modifier.width(52.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (inVerlauf) {
                            FadeSlider(
                                inFrac = fadeIn[band],
                                outFrac = fadeOut[band],
                                onChange = { i, o -> onFade(band, i, o) },
                                heightDp = 50
                            )
                        } else {
                            GainSlider(db = gains[band], enabled = !greyed, onChange = { onGain(band, it) })
                        }
                    }
                    Text(
                        text = "Verlauf",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        color = if (inVerlauf) colors.onBackground else gray,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.onBackground.copy(alpha = 0.08f))
                            .clickable { onVerlaufBand(if (inVerlauf) null else band) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
