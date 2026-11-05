package com.example.audiomix.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

enum class TrackType { AUDIO, DRUM, HIHAT }

data class Clip(
    val id: String,
    val startBar: Double,
    val lengthBar: Double,
    val offsetBar: Double = 0.0,
    val sourceFile: String? = null,
    val sourceLengthBar: Double = 0.0,
    /** Tempo der Original-Datei (bpm). */
    val sourceBpm: Double = 100.0
) {
    val endBar: Double get() = startBar + lengthBar

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startBar", startBar)
        put("lengthBar", lengthBar)
        put("offsetBar", offsetBar)
        put("sourceLengthBar", sourceLengthBar)
        put("sourceBpm", sourceBpm)
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
            sourceBpm = o.optDouble("sourceBpm", 100.0)
        )
    }
}

data class Track(
    val id: String,
    val type: TrackType,
    val clips: List<Clip>,
    /** Eigener Name der Zeile (null = Standardname "Musikzeile N"). */
    val name: String? = null,
    /** Tempo der Zeile in bpm (nur Audiozeilen; 0 = folgt dem Projekt). */
    val bpm: Double = 0.0,
    val muted: Boolean = false,
    /** Lautstärke in Prozent (0 bis 200). */
    val volume: Int = 100
) {
    /** Das Tempo, in dem diese Zeile tatsächlich klingt. */
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

/** Zeigt ganze Zahlen ohne Nachkommastelle, sonst mit einer. */
fun formatBpm(value: Double): String {
    val rounded = Math.round(value)
    return if (abs(value - rounded) < 0.05) {
        rounded.toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}
