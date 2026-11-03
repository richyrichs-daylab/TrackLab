package com.example.audiomix.data

import android.content.Context
import java.io.File
import java.util.UUID

class ProjectRepository(context: Context) {

    private val dir = File(context.filesDir, "projects").apply { mkdirs() }

    fun loadAll(): List<Project> =
        dir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { Project.fromJson(file.readText()) }.getOrNull() }
            .sortedByDescending { it.lastModified }

    fun get(id: String): Project? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return runCatching { Project.fromJson(file.readText()) }.getOrNull()
    }

    fun save(project: Project) {
        File(dir, "${project.id}.json").writeText(project.toJson())
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    /** Legt "Projekt 1", "Projekt 2" ... an; bereits benutzte Nummern werden übersprungen. */
    fun create(): Project {
        val usedNames = loadAll().map { it.name }.toSet()
        var number = 1
        while ("Projekt $number" in usedNames) number++
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = "Projekt $number",
            lastModified = System.currentTimeMillis()
        )
        save(project)
        return project
    }

    fun rename(id: String, newName: String) {
        val project = get(id) ?: return
        val name = newName.trim().ifBlank { project.name }
        save(project.copy(name = name, lastModified = System.currentTimeMillis()))
    }
}
