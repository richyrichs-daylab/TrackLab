package com.example.audiomix.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Backup {

    /** Packt alle Projekte samt MP3s in eine ZIP-Datei im Download-Ordner. Gibt den Dateinamen zurück. */
    fun export(context: Context): String? {
        val app = context.applicationContext
        val projectsDir = File(app.filesDir, "projects")
        val projectFiles = projectsDir.listFiles { f -> f.extension == "json" }.orEmpty()
        if (projectFiles.isEmpty()) return null

        var createdUri: Uri? = null
        try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
            val fileName = "AudioMix-Backup-$date.zip"

            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Datei konnte nicht angelegt werden")
            createdUri = uri
            val stream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Datei konnte nicht geöffnet werden")

            ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                for (file in projectFiles) {
                    addFile(zip, "projects/${file.name}", file)
                }
                val audioRoot = File(app.filesDir, "audio")
                for (dir in audioRoot.listFiles { f -> f.isDirectory }.orEmpty()) {
                    if (!File(projectsDir, "${dir.name}.json").exists()) continue
                    for (file in dir.listFiles().orEmpty()) {
                                                if (file.isFile && file.extension != "pcm" && file.extension != "tmp") {
                            addFile(zip, "audio/${dir.name}/${file.name}", file)
                        }
                    }
                }
            }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return fileName
        } catch (e: Exception) {
            val uri = createdUri
            if (uri != null) {
                try {
                    app.contentResolver.delete(uri, null, null)
                } catch (e2: Exception) {
                    // ignorieren
                }
            }
            return null
        }
    }

    /**
     * Liest eine Backup-ZIP ein und ergänzt fehlende Projekte (vorhandene bleiben unverändert).
     * Gibt die Zahl der wiederhergestellten Projekte zurück, bei einem Fehler -1.
     */
    fun restore(context: Context, uri: Uri): Int {
        val app = context.applicationContext
        val projectsDir = File(app.filesDir, "projects").apply { mkdirs() }
        val audioRoot = File(app.filesDir, "audio").apply { mkdirs() }
        val existingIds = projectsDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .toSet()

        val idRegex = Regex("[A-Za-z0-9-]{1,64}")
        val nameRegex = Regex("[A-Za-z0-9._-]{1,128}")
        var imported = 0

        try {
            val input = app.contentResolver.openInputStream(uri) ?: return -1
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val parts = entry.name.split("/")

                        if (parts.size == 2 && parts[0] == "projects" && parts[1].endsWith(".json")) {
                            val id = parts[1].removeSuffix(".json")
                            if (idRegex.matches(id) && id !in existingIds) {
                                val text = zip.readBytes().toString(Charsets.UTF_8)
                                val project = runCatching { Project.fromJson(text) }.getOrNull()
                                if (project != null && project.id == id) {
                                    File(projectsDir, "$id.json").writeText(text)
                                    imported++
                                }
                            }
                        } else if (parts.size == 3 && parts[0] == "audio") {
                            val id = parts[1]
                            val name = parts[2]
                            if (idRegex.matches(id) && nameRegex.matches(name) && id !in existingIds) {
                                val dir = File(audioRoot, id).apply { mkdirs() }
                                File(dir, name).outputStream().use { out -> zip.copyTo(out) }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return -1
        }
        return imported
    }

    private fun addFile(zip: ZipOutputStream, entryName: String, file: File) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
