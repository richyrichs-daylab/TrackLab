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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomix.data.ProjectEditor
import com.example.audiomix.data.Track
import kotlin.math.hypot

/** Die Schlüssel der Effekte in der Reihenfolge der Tabs (Tonhöhen, Delay, Reverb, Radio, 3D). */
val FX_KEYS = listOf("pitch", "delay", "reverb", "radio", "3d")

private val TABS = listOf("Tonhöhen", "Delay", "Reverb", "Radio", "3D")
private val BAND_NAMES = listOf("Treble", "Mid", "Bass")
private val DELAY_NAMES = listOf("Decay", "Time", "Mix")
private val RADIO_NAMES = listOf("Gain", "Tone")
private val PRESETS = listOf("small plate", "medium plate", "big plate", "cave", "hallway", "behind you")

private val PINK = Color(0xFFFF2D85)

private fun dbFromX(x: Float, width: Float, margin: Float, range: Int): Int {
    val f = ((x - margin) / (width - 2f * margin)).coerceIn(0f, 1f)
    return Math.round(f * 2f * range) - range
}

private fun valueFromX(x: Float, width: Float, margin: Float): Float {
    val f = ((x - margin) / (width - 2f * margin)).coerceIn(0f, 1f)
    return Math.round(f * 20f) / 20f
}

/** Regler von -range bis +range dB, Mitte = unverändert (Treble/Bass ±24, Mid ±12). */
@Composable
private fun GainSlider(db: Int, range: Int, enabled: Boolean, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
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
                    if (active.value) latest.value(dbFromX(off.x, size.width.toFloat(), m, range))
                }
            }
            .pointerInput(Unit) {
                val m = 22.dp.toPx()
                detectDragGestures { change, _ ->
                    if (active.value) {
                        change.consume()
                        latest.value(dbFromX(change.position.x, size.width.toFloat(), m, range))
                    }
                }
            }
    ) {
        val m = 22.dp.toPx()
        val left = m
        val right = size.width - m
        val cy = size.height / 2f
        drawLine(colors.onBackground, Offset(left, cy), Offset(right, cy), 3.dp.toPx(), StrokeCap.Round)
        val x = left + (right - left) * (db + range) / (2f * range)
        drawCircle(
            if (enabled) PINK else Color(0xFFE2E2E2),
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
            if (enabled) PINK else Color(0xFFE2E2E2),
            15.dp.toPx(),
            Offset(x, cy)
        )
    }
}

/** Welcher der fünf Plätze (0 Mitte, 1 vorne, 2 rechts, 3 links, 4 hinten) liegt am nächsten? */
private fun nearestPosition(p: Offset, side: Float): Int {
    val c = side / 2f
    val r = side * 0.35f
    val targets = arrayOf(
        Offset(c, c),          // Mitte
        Offset(c, c - r),      // V (oben)
        Offset(c - r, c),      // R (links im Bild)
        Offset(c + r, c),      // L (rechts im Bild)
        Offset(c, c + r)       // H (unten)
    )
    var best = 0
    var bestDist = Float.MAX_VALUE
    for (i in targets.indices) {
        val d = hypot(p.x - targets[i].x, p.y - targets[i].y)
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

/** Der Kreis für 3D: der pinke Punkt rastet auf Mitte, V, R, L oder H ein (Ziehen oder Antippen). */
@Composable
private fun ThreeDControl(pos: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val latest = rememberUpdatedState(onChange)
    val ringColor = Color(0xFFC8D0DA)

    Box(modifier = modifier.width(170.dp).height(150.dp)) {
        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .size(108.dp)
                .pointerInput(Unit) {
                    detectTapGestures { off -> latest.value(nearestPosition(off, size.width.toFloat())) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        latest.value(nearestPosition(change.position, size.width.toFloat()))
                    }
                }
        ) {
            val side = size.width
            val c = side / 2f
            val r = side * 0.35f
            drawCircle(ringColor, r, Offset(c, c), style = Stroke(width = side * 0.15f))
            drawCircle(ringColor, side * 0.11f, Offset(c, c))
            val dot = when (pos) {
                1 -> Offset(c, c - r)
                2 -> Offset(c - r, c)
                3 -> Offset(c + r, c)
                4 -> Offset(c, c + r)
                else -> Offset(c, c)
            }
            drawCircle(PINK, side * 0.14f, dot)
        }
        Text("V", Modifier.align(Alignment.TopCenter), colors.onBackground, 18.sp, fontWeight = FontWeight.Black)
        Text("H", Modifier.align(Alignment.BottomCenter), colors.onBackground, 18.sp, fontWeight = FontWeight.Black)
        Text("R", Modifier.align(Alignment.CenterStart), colors.onBackground, 18.sp, fontWeight = FontWeight.Black)
        Text("L", Modifier.align(Alignment.CenterEnd), colors.onBackground, 18.sp, fontWeight = FontWeight.Black)
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
    onEnabledChange: (Boolean) -> Unit
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
                        modifier = Modifier.clickable { onSelect(i) }
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
 * Das Effekte-Fenster der ausgewählten Zeile (Tonhöhen, Delay, Reverb, Radio, 3D).
 * [extentLength] ist die Länge der Zeile in Takten (für die Verläufe).
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
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
            onEnabledChange = { editor.setFxEnabled(key, it) }
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
                                    range = if (band == 1) 12 else 24,
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

                2 -> {
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

                3 -> {
                    // Radio (Overdrive): Gain und Tone, mittig im Fenster
                    Box(
                        modifier = Modifier.fillMaxWidth().height(162.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            for (p in 0..1) {
                                val inVerlauf = verlauf == p
                                val greyed = verlauf != null && !inVerlauf
                                FxRow(
                                    label = RADIO_NAMES[p],
                                    greyed = greyed,
                                    inVerlauf = inVerlauf,
                                    onVerlauf = { onVerlauf(if (inVerlauf) null else p) }
                                ) {
                                    if (inVerlauf) {
                                        FadeSlider(
                                            inFrac = frac(track.radioFadeIn[p]),
                                            outFrac = frac(track.radioFadeOut[p]),
                                            onChange = { i, o ->
                                                if (extentLength > 0.0) {
                                                    editor.setRadioFade(p, i * extentLength, o * extentLength)
                                                } else {
                                                    noBlocks()
                                                }
                                            },
                                            heightDp = 50
                                        )
                                    } else {
                                        ParamSlider(
                                            value = track.radio[p].toFloat(),
                                            enabled = !greyed,
                                            onChange = { editor.setRadio(p, it.toDouble()) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    // 3D: Position (V, R, L, H) und Acid
                    Row(
                        modifier = Modifier.fillMaxWidth().height(162.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ThreeDControl(
                            pos = track.threeDPos,
                            onChange = { editor.setThreeDPos(it) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Acid",
                                color = colors.onBackground,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            ParamSlider(
                                value = track.threeDAcid.toFloat(),
                                enabled = true,
                                onChange = {
                                    // taktgleich: auf 7 Stufen einrasten
                                    val v = if (track.acidSync) Math.round(it * 7f) / 7f else it
                                    editor.setThreeDAcid(v.toDouble())
                                }
                            )
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (track.acidSync) Color.Black
                                            else colors.onBackground.copy(alpha = 0.12f)
                                        )
                                        .clickable {
                                            val on = !track.acidSync
                                            editor.setAcidSync(on)
                                            if (on) {
                                                editor.setThreeDAcid(Math.round(track.threeDAcid * 7) / 7.0)
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "taktgleich",
                                        color = if (track.acidSync) PINK else colors.onBackground,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
