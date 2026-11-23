package com.example.audiomix.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

enum class TrackType { AUDIO, EFFECT, SYNTH }

/** Felder der Musikrolle werden als Tonzeile * CELL_BASE + Feldnummer gespeichert. */
const val CELL_BASE = 100000

/** So viele Felder hat die Musikrolle eines Blocks (16 pro Takt, bei gedehnten Blöcken entsprechend). */
fun patternCells(lengthBar: Double, stretch: Double): Int =
    maxOf(1, (lengthBar * 16.0 * stretch).roundToInt())

/** Alte Synth-Projekte hatten 4 Töne (C5, G5, C3, G3); das sind jetzt diese Zeilen der 20 Töne. */
private val OLD_SYNTH_LANES = intArrayOf(5, 1, 19, 15)

private fun remapSynthCode(code: Int): Int {
    val lane = code / CELL_BASE
    val cell = code % CELL_BASE
    val newLane = if (lane in OLD_SYNTH_LANES.indices) OLD_SYNTH_LANES[lane] else lane
    return newLane * CELL_BASE + cell
}

/** Standardwerte der Effekte. */
object FxDefaults {
    /** Delay: Decay, Time, Mix (jeweils 0 bis 1). Mix 0 = zunächst unhörbar. */
    val DELAY: List<Double> = listOf(0.4, 0.3, 0.0)

    /** Reverb: 1 = medium plate. */
    const val REVERB_PRESET = 1

    /** Radio (Overdrive): Gain, Tone (0 bis 1). Gain 0 = zunächst unhörbar. */
    val RADIO: List<Double> = listOf(0.0, 0.5)
}

data class Clip(
    val id: String,
    val startBar: Double,
    val lengthBar: Double,
    val offsetBar: Double = 0.0,
    val sourceFile: String? = null,
    val sourceLengthBar: Double = 0.0,
    val sourceBpm: Double = 100.0,
    val stretch: Double = 1.0,
    val repeatCount: Int = 0,
    val repeatPartial: Double = 0.0,
    val muted: Boolean = false,
    val pattern: Set<Int> = emptySet(),
    /** Länge des Einblendens und Ausblendens in Takten. */
    val fadeIn: Double = 0.0,
    val fadeOut: Double = 0.0,
    /** Nur Synth-Blöcke: 0 = Synth, 1 = Piano, 2 = Bass. */
    val instrument: Int = 0,
    /** Nur intern (nicht gespeichert): Anfang/Ende der ganzen Gruppe aus Block + Wiederholungen. */
    val groupStart: Double = -1.0,
    val groupEnd: Double = -1.0
) {
    val tileEnd: Double get() = startBar + lengthBar

    val fadeFrom: Double get() = if (groupStart >= 0.0) groupStart else startBar
    val fadeTo: Double get() = if (groupEnd >= 0.0) groupEnd else startBar + lengthBar

    val copiesLength: Double
        get() {
            if (repeatCount <= 0) return 0.0
            val last = if (repeatPartial > 0.0) min(repeatPartial, lengthBar) else lengthBar
            return (repeatCount - 1) * lengthBar + last
        }

    val endBar: Double get() = startBar + lengthBar + copiesLength

    fun speed(trackTempo: Double): Double = trackTempo / sourceBpm * stretch

    /** Der Block und seine Kopien als einzelne Teile (der erste ist das Original). */
    fun tiles(freshIds: Boolean = false): List<Clip> {
        val result = ArrayList<Clip>()
        val gs = startBar
        val ge = endBar
        result.add(copy(repeatCount = 0, repeatPartial = 0.0, groupStart = gs, groupEnd = ge))
        var s = startBar + lengthBar
        for (i in 1..repeatCount) {
            val len = if (i == repeatCount && repeatPartial > 0.0) min(repeatPartial, lengthBar) else lengthBar
            result.add(
                copy(
                    id = if (freshIds) UUID.randomUUID().toString() else id,
                    startBar = s,
                    lengthBar = len,
                    repeatCount = 0,
                    repeatPartial = 0.0,
                    groupStart = gs,
                    groupEnd = ge
                )
            )
            s += len
        }
        return result
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startBar", startBar)
        put("lengthBar", lengthBar)
        put("offsetBar", offsetBar)
        put("sourceLengthBar", sourceLengthBar)
        put("sourceBpm", sourceBpm)
        put("stretch", stretch)
        put("repeatCount", repeatCount)
        put("repeatPartial", repeatPartial)
        put("muted", muted)
        put("fadeIn", fadeIn)
        put("fadeOut", fadeOut)
        put("patternV", 2)
        put("instrument", instrument)
        put("pattern", JSONArray().apply { pattern.sorted().forEach { put(it) } })
        if (sourceFile != null) put("sourceFile", sourceFile)
    }

    companion object {
        /**
         * [legacyLane]: bei alten Trommel-/Hitsch-Blöcken die Tonzeile, aus der das Muster entsteht.
         * [remapSynth]: bei Synth-Blöcken aus der Zeit mit 4 Tönen werden die Tonzeilen umgerechnet.
         */
        fun fromJson(o: JSONObject, legacyLane: Int = -1, remapSynth: Boolean = false): Clip {
            val length = o.getDouble("lengthBar")
            val stretch = o.optDouble("stretch", 1.0)

            var count = o.optInt("repeatCount", 0)
            var partial = o.optDouble("repeatPartial", 0.0)
            if (!o.has("repeatCount") && o.has("repeats")) {
                val r = o.optDouble("repeats", 0.0)
                if (r > 1e-9) {
                    count = ceil(r - 1e-9).toInt()
                    val last = r - (count - 1)
                    partial = if (last >= 1.0 - 1e-6) 0.0 else last * length
                }
            }

            val pattern = HashSet<Int>()
            val array = o.optJSONArray("pattern")
            if (array != null) {
                val remap = remapSynth && !o.has("patternV")
                for (i in 0 until array.length()) {
                    val code = array.getInt(i)
                    pattern.add(if (remap) remapSynthCode(code) else code)
                }
            } else if (legacyLane >= 0) {
                val cells = patternCells(length, stretch)
                var k = if (legacyLane == 1) 2 else 0
                while (k < cells) {
                    pattern.add(legacyLane * CELL_BASE + k)
                    k += 4
                }
            }

            return Clip(
                id = o.getString("id"),
                startBar = o.getDouble("startBar"),
                lengthBar = length,
                offsetBar = o.optDouble("offsetBar", 0.0),
                sourceFile = if (o.has("sourceFile")) o.getString("sourceFile") else null,
                sourceLengthBar = o.optDouble("sourceLengthBar", 0.0),
                sourceBpm = o.optDouble("sourceBpm", 100.0),
                stretch = stretch,
                repeatCount = count,
                repeatPartial = partial,
                muted = o.optBoolean("muted", false),
                pattern = pattern,
                fadeIn = o.optDouble("fadeIn", 0.0),
                fadeOut = o.optDouble("fadeOut", 0.0),
                instrument = o.optInt("instrument", 0)
            )
        }
    }
}

