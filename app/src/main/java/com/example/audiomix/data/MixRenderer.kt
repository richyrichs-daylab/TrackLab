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

/** Eine Echo-Leitung (ein Kanal) mit Rückkopplung. */
private class DelayChannel(size: Int) {
    private val buf = FloatArray(size)
    private var pos = 0

    /** Gibt das Echo zurück, das [d] Samples zurückliegt, und schreibt [x] plus Rückkopplung hinein. */
    fun step(x: Float, d: Float, fb: Float): Float {
        var rp = pos - d
        while (rp < 0f) rp += buf.size
        var i0 = rp.toInt()
        if (i0 >= buf.size) i0 = 0
        val frac = rp - i0
        val i1 = if (i0 + 1 >= buf.size) 0 else i0 + 1
        val y = buf[i0] * (1f - frac) + buf[i1] * frac
        buf[pos] = x + y * fb
        pos++
        if (pos >= buf.size) pos = 0
        return y
    }
}

private class DelayFx(rate: Int) {
    val left = DelayChannel(rate + 8)
    val right = DelayChannel(rate + 8)
    val maxDelay: Float = rate.toFloat()
}

/** Ein einfacher Hall (acht Kammfilter und vier Allpässe je Seite). Der Raum bestimmt Größe, Dauer und Dunkelheit. */
private class ReverbFx(preset: Int) {
    /** Größe, Rückkopplung (Dauer), Dämpfung (Dunkelheit). */
    private val params: FloatArray = when (preset) {
        0 -> floatArrayOf(0.55f, 0.80f, 0.15f)   // small plate
        1 -> floatArrayOf(0.85f, 0.84f, 0.20f)   // medium plate
        2 -> floatArrayOf(1.25f, 0.88f, 0.25f)   // big plate
        3 -> floatArrayOf(2.20f, 0.91f, 0.55f)   // cave
        4 -> floatArrayOf(1.50f, 0.83f, 0.30f)   // hallway
        else -> floatArrayOf(0.40f, 0.70f, 0.50f) // behind you (kleiner Raum)
    }
    private val scale = params[0]
    private val feedback = params[1]
    private val damp = params[2]

    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTunings = intArrayOf(556, 441, 341, 225)

    private val combL = Array(8) { FloatArray(maxOf(8, (combTunings[it] * scale).toInt())) }
    private val combR = Array(8) { FloatArray(maxOf(8, ((combTunings[it] + 23) * scale).toInt())) }
    private val combPosL = IntArray(8)
    private val combPosR = IntArray(8)
    private val combStoreL = FloatArray(8)
    private val combStoreR = FloatArray(8)

    private val apL = Array(4) { FloatArray(allpassTunings[it]) }
    private val apR = Array(4) { FloatArray(allpassTunings[it] + 23) }
    private val apPosL = IntArray(4)
    private val apPosR = IntArray(4)

    var outL = 0f
        private set
    var outR = 0f
        private set

    private fun comb(buf: FloatArray, pos: IntArray, store: FloatArray, i: Int, input: Float): Float {
        val o = buf[pos[i]]
        store[i] = o * (1f - damp) + store[i] * damp
        buf[pos[i]] = input + store[i] * feedback
        pos[i]++
        if (pos[i] >= buf.size) pos[i] = 0
        return o
    }

    private fun allpass(buf: FloatArray, pos: IntArray, i: Int, input: Float): Float {
        val b = buf[pos[i]]
        val out = -input + b
        buf[pos[i]] = input + b * 0.5f
        pos[i]++
        if (pos[i] >= buf.size) pos[i] = 0
        return out
    }

