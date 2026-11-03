package com.example.audiomix.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs

object AudioFiles {
    fun dir(context: Context, projectId: String): File =
        File(context.filesDir, "audio/$projectId").apply { mkdirs() }

    fun loadPeaks(context: Context, projectId: String, fileName: String): ByteArray? {
        val file = File(dir(context, projectId), fileName.substringBeforeLast('.') + ".peaks")
        return if (file.exists()) file.readBytes() else null
    }
}

object AudioImport {

    /** Wie viele Wellenform-Werte pro Sekunde gespeichert werden. */
    const val PEAKS_PER_SECOND = 50

    class Imported(val fileName: String, val durationSec: Double)

    private class Analysis(val durationSec: Double, val peaks: ByteArray)

    /** Kopiert die gewählte MP3 ins Projekt. Gibt null zurück, wenn es keine lesbare MP3 ist. */
    fun importMp3(context: Context, uri: Uri, projectId: String): Imported? {
        val dir = AudioFiles.dir(context, projectId)
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.mp3")
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            stream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val analysis = analyze(target)
            if (analysis == null) {
                target.delete()
                return null
            }
            File(dir, "$id.peaks").writeBytes(analysis.peaks)
            return Imported(target.name, analysis.durationSec)
        } catch (e: Exception) {
            target.delete()
            return null
        }
    }

    private fun analyze(file: File): Analysis? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)

            var trackIndex = -1
            var foundFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_MPEG) {
                    trackIndex = i
                    foundFormat = f
                    break
                }
            }
            val format = foundFormat ?: return null
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            val peaks = ByteArrayOutputStream()
            var windowFrames = maxOf(1, sampleRate / PEAKS_PER_SECOND)
            var inWindow = 0
            var windowMax = 0
            var totalFrames = 0L
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val count = shorts.remaining()
                        var i = 0
                        while (i + channels <= count) {
                            var frameMax = 0
                            for (ch in 0 until channels) {
                                val v = abs(shorts.get(i + ch).toInt())
                                if (v > frameMax) frameMax = v
                            }
                            if (frameMax > windowMax) windowMax = frameMax
                            inWindow++
                            totalFrames++
                            if (inWindow >= windowFrames) {
                                peaks.write(minOf(255, windowMax * 255 / 32768))
                                inWindow = 0
                                windowMax = 0
                            }
                            i += channels
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    windowFrames = maxOf(1, sampleRate / PEAKS_PER_SECOND)
                }
            }
            if (inWindow > 0) {
                peaks.write(minOf(255, windowMax * 255 / 32768))
            }

            val duration = totalFrames.toDouble() / sampleRate
            if (duration <= 0.0) return null
            return Analysis(duration, peaks.toByteArray())
        } catch (e: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
                // ignorieren
            }
            extractor.release()
        }
    }
}
