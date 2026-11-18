package app.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WavHeaderTest {
    @Test fun roundTrip() {
        val h = WavHeader.build(48_000, 2, 192_000)
        assertEquals(44, h.size)
        assertEquals(WavHeader.Info(48_000, 2, 192_000), WavHeader.parse(h))
    }

    @Test fun byteRateAndBlockAlign() {
        val h = WavHeader.build(48_000, 1, 0)
        fun le32(at: Int) = (h[at].toInt() and 0xFF) or ((h[at + 1].toInt() and 0xFF) shl 8) or
            ((h[at + 2].toInt() and 0xFF) shl 16) or ((h[at + 3].toInt() and 0xFF) shl 24)
        assertEquals(96_000, le32(28))
        assertEquals(2, h[32].toInt())
        assertEquals(36, le32(4))
    }

    @Test fun repairsATakeCutShort() {
        val unfinished = WavHeader.build(48_000, 1, 0)
        val fixed = WavHeader.repair(unfinished, 44L + 9_600)
        assertEquals(WavHeader.Info(48_000, 1, 9_600), WavHeader.parse(fixed!!))
    }

    @Test fun repairDropsAPartialFrame() {
        val fixed = WavHeader.repair(WavHeader.build(48_000, 2, 0), 44L + 4_003)
        assertEquals(4_000L, WavHeader.parse(fixed!!)!!.dataBytes)
    }

    @Test fun leavesACorrectHeaderAlone() {
        assertNull(WavHeader.repair(WavHeader.build(48_000, 1, 100), 144))
    }

    @Test fun rejectsOtherFiles() {
        assertNull(WavHeader.parse(ByteArray(44)))
        assertNull(WavHeader.parse(ByteArray(10)))
        assertNull(WavHeader.repair("not a wav file at all, just some bytes here!".toByteArray(), 1000))
    }

    @Test fun clampsHugeSizesToWhatRiffCanHold() {
        val h = WavHeader.build(48_000, 1, 10_000_000_000)
        assertEquals(0xFFFFFFFFL - 36, WavHeader.parse(h)!!.dataBytes)
        assertArrayEquals(h, WavHeader.build(48_000, 1, 0xFFFFFFFFL - 36))
    }
}
