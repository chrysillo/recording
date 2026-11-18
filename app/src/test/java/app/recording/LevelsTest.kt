package app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LevelsTest {
    private fun sine(amp: Double) = ShortArray(4800) { (amp * sin(2 * PI * 440 * it / 48_000.0)).toInt().toShort() }

    @Test fun fullScaleSineIsZeroDb() {
        val pcm = sine(32767.0)
        val p = Levels.peak(pcm, pcm.size)
        assertEquals(0.0, Levels.toDb(p), 0.01)
        assertTrue(Levels.clipped(p))
    }

    @Test fun halfScaleIsMinusSixDb() {
        val pcm = sine(16384.0)
        val p = Levels.peak(pcm, pcm.size)
        assertEquals(-6.02, Levels.toDb(p), 0.05)
        assertFalse(Levels.clipped(p))
    }

    @Test fun silenceSitsOnTheFloor() {
        assertEquals(Levels.FLOOR_DB, Levels.toDb(Levels.peak(ShortArray(100), 100)), 0.0)
    }

    @Test fun negativeRailCounts() {
        val pcm = shortArrayOf(0, Short.MIN_VALUE, 0)
        assertEquals(32768, Levels.peak(pcm, 3))
        assertTrue(Levels.clipped(32768))
    }

    @Test fun onlyCountsTheSamplesRead() {
        assertEquals(0, Levels.peak(shortArrayOf(0, 0, 30000), 2))
    }

    @Test fun louderBringsThePeakToMinusOneDb() {
        val peak = 2068   // about -24 dB, a quiet raw take
        val gain = Levels.louderGain(peak)!!
        assertEquals(-1.0, Levels.toDb((peak * gain).toInt()), 0.05)
    }

    @Test fun louderIsCappedAtThirtyDb() {
        assertEquals(Math.pow(10.0, 1.5), Levels.louderGain(10)!!, 1e-9)
    }

    @Test fun louderSkipsSilentAndLoudClips() {
        assertEquals(null, Levels.louderGain(0))
        assertEquals(null, Levels.louderGain(30_000))
    }
}
