package com.example.audiomix.data

import org.json.JSONObject

data class Project(
    val id: String,
    val name: String,
    val lastModified: Long,
    val durationMs: Long = 0L,
    val bpm: Int = 100
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("lastModified", lastModified)
        put("durationMs", durationMs)
        put("bpm", bpm)
    }.toString()

    companion object {
        fun fromJson(text: String): Project {
            val o = JSONObject(text)
            return Project(
                id = o.getString("id"),
                name = o.getString("name"),
                lastModified = o.getLong("lastModified"),
                durationMs = o.optLong("durationMs", 0L),
                bpm = o.optInt("bpm", 100)
            )
        }
    }
}
