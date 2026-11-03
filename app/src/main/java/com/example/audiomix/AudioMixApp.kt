package com.example.audiomix

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.audiomix.data.ProjectRepository
import com.example.audiomix.data.SettingsStore
import com.example.audiomix.ui.AudioMixTheme
import com.example.audiomix.ui.ProjectScreen
import com.example.audiomix.ui.SettingsScreen
import com.example.audiomix.ui.StartScreen

@Composable
fun AudioMixApp(repo: ProjectRepository, settings: SettingsStore) {
    val context = LocalContext.current

    var darkMode by remember { mutableStateOf(settings.darkMode) }
    var screen by rememberSaveable { mutableStateOf("start") }
    var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var projects by remember { mutableStateOf(repo.loadAll()) }

    fun reload() {
        projects = repo.loadAll()
    }

    val openProject = projects.firstOrNull { it.id == openProjectId }
    val currentScreen = if (screen == "project" && openProject == null) "start" else screen

    BackHandler(enabled = currentScreen != "start") {
        reload()
        screen = "start"
    }

    AudioMixTheme(darkMode) {
        when (currentScreen) {
            "settings" -> SettingsScreen(
                darkMode = darkMode,
                onDarkModeChange = {
                    darkMode = it
                    settings.darkMode = it
                }
            )

            "project" -> ProjectScreen(
                project = openProject!!,
                onRename = { newName ->
                    repo.rename(openProject.id, newName)
                    reload()
                },
                onClose = {
                    reload()
                    screen = "start"
                }
            )

            else -> StartScreen(
                projects = projects,
                onNewProject = {
                    val project = repo.create()
                    reload()
                    openProjectId = project.id
                    screen = "project"
                },
                onOpenProject = { id ->
                    openProjectId = id
                    screen = "project"
                },
                onRename = { id, newName ->
                    repo.rename(id, newName)
                    reload()
                },
                onDelete = { id ->
                    repo.delete(id)
                    reload()
                },
                onExport = {
                    Toast.makeText(context, "Noch kein Audio vorhanden", Toast.LENGTH_SHORT).show()
                },
                onOpenSettings = { screen = "settings" }
            )
        }
    }
}
