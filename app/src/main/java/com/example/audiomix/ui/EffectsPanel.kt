package com.example.audiomix.ui

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomix.data.ProjectEditor
import com.example.audiomix.data.Track

/** Die Schlüssel der Effekte in der Reihenfolge der Tabs (Tonhöhen, Delay, Reverb). */
val FX_KEYS = listOf("pitch", "delay", "reverb")

private val TABS = listOf("Tonhöhen", "Delay", "Reverb", "Radio", "3D")
private val BAND_NAMES = listOf("Treble", "Mid", "Bass")
private val DELAY_NAMES = listOf("Decay", "Time", "Mix")
private val PRESETS = listOf("small plate", "medium plate", "big plate", "cave", "hallway", "behind you")

private fun dbFromX(x: Float, width: Float, margin: Float): Int {
    val f = ((x - margin) / (width - 2f * margin)).coerceIn(0f, 1f)
    return Math.round(f * 24f) - 12
}

private fun valueFromX(x: Float, width: Float, margin: Float): Float {
    val f = ((x - margin) / (width - 2f * margin)).coerceIn(0f, 1f)
    return Math.round(f * 20f) / 20f
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

/** Regler von 0 bis 1 in 5er-Schritten (links = 0). */
@Composable
private fun ParamSlider(value: Float, enabled: Boolean, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
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
                    if (active.value) latest.value(valueFromX(off.x, size.width.toFloat(), m))
                }
            }
            .pointerInput(Unit) {
                val m = 22.dp.toPx()
                detectDragGestures { change, _ ->
                    if (active.value) {
                        change.consume()
                        latest.value(valueFromX(change.position.x, size.width.toFloat(), m))
                    }
                }
            }
    ) {
        val m = 22.dp.toPx()
        val left = m
        val right = size.width - m
        val cy = size.height / 2f
        drawLine(colors.onBackground, Offset(left, cy), Offset(right, cy), 3.dp.toPx(), StrokeCap.Round)
        val x = left + (right - left) * value.coerceIn(0f, 1f)
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
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
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
                        color = if (i == selected) colors.onBackground else gray,
                        maxLines = 1,
                        modifier = Modifier.clickable {
                            if (i < FX_KEYS.size) onSelect(i) else onLockedTab(name)
                        }
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

/** Eine Reihe: Name, Regler (oder Verlauf-Regler) und der "Verlauf"-Knopf. */
@Composable
private fun FxRow(
    label: String,
    greyed: Boolean,
    inVerlauf: Boolean,
    onVerlauf: () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = LocalAppColors.current
    val gray = colors.onBackground.copy(alpha = 0.35f)
    Row(
        modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            color = if (greyed) gray else colors.onBackground,
            modifier = Modifier.width(52.dp)
        )
        Box(modifier = Modifier.weight(1f)) { content() }
        Text(
            text = "Verlauf",
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            color = if (inVerlauf) colors.onBackground else gray,
            modifier = Modifier
                .padding(start = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.onBackground.copy(alpha = 0.08f))
                .clickable(onClick = onVerlauf)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

/** Die sechs Räume des Reverbs. Das aktive Feld sieht aus wie das aktive "Effekte"-Feld, die anderen sind ausgegraut. */
@Composable
private fun PresetFields(selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalAppColors.current
    val gray = colors.onBackground.copy(alpha = 0.35f)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (rowIndex in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in 0..2) {
                    val index = rowIndex * 3 + k
                    val active = index == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) colors.rowBg else colors.onBackground.copy(alpha = 0.08f))
                            .clickable { onSelect(index) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = PRESETS[index],
                            color = if (active) Color(0xFFE8607A) else gray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Das Effekte-Fenster der ausgewählten Zeile. Tonhöhen, Delay und Reverb sind verfügbar,
 * Radio und 3D noch gesperrt. [extentLength] ist die Länge der Zeile in Takten (für die Verläufe).
 */
@Composable
fun EffectsPanel(
    track: Track,
    extentLength: Double,
    editor: ProjectEditor,
    tab: Int,
    onTab: (Int) -> Unit,
    verlauf: Int?,
    onVerlauf: (Int?) -> Unit,
    onLockedTab: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val current = tab.coerceIn(0, FX_KEYS.size - 1)
    val key = FX_KEYS[current]
    val enabled = key !in track.fxOff

    fun frac(bars: Double): Float =
        if (extentLength > 0.0) (bars / extentLength).toFloat().coerceIn(0f, 1f) else 0f

    fun noBlocks() {
        Toast.makeText(context, "Die Zeile hat noch keine Blöcke", Toast.LENGTH_SHORT).show()
    }

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        EffectTabs(
            selected = current,
            enabled = enabled,
            onSelect = onTab,
            onEnabledChange = { editor.setFxEnabled(key, it) },
            onLockedTab = onLockedTab
        )

        // Die Einstellungen (abgedunkelt, wenn der Effekt aus ist)
        Column(modifier = Modifier.alpha(if (enabled) 1f else 0.4f)) {
            when (current) {
                0 -> {
                    // Tonhöhen: Treble, Mid, Bass
                    for (band in 0..2) {
                        val inVerlauf = verlauf == band
                        val greyed = verlauf != null && !inVerlauf
                        FxRow(
                            label = BAND_NAMES[band],
                            greyed = greyed,
                            inVerlauf = inVerlauf,
                            onVerlauf = { onVerlauf(if (inVerlauf) null else band) }
                        ) {
                            if (inVerlauf) {
                                FadeSlider(
                                    inFrac = frac(track.eqFadeIn[band]),
                                    outFrac = frac(track.eqFadeOut[band]),
                                    onChange = { i, o ->
                                        if (extentLength > 0.0) {
                                            editor.setEqFade(band, i * extentLength, o * extentLength)
                                        } else {
                                            noBlocks()
                                        }
                                    },
                                    heightDp = 50
                                )
                            } else {
                                GainSlider(
                                    db = track.eqGain[band],
                                    enabled = !greyed,
                                    onChange = { editor.setEqGain(band, it) }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // Delay: Decay, Time, Mix
                    for (p in 0..2) {
                        val inVerlauf = verlauf == p
                        val greyed = verlauf != null && !inVerlauf
                        FxRow(
                            label = DELAY_NAMES[p],
                            greyed = greyed,
                            inVerlauf = inVerlauf,
                            onVerlauf = { onVerlauf(if (inVerlauf) null else p) }
                        ) {
                            if (inVerlauf) {
                                FadeSlider(
                                    inFrac = frac(track.delayFadeIn[p]),
                                    outFrac = frac(track.delayFadeOut[p]),
                                    onChange = { i, o ->
                                        if (extentLength > 0.0) {
                                            editor.setDelayFade(p, i * extentLength, o * extentLength)
                                        } else {
                                            noBlocks()
                                        }
                                    },
                                    heightDp = 50
                                )
                            } else {
                                ParamSlider(
                                    value = track.delay[p].toFloat(),
                                    enabled = !greyed,
                                    onChange = { editor.setDelay(p, it.toDouble()) }
                                )
                            }
                        }
                    }
                }

                else -> {
                    // Reverb: Level und die sechs Räume
                    val inVerlauf = verlauf == 0
                    FxRow(
                        label = "Level",
                        greyed = false,
                        inVerlauf = inVerlauf,
                        onVerlauf = { onVerlauf(if (inVerlauf) null else 0) }
                    ) {
                        if (inVerlauf) {
                            FadeSlider(
                                inFrac = frac(track.reverbFadeIn),
                                outFrac = frac(track.reverbFadeOut),
                                onChange = { i, o ->
                                    if (extentLength > 0.0) {
                                        editor.setReverbFade(i * extentLength, o * extentLength)
                                    } else {
                                        noBlocks()
                                    }
                                },
                                heightDp = 50
                            )
                        } else {
                            ParamSlider(
                                value = track.reverbLevel.toFloat(),
                                enabled = true,
                                onChange = { editor.setReverbLevel(it.toDouble()) }
                            )
                        }
                    }
                    PresetFields(
                        selected = track.reverbPreset,
                        onSelect = { editor.setReverbPreset(it) }
                    )
                }
            }
        }
    }
}
