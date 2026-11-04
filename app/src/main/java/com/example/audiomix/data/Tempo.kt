package com.example.audiomix.data

import android.content.Context
import java.io.File
import kotlin.math.roundToInt

object StretchFiles {

    /** Kennzahl für ein Tempoverhältnis (10000 = unverändert). */
    fun key(speed: Double): Int = (speed * 10000.0).roundToInt()

    fun isOriginal(speed: Double): Boolean = key(speed) == 10000

    /** Dateiname der (gegebenenfalls gedehnten) Rohdatei. */
    fun fileNameFor(fileName: String, speed: Double): String {
        val base = fileName.substringBeforeLast('.')
        return if (isOriginal(speed)) "$base.pcm" else "${base}_${key(speed)}.pcm"
    }

    /** Liefert die Rohdatei im gewünschten Tempo und erzeugt sie bei Bedarf. */
    fun ensure(
        context: Context,
        projectId: String,
        fileName: String,
        speed: Double,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): File? {
        val original = AudioImport.ensurePcm(context, projectId, fileName) ?: return null
        if (isOriginal(speed)) return original

        val dir = AudioFiles.dir(context, projectId)
        val target = File(dir, fileNameFor(fileName, speed))
        if (target.exists() && target.length() > 0L) return target

        val tmp = File(dir, target.name + ".tmp")
        val ok = TimeStretch.stretch(original, tmp, speed, onProgress, isCancelled)
        if (!ok) {
            tmp.delete()
            return null
        }
        tmp.renameTo(target)
        return target
    }

    /** Löscht gedehnte Dateien, die nicht mehr gebraucht werden. */
    fun cleanup(context: Context, projectId: String, keep: Set<String>) {
        val dir = AudioFiles.dir(context, projectId)
        for (f in dir.listFiles().orEmpty()) {
            val n = f.name
            val isStretched = n.contains('_') && n.endsWith(".pcm")
            if ((isStretched || n.endsWith(".tmp")) && n !in keep) {
                f.delete()
            }
        }
    }

    /** Löscht alle Dateien einer importierten MP3 (z. B. wenn der Import abgebrochen wird). */
    fun deleteAll(context: Context, projectId: String, fileName: String) {
        val base = fileName.substringBeforeLast('.')
        val dir = AudioFiles.dir(context, projectId)
        for (f in dir.listFiles().orEmpty()) {
            if (f.name.startsWith(base)) f.delete()
        }
    }
}
