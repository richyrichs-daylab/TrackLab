package com.example.audiomix.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
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

/** Schreibt 16-Bit-Werte schnell (little endian) in einen Stream. */
class PcmWriter(private val stream: OutputStream) {
    private val buf = ByteArray(65536)
    private var n = 0

    fun write16(v: Int) {
        if (n + 2 > buf.size) flush()
        buf[n++] = (v and 0xFF).toByte()
        buf[n++] = ((v shr 8) and 0xFF).toByte()
    }

    fun flush() {
        if (n > 0) stream.write(buf, 0, n)
        n = 0
    }
}

object AudioImport {

    const val PEAKS_PER_SECOND = 50
    const val TARGET_RATE = 44100

    class Imported(val fileName: String, val durationSec: Double)

    private class Analysis(val durationSec: Double, val peaks: ByteArray)

    /** Kopiert die gewählte Datei (MP3, WAV, Opus ...) ins Projekt. Null, wenn sie nicht lesbar ist. */
    fun importAudio(context: Context, uri: Uri, projectId: String): Imported? {
        val dir = AudioFiles.dir(context, projectId)
        val id = UUID.randomUUID().toString()
        val label = displayName(context, uri)
        var ext = label?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext.length !in 2..5 || !ext.all { it.isLetterOrDigit() } || ext == "pcm" || ext == "peaks" || ext == "tmp") {
            ext = "bin"
        }
        val target = File(dir, "$id.$ext")
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
            ensurePcm(context, projectId, target.name)
            return Imported(target.name, analysis.durationSec)
        } catch (e: Exception) {
            target.delete()
            return null
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** Liefert die mischfertige Rohdatei (44,1 kHz, Stereo, 16 Bit) und erzeugt sie bei Bedarf. */
    fun ensurePcm(context: Context, projectId: String, fileName: String): File? {
        val dir = AudioFiles.dir(context, projectId)
        val source = File(dir, fileName)
        val pcm = File(dir, fileName.substringBeforeLast('.') + ".pcm")
        if (pcm.exists() && pcm.length() > 0L) return pcm
        if (!source.exists()) return null
        val tmp = File(dir, pcm.name + ".tmp")
        if (!convertToPcm(source, tmp)) {
            tmp.delete()
            return null
        }
        tmp.renameTo(pcm)
        return pcm
    }

    private fun analyze(file: File): Analysis? {
        val peaks = ByteArrayOutputStream()
        var window = 882
        var inWindow = 0
        var windowMax = 0
        var frames = 0L
        var rate = 0
        val ok = decodeFrames(file, { r ->
            rate = r
            window = maxOf(1, r / PEAKS_PER_SECOND)
        }) { l, r ->
            val m = maxOf(abs(l), abs(r))
            if (m > windowMax) windowMax = m
            inWindow++
            frames++
            if (inWindow >= window) {
                peaks.write(minOf(255, windowMax * 255 / 32768))
                inWindow = 0
                windowMax = 0
            }
        }
        if (!ok || rate <= 0) return null
        if (inWindow > 0) peaks.write(minOf(255, windowMax * 255 / 32768))
        val duration = frames.toDouble() / rate
        return if (duration > 0.0) Analysis(duration, peaks.toByteArray()) else null
    }

    private fun convertToPcm(source: File, outFile: File): Boolean {
        var fileStream: FileOutputStream? = null
        try {
            val stream = FileOutputStream(outFile)
            fileStream = stream
            val writer = PcmWriter(stream)
            val resampler = StreamResampler(TARGET_RATE, TARGET_RATE)
            val ok = decodeFrames(source, { r -> resampler.setInRate(r, TARGET_RATE) }) { l, r ->
                resampler.feed(l, r) { ol, orr ->
                    writer.write16(ol)
                    writer.write16(orr)
                }
            }
            if (!ok) return false
            writer.flush()
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try {
                fileStream?.close()
            } catch (e: Exception) {
                // ignorieren
            }
        }
    }

    // ---------- Dekodieren ----------

    private fun decodeFrames(file: File, onRate: (Int) -> Unit, onFrame: (Int, Int) -> Unit): Boolean =
        if (isWav(file)) decodeWav(file, onRate, onFrame) else decodeWithCodec(file, onRate, onFrame)

    private fun isWav(file: File): Boolean = try {
        file.inputStream().use { s ->
            val h = ByteArray(12)
            s.read(h) == 12 && h.decodeToString(0, 4) == "RIFF" && h.decodeToString(8, 12) == "WAVE"
        }
    } catch (e: Exception) {
        false
    }

    private fun decodeWav(file: File, onRate: (Int) -> Unit, onFrame: (Int, Int) -> Unit): Boolean =
        try {
            RandomAccessFile(file, "r").use { raf -> decodeWavFrom(raf, onRate, onFrame) }
        } catch (e: Exception) {
            false
        }

    private fun decodeWavFrom(raf: RandomAccessFile, onRate: (Int) -> Unit, onFrame: (Int, Int) -> Unit): Boolean {
        raf.seek(12)
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1L
        var dataLen = 0L
        val head = ByteArray(8)
        while (raf.filePointer + 8 <= raf.length()) {
            raf.readFully(head)
            val id = head.decodeToString(0, 4)
            val size = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val pos = raf.filePointer
            if (id == "fmt ") {
                val f = ByteArray(minOf(size, 40L).toInt())
                raf.readFully(f)
                val b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                format = b.short.toInt() and 0xFFFF
                channels = b.short.toInt()
                rate = b.int
                b.int
                b.short
                bits = b.short.toInt()
                if (format == 0xFFFE && f.size >= 26) {
                    format = (f[24].toInt() and 0xFF) or ((f[25].toInt() and 0xFF) shl 8)
                }
            } else if (id == "data") {
                dataStart = pos
                dataLen = minOf(size, raf.length() - pos)
                break
            }
            raf.seek(pos + size + (size and 1L))
        }
        if (dataStart < 0 || channels < 1 || rate < 1 || (format != 1 && format != 3)) return false
        val bytes = bits / 8
        val frameBytes = bytes * channels
        if (bytes < 1 || bytes > 4 || frameBytes < 1) return false
        if (format == 3 && bytes != 4) return false

        onRate(rate)
        raf.seek(dataStart)
        val buf = ByteArray(frameBytes * 4096)
        var remaining = dataLen / frameBytes * frameBytes
        while (remaining > 0) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            raf.readFully(buf, 0, want)
            val bb = ByteBuffer.wrap(buf, 0, want).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < want) {
                val l = readSample(bb, i, bytes, format == 3)
                val r = if (channels >= 2) readSample(bb, i + bytes, bytes, format == 3) else l
                onFrame(l, r)
                i += frameBytes
            }
            remaining -= want
        }
        return true
    }

    private fun readSample(bb: ByteBuffer, pos: Int, bytes: Int, isFloat: Boolean): Int = when {
        isFloat -> (bb.getFloat(pos) * 32767f).toInt().coerceIn(-32768, 32767)
        bytes == 1 -> ((bb.get(pos).toInt() and 0xFF) - 128) shl 8
        bytes == 2 -> bb.getShort(pos).toInt()
        bytes == 3 -> (bb.get(pos + 2).toInt() shl 8) or (bb.get(pos + 1).toInt() and 0xFF)
        else -> bb.getInt(pos) shr 16
    }

    private fun decodeWithCodec(file: File, onRate: (Int) -> Unit, onFrame: (Int, Int) -> Unit): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var foundFormat: MediaFormat? = null
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    trackIndex = i
                    foundFormat = f
                    mime = m
                    break
                }
            }
            val format = foundFormat ?: return false
            if (trackIndex < 0) return false
            extractor.selectTrack(trackIndex)

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            onRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
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
                            val l = shorts.get(i).toInt()
                            val r = if (channels >= 2) shorts.get(i + 1).toInt() else l
                            onFrame(l, r)
                            i += channels
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    onRate(newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                }
            }
            return true
        } catch (e: Exception) {
            return false
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
