package app.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmTest {
    @Test fun downmixAveragesChannels() {
        assertArrayEquals(shortArrayOf(150, -50), Pcm.downmix(shortArrayOf(100, 200, -100, 0), 4, 2))
    }

    @Test fun monoPassesThrough() {
        assertArrayEquals(shortArrayOf(1, 2), Pcm.downmix(shortArrayOf(1, 2, 3), 2, 1))
    }

    @Test fun sameRateIsUnchanged() {
        assertArrayEquals(shortArrayOf(1, 2, 3), LinearResampler(48_000, 48_000).process(shortArrayOf(1, 2, 3, 4), 3))
    }

    @Test fun upsamplingDoublesTheLengthAndInterpolates() {
        val out = LinearResampler(24_000, 48_000).process(shortArrayOf(0, 100, 200), 3)
        assertArrayEquals(shortArrayOf(0, 50, 100, 150, 200), out)
    }

    @Test fun chunkingGivesTheSameResultAsOneBlock() {
        val input = ShortArray(1000) { ((it * 37) % 2000 - 1000).toShort() }
        val whole = LinearResampler(44_100, 48_000).process(input, input.size)
        val r = LinearResampler(44_100, 48_000)
        val parts = listOf(0 until 1, 1 until 333, 333 until 334, 334 until 1000)
            .flatMap { range -> r.process(input.copyOfRange(range.first, range.last + 1), range.count()).toList() }
        assertEquals(whole.size, parts.size)
        for (i in whole.indices) assertTrue("sample $i", abs(whole[i] - parts[i]) <= 1)
    }

    @Test fun lengthFollowsTheRateRatio() {
        val out = LinearResampler(16_000, 48_000).process(ShortArray(16_000) { 500 }, 16_000)
        assertTrue(abs(out.size - 48_000) <= 3)
        assertTrue(out.all { it.toInt() == 500 })
    }

    @Test fun gainScalesAndHoldsInsideSixteenBits() {
        assertArrayEquals(shortArrayOf(200, -200, 32767, -32768), Pcm.gain(shortArrayOf(100, -100, 20000, -20000, 5), 4, 2.0))
    }
}
