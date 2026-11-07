package com.example.audiomix.data

import android.content.Context
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthetische Klänge für Effekt und Synth. */
object DrumSound {

    /** Ein tiefer "Bumm". */
    fun hit(rate: Int): ShortArray {
        val length = (0.28 * rate).toInt()
        val out = ShortArray(length)
        var phase = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            val freq = 50.0 + 130.0 * exp(-t * 28.0)
            phase += 2.0 * PI * freq / rate
            val envelope = exp(-t * 14.0)
            val click = if (i < 200) (Random.nextDouble() * 2.0 - 1.0) * 0.3 * (1.0 - i / 200.0) else 0.0
            val v = (sin(phase) * envelope + click) * 0.9
            out[i] = (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Ein kurzes, helles Zischen (Hi-Hat). */
    fun hihat(rate: Int): ShortArray {
        val length = (0.09 * rate).toInt()
        val out = ShortArray(length)
        var previous = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            val noise = Random.nextDouble() * 2.0 - 1.0
            val high = noise - previous
            previous = noise
            val v = high * exp(-t * 55.0) * 0.4
            out[i] = (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Ein weicher Synth-Ton. */
    fun synth(rate: Int, freq: Double): ShortArray {
        val length = (0.5 * rate).toInt()
        val out = ShortArray(length)
        var phase = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            phase += 2.0 * PI * freq / rate
            val attack = if (t < 0.006) t / 0.006 else 1.0
            val envelope = attack * exp(-t * 6.5)
            val wave = sin(phase) + 0.35 * sin(2.0 * phase) + 0.15 * sin(3.0 * phase)
            val v = wave * envelope * 0.35
            out[i] = (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

/** Ein Equalizer-Band (Biquad-Filter) mit eigenem Zustand je Kanal. */
private class BandFilter(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float
) {
    private val x1 = FloatArray(2)
    private val x2 = FloatArray(2)
    private val y1 = FloatArray(2)
    private val y2 = FloatArray(2)

    fun process(ch: Int, x: Float): Float {
        val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
        x2[ch] = x1[ch]
        x1[ch] = x
        y2[ch] = y1[ch]
        y1[ch] = y
        return y
    }
}

/** Band 0 = Treble (Höhen-Anhebung), 1 = Mid (Glocke), 2 = Bass (Tiefen-Anhebung). */
private fun makeBand(band: Int, gainDb: Int, rate: Int): BandFilter? {
    if (gainDb == 0) return null
    val a = Math.pow(10.0, gainDb / 40.0)
    val f0 = when (band) {
        0 -> 5000.0
        1 -> 1000.0
        else -> 150.0
    }
    val w0 = 2.0 * Math.PI * f0 / rate
    val cosw = Math.cos(w0)
    val sinw = Math.sin(w0)
    val b0: Double
    val b1: Double
    val b2: Double
    val a0: Double
    val a1: Double
    val a2: Double
    when (band) {
        0 -> {
            val sq = 2.0 * Math.sqrt(a) * (sinw / 2.0 * Math.sqrt(2.0))
            b0 = a * ((a + 1) + (a - 1) * cosw + sq)
            b1 = -2.0 * a * ((a - 1) + (a + 1) * cosw)
            b2 = a * ((a + 1) + (a - 1) * cosw - sq)
            a0 = (a + 1) - (a - 1) * cosw + sq
            a1 = 2.0 * ((a - 1) - (a + 1) * cosw)
            a2 = (a + 1) - (a - 1) * cosw - sq
        }
        1 -> {
            val alpha = sinw / (2.0 * 0.7)
            b0 = 1.0 + alpha * a
            b1 = -2.0 * cosw
            b2 = 1.0 - alpha * a
            a0 = 1.0 + alpha / a
            a1 = -2.0 * cosw
            a2 = 1.0 - alpha / a
        }
        else -> {
            val sq = 2.0 * Math.sqrt(a) * (sinw / 2.0 * Math.sqrt(2.0))
            b0 = a * ((a + 1) - (a - 1) * cosw + sq)
            b1 = 2.0 * a * ((a - 1) - (a + 1) * cosw)
            b2 = a * ((a + 1) - (a - 1) * cosw - sq)
            a0 = (a + 1) + (a - 1) * cosw + sq
            a1 = -2.0 * ((a - 1) + (a + 1) * cosw)
            a2 = (a + 1) + (a - 1) * cosw - sq
        }
    }
    return BandFilter(
        (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
        (a1 / a0).toFloat(), (a2 / a0).toFloat()
    )
}

/** Rechnet alle Zeilen eines Projekts zu einem Stereo-Signal (44,1 kHz, 16 Bit) zusammen. */
class MixRenderer(
    context: Context,
    private val projectId: String,
    tracks: List<Track>,
    private val bpm: Int
) {
    companion object {
        const val RATE = 44100
        const val TAIL_FRAMES = 13230
    }

    private class Lane(
        val track: Track,
        val clips: List<Clip>,
        val eq: Array<BandFilter?>?,
        val start: Double,
        val end: Double
    )

    private val appContext = context.applicationContext
    private val pcmFiles = ConcurrentHashMap<String, RandomAccessFile>()

    /** Nur hörbare Zeilen und Blöcke (stumme fehlen, auch im Export), Wiederholungen aufgelöst. */
    private val lanes: List<Lane> = tracks
        .filter { !it.muted && it.volume > 0 }
        .map { t ->
            val eq = if (t.hasEq) Array(3) { b -> makeBand(b, t.eqGain[b], 44100) } else null
            Lane(
                t,
                t.clips.filter { !it.muted }.flatMap { it.tiles() },
                eq,
                t.extentStart,
                t.extentEnd
            )
        }

    val framesPerBar: Double = 240.0 / bpm * RATE

    val totalFrames: Long =
        ((tracks.flatMap { it.clips }.maxOfOrNull { it.endBar } ?: 0.0) * framesPerBar).toLong() +
            TAIL_FRAMES

    private fun fileKey(name: String, speed: Double): String = "$name|${StretchFiles.key(speed)}"

    fun prepare(): Boolean {
        for (lane in lanes) {
            if (lane.track.type != TrackType.AUDIO) continue
            for (clip in lane.clips) {
                val name = clip.sourceFile ?: continue
                val speed = clip.speed(lane.track.tempo(bpm))
                val key = fileKey(name, speed)
                if (pcmFiles.containsKey(key)) continue
                val pcm = StretchFiles.ensure(
                    appContext, projectId, name, speed, { }, { false }
                ) ?: return false
                pcmFiles[key] = RandomAccessFile(pcm, "r")
            }
        }
        return true
    }

    fun render(startFrame: Long, count: Int, out: ShortArray) {
        val acc = IntArray(count * 2)
        val endFrame = startFrame + count
        for (lane in lanes) {
            val target = if (lane.eq != null) IntArray(count * 2) else acc
            val gain = lane.track.volume
            for (clip in lane.clips) {
                when (lane.track.type) {
                    TrackType.AUDIO -> mixAudio(lane.track, clip, gain, startFrame, endFrame, target)
                    TrackType.EFFECT, TrackType.SYNTH ->
                        mixPattern(clip, Sounds.set(lane.track.type), gain, startFrame, endFrame, target)
                }
            }
            if (lane.eq != null) applyEq(lane, startFrame, count, target, acc)
        }
        for (i in 0 until count * 2) {
            out[i] = acc[i].coerceIn(-32768, 32767).toShort()
        }
    }

    fun close() {
        for (file in pcmFiles.values) {
            try {
                file.close()
            } catch (e: Exception) {
                // ignorieren
            }
        }
        pcmFiles.clear()
    }

    /** Faktor (0 bis 1) für das Ein- und Ausblenden eines Blocks an dieser Stelle. */
    private fun fade(clip: Clip, frame: Long): Float {
        if (clip.fadeIn <= 0.0 && clip.fadeOut <= 0.0) return 1f
        val bar = frame / framesPerBar
        var f = 1.0
        if (clip.fadeIn > 0.0) f = minOf(f, (bar - clip.startBar) / clip.fadeIn)
        if (clip.fadeOut > 0.0) f = minOf(f, (clip.startBar + clip.lengthBar - bar) / clip.fadeOut)
        return f.coerceIn(0.0, 1.0).toFloat()
    }

    /** Wie stark ein Equalizer-Band an dieser Stelle wirkt (Verlauf). */
    private fun bandWeight(lane: Lane, band: Int, bar: Double): Float {
        val fi = lane.track.eqFadeIn[band]
        val fo = lane.track.eqFadeOut[band]
        if (fi <= 0.0 && fo <= 0.0) return 1f
        var w = 1.0
        if (fi > 0.0) w = minOf(w, (bar - lane.start) / fi)
        if (fo > 0.0) w = minOf(w, (lane.end - bar) / fo)
        return w.coerceIn(0.0, 1.0).toFloat()
    }

    private fun stage(f: BandFilter?, ch: Int, x: Float, w: Float): Float {
        if (f == null) return x
        val y = f.process(ch, x)
        return x + w * (y - x)
    }

    private fun applyEq(lane: Lane, startFrame: Long, count: Int, src: IntArray, dst: IntArray) {
        val eq = lane.eq ?: return
        for (fr in 0 until count) {
            val bar = (startFrame + fr) / framesPerBar
            val w0 = bandWeight(lane, 0, bar)
            val w1 = bandWeight(lane, 1, bar)
            val w2 = bandWeight(lane, 2, bar)
            for (ch in 0..1) {
                var x = src[fr * 2 + ch].toFloat()
                x = stage(eq[0], ch, x, w0)
                x = stage(eq[1], ch, x, w1)
                x = stage(eq[2], ch, x, w2)
                dst[fr * 2 + ch] += x.toInt()
            }
        }
    }

    private fun mixAudio(
        track: Track,
        clip: Clip,
        gain: Int,
        startFrame: Long,
        endFrame: Long,
        acc: IntArray
    ) {
        val name = clip.sourceFile ?: return
        val file = pcmFiles[fileKey(name, clip.speed(track.tempo(bpm)))] ?: return
        val clipStart = (clip.startBar * framesPerBar).toLong()
        val clipEnd = ((clip.startBar + clip.lengthBar) * framesPerBar).toLong()
        val from = maxOf(startFrame, clipStart)
        val to = minOf(endFrame, clipEnd)
        if (from >= to) return

        val n = (to - from).toInt()
        val sourceFrame = (clip.offsetBar * framesPerBar).toLong() + (from - clipStart)
        val bytes = ByteArray(n * 4)
        var read = 0
        try {
            file.seek(sourceFrame * 4L)
            while (read < bytes.size) {
                val r = file.read(bytes, read, bytes.size - read)
                if (r < 0) break
                read += r
            }
        } catch (e: Exception) {
            return
        }
        val frames = read / 4
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val base = (from - startFrame).toInt() * 2
        val g = gain / 100f
        for (fr in 0 until frames) {
            val f = g * fade(clip, from + fr)
            val i = fr * 2
            acc[base + i] += (samples.get(i) * f).toInt()
            acc[base + i + 1] += (samples.get(i + 1) * f).toInt()
        }
    }

    /** Spielt die gesetzten Felder der Musikrolle ab. */
    private fun mixPattern(
        clip: Clip,
        sounds: Array<ShortArray>,
        gain: Int,
        startFrame: Long,
        endFrame: Long,
        acc: IntArray
    ) {
        val cellBars = 1.0 / (16.0 * clip.stretch)
        val blockEnd = clip.startBar + clip.lengthBar
        val g = gain / 100f
        for (code in clip.pattern) {
            val lane = code / CELL_BASE
            val cell = code % CELL_BASE
            if (lane >= sounds.size) continue
            val posBar = clip.startBar + cell * cellBars
            if (posBar >= blockEnd - 1e-9) continue
            val sound = sounds[lane]
            val hitStart = (posBar * framesPerBar).toLong()
            val from = maxOf(startFrame, hitStart)
            val to = minOf(endFrame, hitStart + sound.size)
            if (from >= to) continue
            val base = (from - startFrame).toInt() * 2
            val sourceStart = (from - hitStart).toInt()
            val n = (to - from).toInt()
            for (i in 0 until n) {
                val v = (sound[sourceStart + i] * (g * fade(clip, from + i))).toInt()
                acc[base + i * 2] += v
                acc[base + i * 2 + 1] += v
            }
        }
    }
}
