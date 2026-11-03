package com.example.audiomix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.audiomix.data.ProjectRepository
import com.example.audiomix.data.SettingsStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = ProjectRepository(applicationContext)
        val settings = SettingsStore(applicationContext)
        setContent {
            AudioMixApp(repo, settings)
        }
    }
}
