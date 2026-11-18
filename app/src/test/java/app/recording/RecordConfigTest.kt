package app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordConfigTest {
    @Test fun validValuesAreKept() {
        val c = RecordConfig.of("wav", "high", 2, "voice", "back", opusAvailable = true)
        assertEquals(RecordConfig(Format.WAV, true, 2, Processing.VOICE, "back"), c)
        assertEquals(48_000 * 2 * 16, c.bitRate)
    }

    @Test fun anythingElseFallsBackToTheDefaults() {
        assertEquals(RecordConfig.DEFAULT, RecordConfig.of("mp3", "max", 6, "studio", "../x", opusAvailable = true))
        assertEquals(RecordConfig.DEFAULT, RecordConfig.of(null, null, null, null, null, opusAvailable = true))
        assertEquals("main", RecordConfig.of("m4a", null, 1, null, "17", opusAvailable = true).mic)
        assertEquals("external", RecordConfig.of("m4a", null, 1, null, "external", opusAvailable = true).mic)
        assertEquals(Processing.MUSIC, RecordConfig.of("m4a", null, 1, "room", null, opusAvailable = true).processing)
    }

    @Test fun opusNeedsTheEncoder() {
        assertEquals(Format.M4A, RecordConfig.of("opus", null, 1, null, null, opusAvailable = false).format)
        assertEquals(Format.OPUS, RecordConfig.of("opus", null, 1, null, null, opusAvailable = true).format)
    }

    @Test fun bitRates() {
        assertEquals(128_000, RecordConfig.DEFAULT.bitRate)
        assertEquals(256_000, RecordConfig.DEFAULT.copy(highQuality = true).bitRate)
        assertEquals(48_000, RecordConfig.DEFAULT.copy(format = Format.OPUS).bitRate)
    }
}
