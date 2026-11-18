package app.recording

import kotlin.math.floor

/** 16-bit PCM helpers for voice notes and "Make louder". Pure. */
object Pcm {
    /** Scales samples by [gain], rounding and holding them inside 16 bits. */
    fun gain(pcm: ShortArray, count: Int, gain: Double): ShortArray = ShortArray(count) { i ->
        Math.round(pcm[i] * gain).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt().toShort()
    }

    /** Averages interleaved frames down to one channel. */
    fun downmix(pcm: ShortArray, count: Int, channels: Int): ShortArray {
        if (channels <= 1) return pcm.copyOf(count)
        val frames = count / channels
        return ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) sum += pcm[f * channels + c]
            (sum / channels).toShort()
        }
    }
}

/**
 * Streaming linear-interpolation resampler for one channel. Chunks may be any size; the position
 * and last sample carry over, so the output is the same as resampling the whole clip at once.
 * Linear is enough here: the output is a 32 kbps voice note, not a master.
 */
class LinearResampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    // Position of the next output sample, in input samples from the start of the next chunk.
    // Between -1 and 0 it falls between the previous chunk's last sample and this chunk's first.
    private var pos = 0.0
    private var last = 0

    fun process(input: ShortArray, n: Int): ShortArray {
        if (inRate == outRate) return input.copyOf(n)
        if (n <= 0) return ShortArray(0)
        fun at(i: Int) = if (i < 0) last else input[i].toInt()
        val out = ShortArray(((n - pos) / step).toInt() + 2)
        var k = 0
        while (pos <= n - 1) {
            val i = floor(pos).toInt()
            val f = pos - i
            val a = at(i)
            val b = if (i + 1 < n) at(i + 1) else a
            out[k++] = (a + (b - a) * f).toInt().toShort()
            pos += step
        }
        pos -= n
        last = input[n - 1].toInt()
        return out.copyOf(k)
    }
}
