package com.example.audiomix.data

import org.json.JSONArray
import org.json.JSONObject

enum class TrackType { AUDIO, DRUM }

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
    val clips: List<Clip>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
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
                clips = clips
            )
        }
    }
}
