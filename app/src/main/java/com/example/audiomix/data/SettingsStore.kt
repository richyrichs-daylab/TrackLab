package com.example.audiomix.data

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var darkMode: Boolean
        get() = prefs.getBoolean("darkMode", false)
        set(value) {
            prefs.edit().putBoolean("darkMode", value).apply()
        }
}
