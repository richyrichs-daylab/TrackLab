package com.example.audiomix.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Die Klänge der Tonzeilen von Effekt und Synth. */
object Sounds {
    const val RATE = 44100

    private val effectSet: Array<ShortArray> by lazy {
        arrayOf(DrumSound.hit(RATE), DrumSound.hihat(RATE))
    }

    private val synthSet: Array<ShortArray> by lazy {
        arrayOf(
            DrumSound.synth(RATE, 523.25),
            DrumSound.synth(RATE, 783.99),
            DrumSound.synth(RATE, 130.81),
            DrumSound.synth(RATE, 196.00)
        )
    }

    fun set(type: TrackType): Array<ShortArray> =
        if (type == TrackType.SYNTH) synthSet else effectSet

    fun lane(type: TrackType, lane: Int): ShortArray {
        val s = set(type)
        return s[lane.coerceIn(0, s.size - 1)]
    }

    fun laneCount(type: TrackType): Int = if (type == TrackType.SYNTH) 4 else 2

    fun laneNames(type: TrackType): List<String> =
        if (type == TrackType.SYNTH) {
            listOf("Hoch 1", "Hoch 2", "Tief 1", "Tief 2")
        } else {
            listOf("Trommel", "Hitsch")
        }
}

/** Spielt einen einzelnen Klang kurz ab (beim Antippen eines Feldes). */
object SoundPreview {
    fun play(sound: ShortArray) {
        Thread {
            var track: AudioTrack? = null
            try {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Sounds.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(sound.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track = t
                t.write(sound, 0, sound.size)
                t.play()
                Thread.sleep(sound.size * 1000L / Sounds.RATE + 150L)
            } catch (e: Exception) {
                // ignorieren
            } finally {
                try {
                    track?.release()
                } catch (e: Exception) {
                    // ignorieren
                }
            }
        }.start()
    }
}
