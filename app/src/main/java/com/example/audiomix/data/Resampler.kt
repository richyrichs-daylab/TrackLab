package com.example.audiomix.data

import kotlin.math.roundToInt

/** Rechnet Audio frameweise auf eine andere Abtastrate um (lineare Interpolation). */
class StreamResampler(inRate: Int, outRate: Int) {
    private var ratio = inRate.toDouble() / outRate
    private var inIndex = 0L
    private var outCount = 0L
    private var prevL = 0
    private var prevR = 0

    fun setInRate(inRate: Int, outRate: Int) {
        ratio = inRate.toDouble() / outRate
    }

    fun feed(l: Int, r: Int, out: (Int, Int) -> Unit) {
        if (inIndex == 0L) {
            prevL = l
            prevR = r
        }
        var outT = outCount * ratio
        while (outT <= inIndex) {
            val frac = outT - (inIndex - 1)
            val ol = (prevL + (l - prevL) * frac).roundToInt().coerceIn(-32768, 32767)
            val orr = (prevR + (r - prevR) * frac).roundToInt().coerceIn(-32768, 32767)
            out(ol, orr)
            outCount++
            outT = outCount * ratio
        }
        prevL = l
        prevR = r
        inIndex++
    }
}
