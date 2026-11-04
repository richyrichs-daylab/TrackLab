package com.example.audiomix.data

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

private class PcmSink(private val stream: OutputStream) {
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

/**
 * Tempoänderung ohne Tonhöhenänderung (WSOLA-Verfahren).
 * speed > 1 = schneller (kürzer), speed < 1 = langsamer (länger).
 */
object TimeStretch {
    private const val N = 2048      // Fensterlänge in Frames
    private const val HS = 1024     // Schrittweite beim Ausgeben
    private const val TOL = 512     // Suchbereich für die beste Stelle
    private const val DEC = 8       // Verkleinerung für die schnelle Suche

    fun stretch(
        input: File,
        output: File,
        speed: Double,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        val inFrames = (input.length() / 4L).toInt()
        if (inFrames < N * 2) {
            input.copyTo(output, overwrite = true)
            return true
        }
        var ok = false
        var fos: FileOutputStream? = null
        try {
            RandomAccessFile(input, "r").use { raf ->
                val data = raf.channel
                    .map(FileChannel.MapMode.READ_ONLY, 0L, inFrames * 4L)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                val stream = FileOutputStream(output)
                fos = stream
                val sink = PcmSink(stream)
                ok = process(data, inFrames, speed, sink, onProgress, isCancelled)
                if (ok) sink.flush()
            }
        } catch (e: Exception) {
            ok = false
        } finally {
            try {
                fos?.close()
            } catch (e: Exception) {
                // ignorieren
            }
        }
        return ok
    }

    private fun clamp(v: Float): Int = v.roundToInt().coerceIn(-32768, 32767)

    private fun process(
        data: ShortBuffer,
        inFrames: Int,
        speed: Double,
        sink: PcmSink,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        // Verkleinerte Mono-Version zum schnellen Suchen
        val dn = inFrames / DEC
        val dm = FloatArray(dn)
        for (i in 0 until dn) {
            var s = 0
            val b = i * DEC * 2
            for (k in 0 until DEC * 2) s += data.get(b + k).toInt()
            dm[i] = s.toFloat() / (DEC * 2)
        }

        val win = FloatArray(N) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / N)).toFloat() }
        val accL = FloatArray(N)
        val accR = FloatArray(N)
        val maxStart = inFrames - N
        val ha = HS * speed
        val len = N / DEC

        fun addSegment(start: Int, first: Boolean) {
            for (i in 0 until N) {
                val w = if (first && i < HS) 1f else win[i]
                accL[i] += data.get((start + i) * 2) * w
                accR[i] += data.get((start + i) * 2 + 1) * w
            }
        }

        fun emit() {
            for (i in 0 until HS) {
                sink.write16(clamp(accL[i]))
                sink.write16(clamp(accR[i]))
            }
            System.arraycopy(accL, HS, accL, 0, N - HS)
            System.arraycopy(accR, HS, accR, 0, N - HS)
            Arrays.fill(accL, N - HS, N, 0f)
            Arrays.fill(accR, N - HS, N, 0f)
        }

        addSegment(0, true)
        emit()
        var lastChosen = 0
        var k = 1
        while (true) {
            if (isCancelled()) return false
            val nominalL = (k * ha).toLong()
            if (nominalL > maxStart) break
            val nominal = nominalL.toInt()

            var best = nominal
            val target = lastChosen + HS
            if (target <= maxStart) {
                val lo = maxOf(0, nominal - TOL)
                val hi = minOf(maxStart, nominal + TOL)
                val tdm = target / DEC
                var bestScore = -Float.MAX_VALUE
                var c = lo / DEC
                val cEnd = hi / DEC
                while (c <= cEnd) {
                    var score = 0f
                    for (i in 0 until len) score += dm[c + i] * dm[tdm + i]
                    if (score > bestScore) {
                        bestScore = score
                        best = c * DEC
                    }
                    c++
                }
            }

            addSegment(best, false)
            emit()
            lastChosen = best
            k++
            if (k % 16 == 0) {
                onProgress((nominalL.toFloat() / maxStart).coerceIn(0f, 1f))
            }
        }

        // Rest ausgeben
        for (i in 0 until HS) {
            sink.write16(clamp(accL[i]))
            sink.write16(clamp(accR[i]))
        }
        onProgress(1f)
        return true
    }
}
