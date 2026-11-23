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

    /** Wie viele Dateien gerade im Hintergrund für das Abspielen vorbereitet werden. */
    val preparing = java.util.concurrent.atomic.AtomicInteger(0)

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Kopiert die gewählte Datei (MP3, WAV, Opus ...) ins Projekt. Null, wenn sie nicht lesbar ist.
     * Die Datei ist sofort bearbeitbar; Wellenform und Abspiel-Datei entstehen im Hintergrund.
     */
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
                target.outputStream().buffered(262144).use { output -> input.copyTo(output, 262144) }
            }
            val quick = quickDuration(target)
            if (quick != null) {
                // Sofort fertig; das Dekodieren läuft im Hintergrund weiter
                preparing.incrementAndGet()
                Thread {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                        ensurePcm(context, projectId, target.name)
                    } catch (e: Exception) {
                        // ignorieren
                    } finally {
                        preparing.decrementAndGet()
                    }
                }.start()
                return Imported(target.name, quick)
            }
            // Dauer unbekannt: einmal ganz dekodieren
            val analysis = synchronized(lockFor(dir, target.name)) { convertAll(dir, target) }
            if (analysis == null) {
                target.delete()
                return null
            }
            return Imported(target.name, analysis.durationSec)
        } catch (e: Exception) {
            target.delete()
            return null
        }
    }

    private fun lockFor(dir: File, fileName: String): Any =
        locks.getOrPut(File(dir, fileName.substringBeforeLast('.')).path) { Any() }

    /** Dauer in Sekunden direkt aus der Datei (ohne Dekodieren), oder null. */
    private fun quickDuration(file: File): Double? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var result: Double? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/") && f.containsKey(MediaFormat.KEY_DURATION)) {
                    val us = f.getLong(MediaFormat.KEY_DURATION)
                    if (us > 0L) result = us / 1_000_000.0
                    break
                }
            }
            result
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /** Dekodiert einmal ganz: schreibt Rohdatei und Wellenform. */
    private fun convertAll(dir: File, source: File): Analysis? {
        val base = source.name.substringBeforeLast('.')
        val pcm = File(dir, "$base.pcm")
        val tmp = File(dir, pcm.name + ".tmp")
        val analysis = analyzeAndConvert(source, tmp)
        if (analysis == null || !source.exists()) {
            tmp.delete()
            return null
        }
        File(dir, "$base.peaks").writeBytes(analysis.peaks)
        tmp.renameTo(pcm)
        return analysis
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** Liefert die mischfertige Rohdatei (44,1 kHz, Stereo, 16 Bit) und erzeugt sie bei Bedarf (wartet auf eine laufende Vorbereitung). */
    fun ensurePcm(context: Context, projectId: String, fileName: String): File? {
        val dir = AudioFiles.dir(context, projectId)
        val source = File(dir, fileName)
        val pcm = File(dir, fileName.substringBeforeLast('.') + ".pcm")
        if (pcm.exists() && pcm.length() > 0L) return pcm
        synchronized(lockFor(dir, fileName)) {
            if (pcm.exists() && pcm.length() > 0L) return pcm
            if (!source.exists()) return null
            return if (convertAll(dir, source) != null) pcm else null
        }
    }

    /** Dekodiert die Datei einmal und liefert Wellenform und Rohdatei (44,1 kHz, Stereo, 16 Bit) zugleich. */
    private fun analyzeAndConvert(file: File, outFile: File): Analysis? {
        val peaks = ByteArrayOutputStream()
        val stream = FileOutputStream(outFile)
        val writer = PcmWriter(stream)
        var rate = 0
        var window = 882
        var inWindow = 0
        var windowMax = 0
        var frames = 0L

        // Umrechnung der Abtastrate (lineare Interpolation), ohne Umwege über Lambdas
        var ratio = 1.0
        var inIndex = 0L
        var outCount = 0L
        var prevL = 0
        var prevR = 0

        try {
            val ok = decodeFrames(file, { r ->
                rate = r
                window = maxOf(1, r / PEAKS_PER_SECOND)
                ratio = r.toDouble() / TARGET_RATE
            }, BlockSink { buf, n ->
                var i = 0
                while (i < n) {
                    val l = buf[i * 2].toInt()
                    val r = buf[i * 2 + 1].toInt()

                    val m = maxOf(abs(l), abs(r))
                    if (m > windowMax) windowMax = m
                    inWindow++
                    if (inWindow >= window) {
                        peaks.write(minOf(255, windowMax * 255 / 32768))
                        inWindow = 0
                        windowMax = 0
                    }

                    if (ratio == 1.0) {
                        writer.write16(l)
                        writer.write16(r)
                    } else {
                        if (inIndex == 0L) {
                            prevL = l
                            prevR = r
                        }
                        var outT = outCount * ratio
                        while (outT <= inIndex) {
                            val frac = outT - (inIndex - 1)
                            val ol = Math.round(prevL + (l - prevL) * frac).toInt().coerceIn(-32768, 32767)
                            val orr = Math.round(prevR + (r - prevR) * frac).toInt().coerceIn(-32768, 32767)
                            writer.write16(ol)
                            writer.write16(orr)
                            outCount++
                            outT = outCount * ratio
                        }
                        prevL = l
                        prevR = r
                        inIndex++
                    }
                    i++
                }
                frames += n
            })
            if (!ok || rate <= 0) return null
            writer.flush()
        } catch (e: Exception) {
            return null
        } finally {
            try {
                stream.close()
            } catch (e: Exception) {
                // ignorieren
            }
        }
        if (inWindow > 0) peaks.write(minOf(255, windowMax * 255 / 32768))
        val duration = frames.toDouble() / rate
        return if (duration > 0.0) Analysis(duration, peaks.toByteArray()) else null
    }

    // ---------- Dekodieren ----------

    /** Nimmt Blöcke von Stereo-Frames (links, rechts abwechselnd) entgegen. Ohne Boxing, daher schnell. */
    private fun interface BlockSink {
        fun onBlock(stereo: ShortArray, frames: Int)
    }

    private fun decodeFrames(file: File, onRate: (Int) -> Unit, sink: BlockSink): Boolean =
        if (isWav(file)) decodeWav(file, onRate, sink) else decodeWithCodec(file, onRate, sink)

    private fun isWav(file: File): Boolean = try {
        file.inputStream().use { s ->
            val h = ByteArray(12)
            s.read(h) == 12 && h.decodeToString(0, 4) == "RIFF" && h.decodeToString(8, 12) == "WAVE"
        }
    } catch (e: Exception) {
        false
    }

    private fun decodeWav(file: File, onRate: (Int) -> Unit, sink: BlockSink): Boolean =
        try {
            RandomAccessFile(file, "r").use { raf -> decodeWavFrom(raf, onRate, sink) }
        } catch (e: Exception) {
            false
        }

    private fun decodeWavFrom(raf: RandomAccessFile, onRate: (Int) -> Unit, sink: BlockSink): Boolean {
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
        val framesPerBlock = 16384
        val buf = ByteArray(frameBytes * framesPerBlock)
        val out = ShortArray(framesPerBlock * 2)
        var remaining = dataLen / frameBytes * frameBytes
        while (remaining > 0) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            raf.readFully(buf, 0, want)
            val bb = ByteBuffer.wrap(buf, 0, want).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            var o = 0
            while (i < want) {
                val l = readSample(bb, i, bytes, format == 3)
                val r = if (channels >= 2) readSample(bb, i + bytes, bytes, format == 3) else l
                out[o++] = l.toShort()
                out[o++] = r.toShort()
                i += frameBytes
            }
            sink.onBlock(out, o / 2)
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

    private fun decodeWithCodec(file: File, onRate: (Int) -> Unit, sink: BlockSink): Boolean {
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
            var raw = ShortArray(65536)
            var stereo = ShortArray(65536)
            while (!outputDone) {
                // Alle freien Eingabepuffer sofort füllen (ohne zu warten)
                while (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val inBuf = decoder.getInputBuffer(inIndex)
                    val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                // Alle fertigen Ausgabepuffer abholen; nur beim ersten kurz warten
                var wait = 5_000L
                while (true) {
                    val outIndex = decoder.dequeueOutputBuffer(info, wait)
                    wait = 0L
                    if (outIndex >= 0) {
                        val outBuf = decoder.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val count = shorts.remaining()
                            if (raw.size < count) raw = ShortArray(count)
                            shorts.get(raw, 0, count)
                            val frames = count / channels
                            if (channels == 2) {
                                sink.onBlock(raw, frames)
                            } else {
                                if (stereo.size < frames * 2) stereo = ShortArray(frames * 2)
                                for (f in 0 until frames) {
                                    val l = raw[f * channels]
                                    stereo[f * 2] = l
                                    stereo[f * 2 + 1] = if (channels >= 2) raw[f * channels + 1] else l
                                }
                                sink.onBlock(stereo, frames)
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            break
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = decoder.outputFormat
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        onRate(newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    }
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
