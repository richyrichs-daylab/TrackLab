package com.example.audiomix.data

import android.content.Context
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

object DrumSound {
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

    private class Lane(val track: Track, val clips: List<Clip>)

    private val appContext = context.applicationContext
    private val drumHit = DrumSound.hit(RATE)
    private val hihatHit = DrumSound.hihat(RATE)
    private val pcmFiles = ConcurrentHashMap<String, RandomAccessFile>()

    /** Nur hörbare Zeilen und Blöcke, Wiederholungen schon aufgelöst. */
    private val lanes: List<Lane> = tracks
        .filter { !it.muted && it.volume > 0 }
        .map { t -> Lane(t, t.clips.filter { !it.muted }.flatMap { it.tiles() }) }

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
            val gain = lane.track.volume
            for (clip in lane.clips) {
                when (lane.track.type) {
                    TrackType.AUDIO -> mixAudio(lane.track, clip, gain, startFrame, endFrame, acc)
                    TrackType.DRUM -> mixHits(clip, drumHit, 0.0, gain, startFrame, endFrame, acc)
                    TrackType.HIHAT -> mixHits(clip, hihatHit, 0.5, gain, startFrame, endFrame, acc)
                }
            }
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
        for (i in 0 until frames * 2) {
            acc[base + i] += samples.get(i).toInt() * gain / 100
        }
    }

    /** Schläge im Raster: [offsetHits] 0 = auf den Zählzeiten, 0,5 = genau dazwischen. */
    private fun mixHits(
        clip: Clip,
        sound: ShortArray,
        offsetHits: Double,
        gain: Int,
        startFrame: Long,
        endFrame: Long,
        acc: IntArray
    ) {
        val spacing = 1.0 / clip.stretch
        val framesPerHit = framesPerBar / 4.0 * spacing
        val hitLength = sound.size
        val kClipFirst = ceil(clip.startBar * 4.0 / spacing - offsetHits - 1e-9).toLong()
        val kClipLast = ceil(clip.endBar * 4.0 / spacing - offsetHits - 1e-9).toLong() - 1L
        val kChunkFirst = floor((startFrame - hitLength) / framesPerHit - offsetHits).toLong()
        val kChunkLast = floor(endFrame / framesPerHit - offsetHits).toLong()

        var k = maxOf(kClipFirst, kChunkFirst)
        val kEnd = minOf(kClipLast, kChunkLast)
        while (k <= kEnd) {
            val hitStart = ((k + offsetHits) * framesPerHit).toLong()
            val from = maxOf(startFrame, hitStart)
            val to = minOf(endFrame, hitStart + hitLength)
            if (from < to) {
                val base = (from - startFrame).toInt() * 2
                val sourceStart = (from - hitStart).toInt()
                val n = (to - from).toInt()
                for (i in 0 until n) {
                    val v = sound[sourceStart + i].toInt() * gain / 100
                    acc[base + i * 2] += v
                    acc[base + i * 2 + 1] += v
                }
            }
            k++
        }
    }
}
