package com.example.audiomix.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.pow

/** Die Klänge der Tonzeilen von Effekt und Synth. */
object Sounds {
    const val RATE = 44100

    /** Synth: 20 Töne von oben (A5) nach unten (C3), als MIDI-Nummern. */
    private val synthMidi = intArrayOf(81, 79, 77, 76, 74, 72, 71, 69, 67, 65, 64, 62, 60, 59, 57, 55, 53, 52, 50, 48)

    private val effectSet: Array<ShortArray> by lazy {
        arrayOf(
            DrumSound.hit(RATE),
            DrumSound.hihat(RATE),
            FxSound.hihat2(RATE),
            FxSound.knaller(RATE),
            FxSound.clap(RATE),
            FxSound.snap(RATE),
            FxSound.pen(RATE),
            FxSound.krach(RATE),
            FxSound.zap(RATE)
        )
    }

    private fun hz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private val synthSet: Array<ShortArray> by lazy {
        Array(synthMidi.size) { i -> DrumSound.synth(RATE, hz(synthMidi[i])) }
    }

    private val pianoSet: Array<ShortArray> by lazy {
        Array(synthMidi.size) { i -> DrumSound.piano(RATE, hz(synthMidi[i])) }
    }

    /** Bass: eine Oktave tiefer als der Synth. */
    private val bassSet: Array<ShortArray> by lazy {
        Array(synthMidi.size) { i -> DrumSound.bass(RATE, hz(synthMidi[i] - 12)) }
    }

    val INSTRUMENTS = listOf("Synth", "Piano", "Bass")

    fun set(type: TrackType, instrument: Int = 0): Array<ShortArray> =
        if (type == TrackType.SYNTH) {
            when (instrument) {
                1 -> pianoSet
                2 -> bassSet
                else -> synthSet
            }
        } else {
            effectSet
        }

    fun lane(type: TrackType, lane: Int, instrument: Int = 0): ShortArray {
        val s = set(type, instrument)
        return s[lane.coerceIn(0, s.size - 1)]
    }

    fun laneCount(type: TrackType): Int = if (type == TrackType.SYNTH) synthMidi.size else 9

    private fun noteName(midi: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H")
        return names[midi % 12] + (midi / 12 - 1)
    }

    fun laneNames(type: TrackType, instrument: Int = 0): List<String> =
        if (type == TrackType.SYNTH) {
            synthMidi.map { noteName(if (instrument == 2) it - 12 else it) }
        } else {
            listOf(
                "Trommel", "Hitsch", "Hitsch 2", "Knaller", "Händeklatschen",
                "Fingerschnipsen", "Pen click", "Krach", "zzzzz"
            )
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
