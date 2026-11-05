package com.example.audiomix.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class ClipboardEntry(
    val type: TrackType,
    val clip: Clip,
    val trackTempo: Double,
    val projectBpm: Int
)

object BlockClipboard {

    var entry by mutableStateOf<ClipboardEntry?>(null)

    private fun dir(context: Context) = File(context.filesDir, "clipboard")

    /** Merkt sich den Block samt Audiodatei (so überlebt er auch das Löschen des Projekts). */
    fun store(
        context: Context,
        projectId: String,
        type: TrackType,
        clip: Clip,
        trackTempo: Double,
        projectBpm: Int
    ) {
        val d = dir(context)
        d.deleteRecursively()
        d.mkdirs()
        val name = clip.sourceFile
        if (name != null) {
            val src = AudioFiles.dir(context, projectId)
            val file = File(src, name)
            if (file.exists()) file.copyTo(File(d, name), overwrite = true)
            val peaks = name.substringBeforeLast('.') + ".peaks"
            val peaksFile = File(src, peaks)
            if (peaksFile.exists()) peaksFile.copyTo(File(d, peaks), overwrite = true)
        }
        entry = ClipboardEntry(type, clip, trackTempo, projectBpm)
    }

    /** Kopiert die Audiodatei des Blocks ins Zielprojekt. */
    fun restoreFiles(context: Context, projectId: String, clip: Clip): Boolean {
        val name = clip.sourceFile ?: return true
        val d = dir(context)
        val target = AudioFiles.dir(context, projectId)
        val dst = File(target, name)
        if (!dst.exists()) {
            val src = File(d, name)
            if (!src.exists()) return false
            src.copyTo(dst)
        }
        val peaks = name.substringBeforeLast('.') + ".peaks"
        val dstPeaks = File(target, peaks)
        val srcPeaks = File(d, peaks)
        if (!dstPeaks.exists() && srcPeaks.exists()) srcPeaks.copyTo(dstPeaks)
        return true
    }
}
