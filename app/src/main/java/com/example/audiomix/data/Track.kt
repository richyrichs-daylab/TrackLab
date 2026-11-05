package com.example.audiomix.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

enum class TrackType { AUDIO, DRUM, HIHAT }

data class Clip(
    val id: String,
    val startBar: Double,
    val lengthBar: Double,
    val offsetBar: Double = 0.0,
    val sourceFile: String? = null,
    val sourceLengthBar: Double = 0.0,
    /** Tempo der Original-Datei (bpm). */
    val sourceBpm: Double = 100.0,
    /** Tempo-Faktor dieses Blocks (1 = wie die Zeile, 2 = doppelt so schnell). */
    val stretch: Double = 1.0,
    /** Wiederholungen als Vielfaches der Blocklänge (0 = keine). */
    val repeats: Double = 0.0,
    val muted: Boolean = false
) {
    /** Ende des eigentlichen Blocks (ohne Wiederholungen). */
    val tileEnd: Double get() = startBar + lengthBar

    /** Ende inklusive aller Wiederholungen. */
    val endBar: Double get() = startBar + lengthBar * (1.0 + repeats)

    fun speed(trackTempo: Double): Double = trackTempo / sourceBpm * stretch

    /** Der Block und seine Wiederholungen als einzelne Teile. */
    fun tiles(freshIds: Boolean = false): List<Clip> {
        if (repeats <= 1e-9) return listOf(copy(repeats = 0.0))
        val result = ArrayList<Clip>()
        val end = endBar
        var i = 0
        while (true) {
            val s = startBar + i * lengthBar
            val len = min(lengthBar, end - s)
            if (len < 1e-6) break
            result.add(
                copy(
                    id = if (i == 0 || !freshIds) id else UUID.randomUUID().toString(),
                    startBar = s,
                    lengthBar = len,
                    repeats = 0.0
                )
            )
            i++
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
        put("repeats", repeats)
        put("muted", muted)
        if (sourceFile != null) put("sourceFile", sourceFile)
    }

    companion object {
        fun fromJson(o: JSONObject): Clip = Clip(
            id = o.getString("id"),
            startBar = o.getDouble("startBar"),
            lengthBar = o.getDouble("lengthBar"),
            offsetBar = o.optDouble("offsetBar", 0.0),
            sourceFile = if (o.has("sourceFile")) o.getString("sourceFile") else null,
            sourceLengthBar = o.optDouble("sourceLengthBar", 0.0),
            sourceBpm = o.optDouble("sourceBpm", 100.0),
            stretch = o.optDouble("stretch", 1.0),
            repeats = o.optDouble("repeats", 0.0),
            muted = o.optBoolean("muted", false)
        )
    }
}

data class Track(
    val id: String,
    val type: TrackType,
    val clips: List<Clip>,
    val name: String? = null,
    val bpm: Double = 0.0,
    val muted: Boolean = false,
    val volume: Int = 100
) {
    fun tempo(projectBpm: Int): Double = if (bpm > 0.0) bpm else projectBpm.toDouble()

    fun displayName(index: Int): String = name ?: "Musikzeile ${index + 1}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("bpm", bpm)
        put("muted", muted)
        put("volume", volume)
        if (name != null) put("name", name)
        put("clips", JSONArray().apply { clips.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Track {
            val array = o.getJSONArray("clips")
            val clips = ArrayList<Clip>()
            for (i in 0 until array.length()) {
                clips.add(Clip.fromJson(array.getJSONObject(i)))
            }
            return Track(
                id = o.getString("id"),
                type = TrackType.valueOf(o.getString("type")),
                clips = clips,
                name = if (o.has("name")) o.getString("name") else null,
                bpm = o.optDouble("bpm", 0.0),
                muted = o.optBoolean("muted", false),
                volume = o.optInt("volume", 100)
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
