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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    /** Fortschritt der laufenden Vorbereitung in Prozent (0 bis 99), -1 = nichts läuft. */
    val progress = java.util.concurrent.atomic.AtomicInteger(-1)

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
        progress.set(0)
        val analysis = try {
            analyzeAndConvert(source, tmp)
        } finally {
            progress.set(-1)
        }
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

    // ---------- MP3: parallel dekodieren ----------

    private class Mp3Hdr {
        var len = 0
        var rate = 0
        var channels = 0
        var spf = 0
        var protection = false
        var mpeg1 = false
    }

    private class Mp3Index(
        val rate: Int,
        val channels: Int,
        val spf: Int,
        val offsets: IntArray,
        val sizes: IntArray,
        val count: Int
    )

    private val MP3_BR1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MP3_BR2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val MP3_SR = intArrayOf(44100, 48000, 32000)

    private fun mp3Header(m: ByteBuffer, p: Int, limit: Int, h: Mp3Hdr): Boolean {
        if (p < 0 || p + 4 > limit) return false
        val b0 = m.get(p).toInt() and 0xFF
        val b1 = m.get(p + 1).toInt() and 0xFF
        val b2 = m.get(p + 2).toInt() and 0xFF
        val b3 = m.get(p + 3).toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return false
        val ver = (b1 shr 3) and 3
        val layer = (b1 shr 1) and 3
        if (ver == 1 || layer != 1) return false
        val brIdx = (b2 shr 4) and 15
        val srIdx = (b2 shr 2) and 3
        if (brIdx == 0 || brIdx == 15 || srIdx == 3) return false
        val pad = (b2 shr 1) and 1
        val mpeg1 = ver == 3
        val br = (if (mpeg1) MP3_BR1[brIdx] else MP3_BR2[brIdx]) * 1000
        val sr = MP3_SR[srIdx] / (if (ver == 3) 1 else if (ver == 2) 2 else 4)
        h.mpeg1 = mpeg1
        h.rate = sr
        h.spf = if (mpeg1) 1152 else 576
        h.len = (if (mpeg1) 144 else 72) * br / sr + pad
        h.channels = if (((b3 shr 6) and 3) == 3) 1 else 2
        h.protection = (b1 and 1) == 0
        return h.len > 4
    }

    private fun tagAt(m: ByteBuffer, p: Int, limit: Int): Boolean {
        if (p + 3 > limit) return false
        val a = m.get(p).toInt()
        val b = m.get(p + 1).toInt()
        val c = m.get(p + 2).toInt()
        return (a == 'T'.code && b == 'A'.code && c == 'G'.code) ||
            (a == 'I'.code && b == 'D'.code && c == '3'.code) ||
            (a == 'A'.code && b == 'P'.code && c == 'E'.code)
    }

    private fun isInfoFrame(m: ByteBuffer, p: Int, h: Mp3Hdr, limit: Int): Boolean {
        val side = if (h.mpeg1) (if (h.channels == 1) 17 else 32) else (if (h.channels == 1) 9 else 17)
        val t = p + 4 + side + (if (h.protection) 2 else 0)
        if (t + 4 > limit) return false
        fun at(q: Int, w: String): Boolean {
            for (i in w.indices) if (m.get(q + i).toInt() != w[i].code) return false
            return true
        }
        return at(t, "Xing") || at(t, "Info") || (p + 36 + 4 <= limit && at(p + 36, "VBRI"))
    }

    /** Geht die MP3-Datei Bild für Bild durch. Null, wenn sie nicht eindeutig lesbar ist. */
    private fun indexMp3(m: ByteBuffer, length: Int): Mp3Index? {
        var pos = 0
        if (length > 10 && m.get(0).toInt() == 'I'.code && m.get(1).toInt() == 'D'.code && m.get(2).toInt() == '3'.code) {
            val sz = ((m.get(6).toInt() and 0x7F) shl 21) or ((m.get(7).toInt() and 0x7F) shl 14) or
                ((m.get(8).toInt() and 0x7F) shl 7) or (m.get(9).toInt() and 0x7F)
            val footer = if ((m.get(5).toInt() and 0x10) != 0) 10 else 0
            pos = 10 + sz + footer
        }
        val h = Mp3Hdr()
        val h2 = Mp3Hdr()
        val cap = length / 24 + 16
        val offs = IntArray(cap)
        val sizes = IntArray(cap)
        var n = 0
        var rate = 0
        var channels = 0
        var spf = 0
        var first = true
        var skipped = 0
        while (pos + 4 <= length) {
            if (mp3Header(m, pos, length, h) && pos + h.len <= length) {
                val next = pos + h.len
                val okNext = next + 4 > length || mp3Header(m, next, length, h2) || tagAt(m, next, length)
                val sameFormat = rate == 0 || (h.rate == rate && h.spf == spf && h.channels == channels)
                if (okNext && sameFormat) {
                    if (rate == 0) {
                        rate = h.rate
                        channels = h.channels
                        spf = h.spf
                    }
                    if (first) {
                        first = false
                        if (isInfoFrame(m, pos, h, length)) {
                            pos = next
                            continue
                        }
                    }
                    if (n >= cap) return null
                    offs[n] = pos
                    sizes[n] = h.len
                    n++
                    pos = next
                    continue
                }
            }
            pos++
            skipped++
            if (skipped > 4_000_000) return null
        }
        if (n < 400 || rate == 0) return null
        return Mp3Index(rate, channels, spf, offs, sizes, n)
    }

    /** Verarbeitet einen Abschnitt: Wellenform und Umrechnung auf 44,1 kHz, schreibt direkt an die richtige Stelle. */
    private class Mp3Chunk(
        val out: java.nio.channels.FileChannel,
        val ratio: Double,
        val window: Int,
        val gStart: Long,
        val gEnd: Long,
        val fromFrame: Boolean
    ) {
        private val buf = ByteArray(1 shl 18)
        private var n = 0
        var gCur: Long = if (fromFrame) gStart - 1 else gStart
        private var prevL = 0
        private var prevR = 0

        var j0: Long
        private var j: Long

        val firstWin: Long = gStart / window
        val peaksLocal = IntArray(((gEnd - 1) / window - gStart / window).toInt() + 3)
        private var curWin: Long = gStart / window
        private var inWin: Int = (gStart % window).toInt()
        private var curMax = 0
        private var outPos: Long

        init {
            var jj: Long
            if (gStart == 0L) {
                jj = 0L
            } else {
                jj = Math.floor((gStart - 1) / ratio).toLong()
                while (jj * ratio <= (gStart - 1).toDouble()) jj++
                while (jj > 0 && (jj - 1) * ratio > (gStart - 1).toDouble()) jj--
            }
            j0 = jj
            j = jj
            outPos = jj * 4L
        }

        val outCount: Long get() = j - j0

        private fun put(l: Int, r: Int) {
            if (n + 4 > buf.size) flush()
            buf[n++] = (l and 0xFF).toByte()
            buf[n++] = ((l shr 8) and 0xFF).toByte()
            buf[n++] = (r and 0xFF).toByte()
            buf[n++] = ((r shr 8) and 0xFF).toByte()
        }

        fun flush() {
            if (n > 0) {
                val bb = ByteBuffer.wrap(buf, 0, n)
                var pos = outPos
                while (bb.hasRemaining()) pos += out.write(bb, pos)
                outPos = pos
                n = 0
            }
        }

        fun finish() {
            flush()
            if (inWin > 0 || curMax > 0) {
                val idx = (curWin - firstWin).toInt()
                if (idx in peaksLocal.indices && curMax > peaksLocal[idx]) peaksLocal[idx] = curMax
            }
        }

        fun consume(a: ShortArray, frames: Int) {
            var i = 0
            val unity = ratio == 1.0
            while (i < frames) {
                val l = a[i * 2].toInt()
                val r = a[i * 2 + 1].toInt()
                i++
                if (gCur < gStart) {
                    prevL = l
                    prevR = r
                    gCur++
                    continue
                }
                if (gCur >= gEnd) return
                if (gCur == 0L) {
                    prevL = l
                    prevR = r
                }

                val m = if (l < 0) (if (r < 0) maxOf(-l, -r) else maxOf(-l, r)) else (if (r < 0) maxOf(l, -r) else maxOf(l, r))
                if (m > curMax) curMax = m
                inWin++
                if (inWin >= window) {
                    val idx = (curWin - firstWin).toInt()
                    if (idx in peaksLocal.indices) peaksLocal[idx] = curMax
                    curWin++
                    inWin = 0
                    curMax = 0
                }

                if (unity) {
                    put(l, r)
                    j++
                } else {
                    var outT = j * ratio
                    while (outT <= gCur) {
                        val frac = outT - (gCur - 1)
                        val ol = Math.round(prevL + (l - prevL) * frac).toInt().coerceIn(-32768, 32767)
                        val orr = Math.round(prevR + (r - prevR) * frac).toInt().coerceIn(-32768, 32767)
                        put(ol, orr)
                        j++
                        outT = j * ratio
                    }
                }
                prevL = l
                prevR = r
                gCur++
            }
        }
    }

    /**
     * Dekodiert die Bilder [from, to) mit einem eigenen Decoder. Davor werden ein paar Bilder zum Einschwingen
     * mitdekodiert. Die Ergebnisse gehen an [chunk]. Gibt false zurück, wenn etwas nicht stimmt.
     */
    private fun decodeMp3Range(
        map: ByteBuffer,
        idx: Mp3Index,
        from: Int,
        to: Int,
        chunk: Mp3Chunk,
        done: AtomicLong,
        failed: AtomicBoolean
    ): Boolean {
        val spf = idx.spf
        val ch = idx.channels
        val warm = if (from > 0) minOf(4, from) else 0
        val startFeed = from - warm
        val wantFirstSample = if (from > 0) from.toLong() * spf - 1 else 0L
        var decoder: MediaCodec? = null
        try {
            val format = MediaFormat.createAudioFormat("audio/mpeg", idx.rate, ch)
            val dec = MediaCodec.createDecoderByType("audio/mpeg")
            decoder = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val src = map.duplicate()
            val info = MediaCodec.BufferInfo()
            var next = startFeed
            var inputDone = false
            var outputDone = false
            var raw = ShortArray(16384)
            var stereo = ShortArray(16384)
            var producedFrames = 0L          // dekodierte Frames (ab startFeed)
            val expected = (to - startFeed).toLong() * spf
            var lastProgress = System.nanoTime()

            while (!outputDone) {
                if (failed.get()) return false
                while (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    if (next >= to) {
                        dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val inBuf = dec.getInputBuffer(inIndex) ?: return false
                        inBuf.clear()
                        val len = idx.sizes[next]
                        if (inBuf.capacity() < len) return false
                        src.clear()
                        src.position(idx.offsets[next])
                        src.limit(idx.offsets[next] + len)
                        inBuf.put(src)
                        dec.queueInputBuffer(inIndex, 0, len, next.toLong() * spf * 1_000_000L / idx.rate, 0)
                        next++
                    }
                }

                var wait = 5_000L
                while (true) {
                    val outIndex = dec.dequeueOutputBuffer(info, wait)
                    wait = 0L
                    if (outIndex >= 0) {
                        val outBuf = dec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val count = shorts.remaining()
                            if (raw.size < count) raw = ShortArray(count)
                            shorts.get(raw, 0, count)
                            val frames = count / ch
                            val gStartOfBuf = startFeed.toLong() * spf + producedFrames
                            producedFrames += frames
                            // nur Frames ab wantFirstSample weitergeben
                            val skip = maxOf(0L, wantFirstSample - gStartOfBuf).toInt()
                            if (skip < frames) {
                                val take = frames - skip
                                if (stereo.size < take * 2) stereo = ShortArray(take * 2)
                                for (f in 0 until take) {
                                    val l = raw[(skip + f) * ch]
                                    stereo[f * 2] = l
                                    stereo[f * 2 + 1] = if (ch >= 2) raw[(skip + f) * ch + 1] else l
                                }
                                chunk.consume(stereo, take)
                                done.addAndGet(take.toLong())
                            }
                            lastProgress = System.nanoTime()
                        }
                        dec.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            break
                        }
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    }
                }
                if (System.nanoTime() - lastProgress > 15_000_000_000L) return false
            }
            chunk.finish()
            return producedFrames == expected && chunk.gCur == chunk.gEnd
        } catch (e: Throwable) {
            return false
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {
                // ignorieren
            }
        }
    }

    private fun parallelMp3(file: File, outFile: File): Analysis? {
        val length = file.length()
        if (length < 200_000L || length > Int.MAX_VALUE) return null
        return RandomAccessFile(file, "r").use { raf ->
            val map = raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0L, length)
            val idx = indexMp3(map, length.toInt()) ?: return null
            val spf = idx.spf
            val rate = idx.rate
            val total = idx.count
            val cores = Runtime.getRuntime().availableProcessors()
            val workers = minOf(maxOf(2, cores), 6).coerceAtMost(maxOf(1, total / 200))
            val bounds = IntArray(workers + 1) { (total.toLong() * it / workers).toInt() }
            val totalSamples = total.toLong() * spf
            val ratio = rate.toDouble() / TARGET_RATE
            val window = maxOf(1, rate / PEAKS_PER_SECOND)

            val outRaf = RandomAccessFile(outFile, "rw")
            outRaf.setLength(0)
            try {
                val channel = outRaf.channel
                val chunks = Array(workers) { k ->
                    Mp3Chunk(
                        channel, ratio, window,
                        bounds[k].toLong() * spf,
                        bounds[k + 1].toLong() * spf,
                        bounds[k] > 0
                    )
                }
                val done = AtomicLong(0)
                val failed = AtomicBoolean(false)
                val threads = Array(workers) { k ->
                    Thread {
                        try {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                        } catch (e: Exception) {
                            // ignorieren
                        }
                        val ok = decodeMp3Range(map, idx, bounds[k], bounds[k + 1], chunks[k], done, failed)
                        if (!ok) failed.set(true)
                    }.also { it.start() }
                }
                while (threads.any { it.isAlive }) {
                    progress.set((done.get() * 100.0 / totalSamples).toInt().coerceIn(0, 99))
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        // ignorieren
                    }
                }
                if (failed.get()) return null

                val last = chunks[workers - 1]
                channel.truncate((last.j0 + last.outCount) * 4L)

                val totalWindows = ((totalSamples + window - 1) / window).toInt()
                val global = IntArray(totalWindows + 4)
                for (c in chunks) {
                    for (w in c.peaksLocal.indices) {
                        val gi = (c.firstWin + w).toInt()
                        if (gi in global.indices && c.peaksLocal[w] > global[gi]) global[gi] = c.peaksLocal[w]
                    }
                }
                val peaks = ByteArray(totalWindows) { minOf(255, global[it] * 255 / 32768).toByte() }
                Analysis(totalSamples.toDouble() / rate, peaks)
            } finally {
                try {
                    outRaf.close()
                } catch (e: Exception) {
                    // ignorieren
                }
            }
        }
    }


    /** Dekodiert die Datei einmal und liefert Wellenform und Rohdatei (44,1 kHz, Stereo, 16 Bit) zugleich. */
    private fun analyzeAndConvert(file: File, outFile: File): Analysis? {
        // MP3: auf mehrere Prozessorkerne verteilen (deutlich schneller)
        if (!isWav(file)) {
            val fast = try {
                parallelMp3(file, outFile)
            } catch (e: Throwable) {
                null
            }
            if (fast != null) return fast
            try {
                outFile.delete()
            } catch (e: Exception) {
                // ignorieren
            }
        }
        val estFrames = (quickDuration(file) ?: 0.0)
        var lastReport = 0L
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
                if (estFrames > 0.0 && rate > 0 && frames - lastReport > 200_000L) {
                    lastReport = frames
                    progress.set((frames * 100.0 / (estFrames * rate)).toInt().coerceIn(0, 99))
                }
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
