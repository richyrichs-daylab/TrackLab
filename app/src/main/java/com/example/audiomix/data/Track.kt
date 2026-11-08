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
    val fadeOut: Double = 0.0
) {
    val tileEnd: Double get() = startBar + lengthBar

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
        result.add(copy(repeatCount = 0, repeatPartial = 0.0))
        var s = startBar + lengthBar
        for (i in 1..repeatCount) {
            val len = if (i == repeatCount && repeatPartial > 0.0) min(repeatPartial, lengthBar) else lengthBar
            result.add(
                copy(
                    id = if (freshIds) UUID.randomUUID().toString() else id,
                    startBar = s,
                    lengthBar = len,
                    repeatCount = 0,
                    repeatPartial = 0.0
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
                fadeOut = o.optDouble("fadeOut", 0.0)
            )
        }
    }
}

private fun JSONObject.intList3(key: String): List<Int> {
    val a = optJSONArray(key)
    return List(3) { i -> if (a != null && i < a.length()) a.optInt(i, 0) else 0 }
}

private fun JSONObject.doubleList3(key: String): List<Double> {
    val a = optJSONArray(key)
    return List(3) { i -> if (a != null && i < a.length()) a.optDouble(i, 0.0) else 0.0 }
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
    val eqFadeOut: List<Double> = listOf(0.0, 0.0, 0.0)
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
            return Track(
                id = o.getString("id"),
                type = type,
                clips = clips,
                name = if (o.has("name")) o.getString("name") else null,
                bpm = o.optDouble("bpm", 0.0),
                muted = o.optBoolean("muted", false),
                volume = o.optInt("volume", 100),
                eqGain = o.intList3("eqGain"),
                eqFadeIn = o.doubleList3("eqFadeIn"),
                eqFadeOut = o.doubleList3("eqFadeOut")
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
