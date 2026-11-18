package app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TakeJournalTest {
    private val m4a = TakeJournal("Recording7.m4a", Format.M4A, 2, 128_000,
        "content://media/external_primary/audio/media/42", null, "/data/files/takes/1.wav")

    @Test fun roundTrip() {
        assertEquals(m4a, TakeJournal.read(m4a.write()))
    }

    @Test fun legacyWavTakeHasAPartFileAndNoBackup() {
        val wav = TakeJournal("Recording 3 = take: 2.wav", Format.WAV, 1, 768_000, null, "/sdcard/Music/Recording/.x.part", null)
        assertEquals(wav, TakeJournal.read(wav.write()))
    }

    @Test fun halfWrittenOrForeignTextIsRejected() {
        val whole = m4a.write()
        assertNull(TakeJournal.read(""))
        assertNull(TakeJournal.read(whole.lines().filterNot { it.startsWith("format") }.joinToString("\n")))
        assertNull(TakeJournal.read(whole.replace("format=m4a", "format=flac")))
        assertNull(TakeJournal.read(whole.replace("channels=2", "channels=6")))
    }

    @Test fun needsSomethingToRecover() {
        val nowhere = m4a.copy(uri = null, part = null)
        assertNull(TakeJournal.read(nowhere.write()))
    }
}
