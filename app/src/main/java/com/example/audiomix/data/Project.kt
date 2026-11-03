package com.example.audiomix.data

import org.json.JSONArray
import org.json.JSONObject

data class Project(
    val id: String,
    val name: String,
    val lastModified: Long,
    val durationMs: Long = 0L,
    val bpm: Int = 100,
    val tracks: List<Track> = emptyList()
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("lastModified", lastModified)
        put("durationMs", durationMs)
        put("bpm", bpm)
        put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })
    }.toString()

    companion object {
        fun fromJson(text: String): Project {
            val o = JSONObject(text)
            val array = o.optJSONArray("tracks")
            val tracks = ArrayList<Track>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    tracks.add(Track.fromJson(array.getJSONObject(i)))
                }
            }
            return Project(
                id = o.getString("id"),
                name = o.getString("name"),
                lastModified = o.getLong("lastModified"),
                durationMs = o.optLong("durationMs", 0L),
                bpm = o.optInt("bpm", 100),
                tracks = tracks
            )
        }
    }
}
