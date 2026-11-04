package com.example.audiomix

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.audiomix.data.Exporter
import com.example.audiomix.data.ProjectRepository
import com.example.audiomix.data.SettingsStore
import com.example.audiomix.ui.AudioMixTheme
import com.example.audiomix.ui.ProjectScreen
import com.example.audiomix.ui.SettingsScreen
import com.example.audiomix.ui.StartScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AudioMixApp(repo: ProjectRepository, settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                },
                onClose = {
                    reload()
                    screen = "start"
                }
            )

            "project" -> ProjectScreen(
                project = openProject!!,
                onSave = { repo.save(it) },
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
                onExport = { id ->
                    val project = repo.get(id)
                    if (project == null || project.tracks.all { it.clips.isEmpty() }) {
                        Toast.makeText(context, "Noch kein Audio vorhanden", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Export läuft …", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val name = withContext(Dispatchers.IO) { Exporter.export(context, project) }
                            val message = if (name != null) {
                                "Gespeichert in Downloads: $name"
                            } else {
                                "Export fehlgeschlagen"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onOpenSettings = { screen = "settings" }
            )
        }
    }
}
