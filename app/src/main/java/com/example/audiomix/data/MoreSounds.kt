package com.example.audiomix.data

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Weitere synthetische Klänge für die Effekt-Zeilen. */
object FxSound {

    private fun toShort(v: Double): Short = (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()

    /** Futuristische Strahlenpistole: schnelles "piuu" von hoch nach tief. */
    fun knaller(rate: Int): ShortArray {
        val length = (0.4 * rate).toInt()
        val out = ShortArray(length)
        var phase = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            val freq = 150.0 + 2300.0 * exp(-t * 11.0)
            phase += 2.0 * PI * freq / rate
            val attack = if (t < 0.002) t / 0.002 else 1.0
            val env = attack * exp(-t * 5.0)
            val carrier = sin(phase + 0.9 * sin(2.0 * PI * 44.0 * t))
            val v = (carrier + 0.4 * sin(2.0 * phase) + 0.18 * sin(3.0 * phase)) * env * 0.36
            out[i] = toShort(v)
        }
        return out
    }

    /** Händeklatschen: mehrere schnelle Rauschstöße, dann ein kurzer Nachhall. */
    fun clap(rate: Int): ShortArray {
        val length = (0.26 * rate).toInt()
        val out = ShortArray(length)
        val rnd = Random(11)
        var prev = 0.0
        var low = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            val noise = rnd.nextDouble() * 2.0 - 1.0
            val high = noise - prev
            prev = noise
            low += 0.55 * (high - low)
            val env = if (t < 0.036) exp(-(t % 0.012) * 420.0) else exp(-(t - 0.036) * 24.0) * 0.8
            out[i] = toShort(low * env * 0.9)
        }
        return out
    }

    /** Fingerschnipsen: sehr kurzer heller Knack mit einem kleinen "Plopp". */
    fun snap(rate: Int): ShortArray {
        val length = (0.14 * rate).toInt()
        val out = ShortArray(length)
        val rnd = Random(5)
        var prev = 0.0
        var p1 = 0.0
        var p2 = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            val noise = rnd.nextDouble() * 2.0 - 1.0
            val high = noise - prev
            prev = noise
            p1 += 2.0 * PI * 2600.0 / rate
            p2 += 2.0 * PI * 950.0 / rate
            val click = high * exp(-t * 500.0) * 0.8
            val tone = sin(p1) * exp(-t * 70.0) * 0.5
            val pop = sin(p2) * exp(-t * 45.0) * 0.45
            out[i] = toShort((click + tone + pop) * 0.9)
        }
        return out
    }

    /** Kugelschreiber-Klick: Klick-Klack. */
    fun pen(rate: Int): ShortArray {
        val length = (0.17 * rate).toInt()
        val out = ShortArray(length)
        val rnd = Random(3)
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            var v = sin(2.0 * PI * 3300.0 * t) * exp(-t * 260.0) * 0.6 +
                (rnd.nextDouble() * 2.0 - 1.0) * exp(-t * 1200.0) * 0.5 +
                sin(2.0 * PI * 700.0 * t) * exp(-t * 120.0) * 0.25
            val t2 = t - 0.075
            if (t2 >= 0.0) {
                v += sin(2.0 * PI * 2300.0 * t2) * exp(-t2 * 220.0) * 0.55 +
                    (rnd.nextDouble() * 2.0 - 1.0) * exp(-t2 * 1000.0) * 0.45
            }
            out[i] = toShort(v * 0.8)
        }
        return out
    }

    /** Krach: harter Stampfer mit scheppernden Blechteilen. */
    fun krach(rate: Int): ShortArray {
        val length = (0.7 * rate).toInt()
        val out = ShortArray(length)
        val rnd = Random(21)
        val partials = doubleArrayOf(410.0, 663.0, 1107.0, 1790.0, 2410.0, 3270.0)
        val starts = doubleArrayOf(0.03, 0.07, 0.11, 0.16, 0.22, 0.29)
        var phase = 0.0
        var prev = 0.0
        var low = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            val freq = 38.0 + 90.0 * exp(-t * 35.0)
            phase += 2.0 * PI * freq / rate
            var v = sin(phase) * exp(-t * 11.0)
            val noise = rnd.nextDouble() * 2.0 - 1.0
            low += 0.12 * (noise - low)
            v += low * exp(-t * 30.0) * 1.4
            val high = noise - prev
            prev = noise
            if (t > 0.02) v += high * exp(-t * 16.0) * 0.28
            for (k in partials.indices) {
                val s = t - starts[k]
                if (s >= 0.0) v += sin(2.0 * PI * partials[k] * s) * exp(-s * (9.0 + k * 2.0)) * 0.22
            }
            out[i] = toShort(v * 0.55)
        }
        return out
    }

    /** Blecherner Stromschlag: flackerndes "zzzzz". */
    fun zap(rate: Int): ShortArray {
        val length = (0.5 * rate).toInt()
        val out = ShortArray(length)
        val rnd = Random(9)
        var prev = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            val attack = if (t < 0.004) t / 0.004 else 1.0
            val env = attack * exp(-t * 3.2)
            val saw = ((t * 118.0) % 1.0) * 2.0 - 1.0
            val fm = sin(2.0 * PI * 1300.0 * t + 5.0 * sin(2.0 * PI * 95.0 * t))
            val noise = rnd.nextDouble() * 2.0 - 1.0
            val high = noise - prev
            prev = noise
            val gate = if (sin(2.0 * PI * 57.0 * t) > -0.3) 1.0 else 0.25
            val v = (0.4 * saw + 0.35 * fm + 0.45 * high) * env * gate * 0.45
            out[i] = toShort(v)
        }
        return out
    }

    /** Ein anderer Hi-Hat: offen, metallisch, mit längerem Ausklingen. */
    fun hihat2(rate: Int): ShortArray {
        val length = (0.22 * rate).toInt()
        val out = ShortArray(length)
        val freqs = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)
        var prev1 = 0.0
        var prev2 = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            var s = 0.0
            for (f in freqs) {
                s += if ((t * f) % 1.0 < 0.5) 1.0 else -1.0
            }
            val hp1 = s - prev1
            prev1 = s
            val hp2 = hp1 - prev2
            prev2 = hp1
            out[i] = toShort(hp2 * exp(-t * 17.0) * 0.09)
        }
        return out
    }
}