    /** Verarbeitet einen Eingangswert; das Ergebnis steht in [outL] und [outR] (nur der Hallanteil). */
    fun process(x: Float) {
        val input = x * 0.015f
        var sumL = 0f
        var sumR = 0f
        for (c in 0 until 8) {
            sumL += comb(combL[c], combPosL, combStoreL, c, input)
            sumR += comb(combR[c], combPosR, combStoreR, c, input)
        }
        for (a in 0 until 4) {
            sumL = allpass(apL[a], apPosL, a, sumL)
            sumR = allpass(apR[a], apPosR, a, sumR)
        }
        outL = sumL
        outR = sumR
    }
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
        val delay: DelayFx?,
        val reverb: ReverbFx?,
        val start: Double,
        val end: Double
    ) {
        val hasFx: Boolean get() = eq != null || delay != null || reverb != null
    }

    private val appContext = context.applicationContext
    private val pcmFiles = ConcurrentHashMap<String, RandomAccessFile>()

    /** Nur hörbare Zeilen und Blöcke (stumme fehlen, auch im Export), Wiederholungen aufgelöst. */
    private val lanes: List<Lane> = tracks
        .filter { !it.muted && it.volume > 0 }
        .map { t ->
            val eqOn = t.hasEq && "pitch" !in t.fxOff
            val delayOn = "delay" !in t.fxOff && t.delay[2] > 0.0
            val reverbOn = "reverb" !in t.fxOff && t.reverbLevel > 0.0
            Lane(
                t,
                t.clips.filter { !it.muted }.flatMap { it.tiles() },
                if (eqOn) Array(3) { b -> makeBand(b, t.eqGain[b], 44100) } else null,
                if (delayOn) DelayFx(RATE) else null,
                if (reverbOn) ReverbFx(t.reverbPreset) else null,
                t.extentStart,
                t.extentEnd
            )
        }

    val framesPerBar: Double = 240.0 / bpm * RATE

    /** Echo und Nachhall klingen nach dem letzten Block weiter. */
    private val fxTailFrames: Long = lanes.maxOfOrNull { lane ->
        var sec = 0.0
        if (lane.reverb != null) sec = 4.0
        if (lane.delay != null) {
            val d = lane.track.delay
            sec = maxOf(sec, minOf(8.0, (0.05 + 0.95 * d[1]) * (1.0 + 5.0 * d[0])))
        }
        (sec * RATE).toLong()
    } ?: 0L

    val totalFrames: Long =
        ((tracks.flatMap { it.clips }.maxOfOrNull { it.endBar } ?: 0.0) * framesPerBar).toLong() +
            TAIL_FRAMES + fxTailFrames

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
            val target = if (lane.hasFx) IntArray(count * 2) else acc
            val gain = lane.track.volume
            for (clip in lane.clips) {
                when (lane.track.type) {
                    TrackType.AUDIO -> mixAudio(lane.track, clip, gain, startFrame, endFrame, target)
                    TrackType.EFFECT, TrackType.SYNTH ->
                        mixPattern(clip, Sounds.set(lane.track.type), gain, startFrame, endFrame, target)
                }
            }
            if (lane.hasFx) applyFx(lane, startFrame, count, target, acc)
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

    /** Wie stark ein Effekt-Wert an dieser Stelle der Zeile wirkt (Verlauf: ein- und ausblenden). */
    private fun fadeWeight(lane: Lane, fi: Double, fo: Double, bar: Double): Float {
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

    /** Wendet nacheinander Tonhöhen, Delay und Reverb der Zeile an und addiert das Ergebnis zu [dst]. */
    private fun applyFx(lane: Lane, startFrame: Long, count: Int, src: IntArray, dst: IntArray) {
        val tr = lane.track
        val eq = lane.eq
        val delay = lane.delay
        val reverb = lane.reverb
        for (fr in 0 until count) {
            val bar = (startFrame + fr) / framesPerBar
            var l = src[fr * 2].toFloat()
            var r = src[fr * 2 + 1].toFloat()

            if (eq != null) {
                val w0 = fadeWeight(lane, tr.eqFadeIn[0], tr.eqFadeOut[0], bar)
                val w1 = fadeWeight(lane, tr.eqFadeIn[1], tr.eqFadeOut[1], bar)
                val w2 = fadeWeight(lane, tr.eqFadeIn[2], tr.eqFadeOut[2], bar)
                l = stage(eq[2], 0, stage(eq[1], 0, stage(eq[0], 0, l, w0), w1), w2)
                r = stage(eq[2], 1, stage(eq[1], 1, stage(eq[0], 1, r, w0), w1), w2)
            }

            if (delay != null) {
                val wDecay = fadeWeight(lane, tr.delayFadeIn[0], tr.delayFadeOut[0], bar)
                val wTime = fadeWeight(lane, tr.delayFadeIn[1], tr.delayFadeOut[1], bar)
                val wMix = fadeWeight(lane, tr.delayFadeIn[2], tr.delayFadeOut[2], bar)
                val feedback = (tr.delay[0] * 0.9 * wDecay).toFloat()
                val timeMs = 50.0 + tr.delay[1] * 950.0 * wTime
                val d = (timeMs * RATE / 1000.0).toFloat().coerceIn(1f, delay.maxDelay)
                val wet = (tr.delay[2] * wMix).toFloat()
                val yl = delay.left.step(l, d, feedback)
                val yr = delay.right.step(r, d, feedback)
                l += yl * wet
                r += yr * wet
            }

            if (reverb != null) {
                val level = (tr.reverbLevel * fadeWeight(lane, tr.reverbFadeIn, tr.reverbFadeOut, bar)).toFloat()
                reverb.process((l + r) * 0.5f)
                l += reverb.outL * level * 3f
                r += reverb.outR * level * 3f
            }

            dst[fr * 2] += l.toInt()
            dst[fr * 2 + 1] += r.toInt()
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
