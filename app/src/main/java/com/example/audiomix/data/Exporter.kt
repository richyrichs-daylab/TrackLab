package com.example.audiomix.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ExportFormat(val label: String, val ext: String) {
    WAV("WAV", "wav"),
    MP3("MP3", "mp3"),
    OPUS("Opus", "opus")
}

object Exporter {

    /** Speichert das Projekt im Download-Ordner. Gibt den Dateinamen zurück oder null bei Fehlern. */
    fun export(context: Context, project: Project, format: ExportFormat): String? {
        if (format == ExportFormat.MP3) return null
        if (project.tracks.all { it.clips.isEmpty() }) return null

        val app = context.applicationContext
        val renderer = MixRenderer(app, project.id, project.tracks, project.bpm)
        var createdUri: Uri? = null
        try {
            if (!renderer.prepare()) return null

            val baseName = project.name
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifBlank { "Projekt" }
            val fileName = "$baseName.${format.ext}"

            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    if (format == ExportFormat.WAV) "audio/x-wav" else "audio/ogg"
                )
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Datei konnte nicht angelegt werden")
            createdUri = uri

            if (format == ExportFormat.WAV) {
                val stream = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Datei konnte nicht geöffnet werden")
                stream.use { writeWav(it, renderer) }
            } else {
                val pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: throw IllegalStateException("Datei konnte nicht geöffnet werden")
                pfd.use { writeOpus(app, it.fileDescriptor, renderer) }
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
        b.putShort(1.toShort())
        b.putShort(2.toShort())
        b.putInt(rate)
        b.putInt(rate * 4)
        b.putShort(4.toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray())
        b.putInt(dataBytes.toInt())
        return b.array()
    }

    /** Opus in einer Ogg-Datei (braucht Android 10 oder neuer). */
    private fun writeOpus(context: Context, fd: FileDescriptor, renderer: MixRenderer) {
        val outRate = 48000
        val temp = File.createTempFile("opus", ".pcm", context.cacheDir)
        try {
            // 1) mischen und auf 48 kHz umrechnen
            FileOutputStream(temp).use { fos ->
                val writer = PcmWriter(fos)
                val resampler = StreamResampler(MixRenderer.RATE, outRate)
                val shorts = ShortArray(4096 * 2)
                val total = renderer.totalFrames
                var pos = 0L
                while (pos < total) {
                    val n = minOf(4096L, total - pos).toInt()
                    renderer.render(pos, n, shorts)
                    for (i in 0 until n) {
                        resampler.feed(shorts[i * 2].toInt(), shorts[i * 2 + 1].toInt()) { l, r ->
                            writer.write16(l)
                            writer.write16(r)
                        }
                    }
                    pos += n
                }
                writer.flush()
            }

            // 2) kodieren
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, outRate, 2)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            var muxer: MediaMuxer? = null
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val mux = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
                muxer = mux
                var trackIndex = -1
                var started = false
                val info = MediaCodec.BufferInfo()
                val chunk = ByteArray(16384)
                var fed = 0L
                var inputDone = false
                var outputDone = false
                FileInputStream(temp).use { input ->
                    while (!outputDone) {
                        if (!inputDone) {
                            val inIdx = codec.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val buf = codec.getInputBuffer(inIdx)
                                if (buf != null) {
                                    buf.clear()
                                    val want = (minOf(buf.capacity(), chunk.size) / 4) * 4
                                    var n = if (want > 0) input.read(chunk, 0, want) else -1
                                    if (n > 0) n -= n % 4
                                    val pts = fed * 1_000_000L / outRate
                                    if (n <= 0) {
                                        codec.queueInputBuffer(
                                            inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        inputDone = true
                                    } else {
                                        buf.put(chunk, 0, n)
                                        codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                                        fed += n / 4
                                    }
                                }
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            trackIndex = mux.addTrack(codec.outputFormat)
                            mux.start()
                            started = true
                        } else if (outIdx >= 0) {
                            val ob = codec.getOutputBuffer(outIdx)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                            if (ob != null && info.size > 0 && started) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                mux.writeSampleData(trackIndex, ob, info)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        }
                    }
                }
                if (!started) throw IllegalStateException("Der Opus-Encoder lieferte keine Daten")
                mux.stop()
            } finally {
                try {
                    codec.stop()
                    codec.release()
                } catch (e: Exception) {
                    // ignorieren
                }
                try {
                    muxer?.release()
                } catch (e: Exception) {
                    // ignorieren
                }
            }
        } finally {
            temp.delete()
        }
    }
}
