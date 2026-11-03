package com.example.audiomix.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Exporter {

    /** Speichert das Projekt als WAV im Download-Ordner. Gibt den Dateinamen zurück oder null bei Fehlern. */
    fun export(context: Context, project: Project): String? {
        if (project.tracks.all { it.clips.isEmpty() }) return null

        val appContext = context.applicationContext
        val renderer = MixRenderer(appContext, project.id, project.tracks, project.bpm)
        var createdUri: Uri? = null
        try {
            if (!renderer.prepare()) return null

            val baseName = project.name
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifBlank { "Projekt" }
            val fileName = "$baseName.wav"

            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Datei konnte nicht angelegt werden")
            createdUri = uri

            val stream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Datei konnte nicht geöffnet werden")
            stream.use { out -> writeWav(out, renderer) }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return fileName
        } catch (e: Exception) {
            val uri = createdUri
            if (uri != null) {
                try {
                    appContext.contentResolver.delete(uri, null, null)
                } catch (e2: Exception) {
                    // ignorieren
                }
            }
            return null
        } finally {
            renderer.close()
        }
    }

    private fun writeWav(out: OutputStream, renderer: MixRenderer) {
        val total = renderer.totalFrames
        out.write(wavHeader(total * 4L))

        val chunkFrames = 8192
        val shorts = ShortArray(chunkFrames * 2)
        val bytes = ByteArray(chunkFrames * 4)
        var position = 0L
        while (position < total) {
            val n = minOf(chunkFrames.toLong(), total - position).toInt()
            renderer.render(position, n, shorts)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, n * 2)
            out.write(bytes, 0, n * 4)
            position += n
        }
        out.flush()
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        val rate = MixRenderer.RATE
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36L + dataBytes).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1.toShort())            // PCM
        b.putShort(2.toShort())            // Stereo
        b.putInt(rate)
        b.putInt(rate * 4)
        b.putShort(4.toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray())
        b.putInt(dataBytes.toInt())
        return b.array()
    }
}
