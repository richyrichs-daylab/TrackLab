package com.example.audiomix.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MixPlayer {

    private var track: AudioTrack? = null
    private var scope: CoroutineScope? = null

    @Volatile
    private var writtenFrames = 0L

    /** Wird true, sobald alles bis zum Ende abgespielt wurde. */
    @Volatile
    var finished: Boolean = false
        private set

    fun start(renderer: MixRenderer, startFrame: Long) {
        stop()
        finished = false
        writtenFrames = 0L

        val rate = MixRenderer.RATE
        val minBuffer = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBuffer, rate * 4 / 2)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            try {
                val total = renderer.totalFrames
                val chunkFrames = 4096
                val buffer = ShortArray(chunkFrames * 2)
                var position = startFrame
                audioTrack.play()

                while (isActive && position < total) {
                    val n = minOf(chunkFrames.toLong(), total - position).toInt()
                    renderer.render(position, n, buffer)
                    val length = n * 2
                    var offset = 0
                    while (offset < length && isActive) {
                        val written = audioTrack.write(
                            buffer, offset, length - offset, AudioTrack.WRITE_NON_BLOCKING
                        )
                        if (written < 0) {
                            finished = true
                            return@launch
                        }
                        offset += written
                        if (written == 0) delay(5)
                    }
                    position += n
                    writtenFrames = position - startFrame
                }

                // Warten, bis alles wirklich abgespielt wurde
                var waited = 0
                while (isActive && audioTrack.playbackHeadPosition < writtenFrames && waited < 1000) {
                    delay(10)
                    waited++
                }
                if (isActive) finished = true
            } catch (e: Exception) {
                if (isActive) finished = true
            }
        }
    }

    /** Wie viele Frames seit dem Start tatsächlich zu hören waren. */
    fun playedFrames(): Long {
        val t = track ?: return 0L
        return try {
            t.playbackHeadPosition.toLong()
        } catch (e: Exception) {
            0L
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        val t = track
        track = null
        if (t != null) {
            try {
                t.pause()
                t.flush()
            } catch (e: Exception) {
                // ignorieren
            }
            try {
                t.release()
            } catch (e: Exception) {
                // ignorieren
            }
        }
    }
}
