package app.recording

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** Input level maths for the meter. Pure. */
object Levels {
    const val FLOOR_DB = -90.0
    private const val FULL_SCALE = 32768.0

    /** Largest absolute sample in the first [count] samples, 0 to 32768. */
    fun peak(pcm: ShortArray, count: Int): Int {
        var p = 0
        for (i in 0 until count) p = max(p, abs(pcm[i].toInt()))
        return p
    }

    fun toDb(peak: Int): Double = if (peak <= 0) FLOOR_DB else max(FLOOR_DB, 20 * log10(peak / FULL_SCALE))

    /** A sample at either rail means the input was louder than 16 bits can hold. */
    fun clipped(peak: Int) = peak >= Short.MAX_VALUE

    /** Where "Make louder" puts a clip's loudest sample: just under full scale, leaving room for the encoder. */
    const val LOUDER_TARGET_DB = -1.0
    /** The most "Make louder" adds, so a near-silent take doesn't become pure hiss. */
    const val LOUDER_MAX_DB = 30.0
    /** Less than this isn't worth a new file. */
    const val LOUDER_MIN_DB = 1.0

    /** The linear gain that brings [peak] up to the target, capped; null when the clip is silent or already loud. */
    fun louderGain(peak: Int): Double? {
        if (peak <= 0) return null
        val db = minOf(LOUDER_TARGET_DB - toDb(peak), LOUDER_MAX_DB)
        return if (db < LOUDER_MIN_DB) null else Math.pow(10.0, db / 20)
    }
}