private fun JSONObject.intList3(key: String): List<Int> {
    val a = optJSONArray(key)
    return List(3) { i -> if (a != null && i < a.length()) a.optInt(i, 0) else 0 }
}

/** Liest eine Liste von Kommazahlen; fehlende Werte kommen aus [default] (bestimmt auch die Länge). */
private fun JSONObject.doubleList(key: String, default: List<Double>): List<Double> {
    val a = optJSONArray(key)
    return List(default.size) { i -> if (a != null && i < a.length()) a.optDouble(i, default[i]) else default[i] }
}

data class Track(
    val id: String,
    val type: TrackType,
    val clips: List<Clip>,
    val name: String? = null,
    val bpm: Double = 0.0,
    val muted: Boolean = false,
    val volume: Int = 100,
    /** Tonhöhen: Treble, Mid, Bass in dB (-12 bis +12). */
    val eqGain: List<Int> = listOf(0, 0, 0),
    /** Verlauf je Band: Einblenden und Ausblenden in Takten, bezogen auf die ganze Zeile. */
    val eqFadeIn: List<Double> = listOf(0.0, 0.0, 0.0),
    val eqFadeOut: List<Double> = listOf(0.0, 0.0, 0.0),
    /** Ausgeschaltete Effekte: "pitch", "delay", "reverb", "radio", "3d" (leer = alle an). */
    val fxOff: Set<String> = emptySet(),
    /** Delay: Decay, Time, Mix (0 bis 1). */
    val delay: List<Double> = FxDefaults.DELAY,
    val delayFadeIn: List<Double> = listOf(0.0, 0.0, 0.0),
    val delayFadeOut: List<Double> = listOf(0.0, 0.0, 0.0),
    /** Reverb: Level (0 bis 1), Verlauf und Raum (0 bis 5). */
    val reverbLevel: Double = 0.0,
    val reverbFadeIn: Double = 0.0,
    val reverbFadeOut: Double = 0.0,
    val reverbPreset: Int = FxDefaults.REVERB_PRESET,
    /** Radio (Overdrive): Gain, Tone (0 bis 1) und Verläufe. */
    val radio: List<Double> = FxDefaults.RADIO,
    val radioFadeIn: List<Double> = listOf(0.0, 0.0),
    val radioFadeOut: List<Double> = listOf(0.0, 0.0),
    /** 3D: Position (0 Mitte, 1 vorne, 2 rechts, 3 links, 4 hinten) und Acid (0 bis 1). */
    val threeDPos: Int = 0,
    val threeDAcid: Double = 0.0,
    /** Acid taktgleich (Stufen passend zum Takt). Standard: an. */
    val acidSync: Boolean = true
) {
    fun tempo(projectBpm: Int): Double = if (bpm > 0.0) bpm else projectBpm.toDouble()

    fun displayName(index: Int): String = name ?: "Musikzeile ${index + 1}"

    val hasEq: Boolean get() = eqGain.any { it != 0 }
    val extentStart: Double get() = clips.minOfOrNull { it.startBar } ?: 0.0
    val extentEnd: Double get() = clips.maxOfOrNull { it.endBar } ?: 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("bpm", bpm)
        put("muted", muted)
        put("volume", volume)
        put("eqGain", JSONArray().apply { eqGain.forEach { put(it) } })
        put("eqFadeIn", JSONArray().apply { eqFadeIn.forEach { put(it) } })
        put("eqFadeOut", JSONArray().apply { eqFadeOut.forEach { put(it) } })
        put("fxOff", JSONArray().apply { fxOff.forEach { put(it) } })
        put("delay", JSONArray().apply { delay.forEach { put(it) } })
        put("delayFadeIn", JSONArray().apply { delayFadeIn.forEach { put(it) } })
        put("delayFadeOut", JSONArray().apply { delayFadeOut.forEach { put(it) } })
        put("reverbLevel", reverbLevel)
        put("reverbFadeIn", reverbFadeIn)
        put("reverbFadeOut", reverbFadeOut)
        put("reverbPreset", reverbPreset)
        put("radio", JSONArray().apply { radio.forEach { put(it) } })
        put("radioFadeIn", JSONArray().apply { radioFadeIn.forEach { put(it) } })
        put("radioFadeOut", JSONArray().apply { radioFadeOut.forEach { put(it) } })
        put("threeDPos", threeDPos)
        put("threeDAcid", threeDAcid)
        put("acidSync", acidSync)
        if (name != null) put("name", name)
        put("clips", JSONArray().apply { clips.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Track {
            val typeName = o.getString("type")
            // Alte Projekte: Trommel und Hitsch sind jetzt Effekt-Zeilen
            val type = when (typeName) {
                "DRUM", "HIHAT" -> TrackType.EFFECT
                else -> TrackType.valueOf(typeName)
            }
            val legacyLane = when (typeName) {
                "DRUM" -> 0
                "HIHAT" -> 1
                else -> -1
            }
            val array = o.getJSONArray("clips")
            val clips = ArrayList<Clip>()
            for (i in 0 until array.length()) {
                clips.add(Clip.fromJson(array.getJSONObject(i), legacyLane, type == TrackType.SYNTH))
            }
            val off = o.optJSONArray("fxOff")
            val fxOff = if (off == null) {
                emptySet()
            } else {
                (0 until off.length()).map { off.getString(it) }.toSet()
            }
            val zero3 = listOf(0.0, 0.0, 0.0)
            val zero2 = listOf(0.0, 0.0)
            return Track(
                id = o.getString("id"),
                type = type,
                clips = clips,
                name = if (o.has("name")) o.getString("name") else null,
                bpm = o.optDouble("bpm", 0.0),
                muted = o.optBoolean("muted", false),
                volume = o.optInt("volume", 100),
                eqGain = o.intList3("eqGain"),
                eqFadeIn = o.doubleList("eqFadeIn", zero3),
                eqFadeOut = o.doubleList("eqFadeOut", zero3),
                fxOff = fxOff,
                delay = o.doubleList("delay", FxDefaults.DELAY),
                delayFadeIn = o.doubleList("delayFadeIn", zero3),
                delayFadeOut = o.doubleList("delayFadeOut", zero3),
                reverbLevel = o.optDouble("reverbLevel", 0.0),
                reverbFadeIn = o.optDouble("reverbFadeIn", 0.0),
                reverbFadeOut = o.optDouble("reverbFadeOut", 0.0),
                reverbPreset = o.optInt("reverbPreset", FxDefaults.REVERB_PRESET),
                radio = o.doubleList("radio", FxDefaults.RADIO),
                radioFadeIn = o.doubleList("radioFadeIn", zero2),
                radioFadeOut = o.doubleList("radioFadeOut", zero2),
                threeDPos = o.optInt("threeDPos", 0),
                threeDAcid = o.optDouble("threeDAcid", 0.0),
                acidSync = o.optBoolean("acidSync", true)
            )
        }
    }
}

fun formatBpm(value: Double): String {
    val rounded = Math.round(value)
    return if (abs(value - rounded) < 0.05) {
        rounded.toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}
