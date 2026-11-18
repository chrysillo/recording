package app.recording

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.FileOutputStream

/**
 * Crash recovery and Make louder against the real MediaStore. Clips are named "Test…" and
 * deleted afterwards, so the Recordings folder is left as it was.
 */
@RunWith(AndroidJUnit4::class)
class ClipStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ClipStore(context)
    private val takes = Takes(context, store)
    private val tag = "Test" + System.currentTimeMillis()

    @After fun cleanUp() {
        store.list().filter { it.name.startsWith(tag) }.let { store.delete(it) }
    }

    private fun clip(base: String) = store.list().firstOrNull { ClipNames.base(it.name) == base }

    @Test fun m4aTakeCutShortIsRebuiltFromItsBackup() {
        val name = "$tag-m4a.m4a"
        val c = RecordConfig.DEFAULT.copy(format = Format.M4A)
        val pending = store.create(name, c.format.mime)
        val active = takes.begin(name, c, pending)
        // What a kill leaves: a few bytes of an unfinished MP4, and a backup whose header says 0 bytes.
        FileOutputStream(pending.pfd.fileDescriptor).write(ByteArray(100))
        pending.pfd.close()
        val backup = WavEncoder(ParcelFileDescriptor.open(active.backup!!, ParcelFileDescriptor.MODE_READ_WRITE or
            ParcelFileDescriptor.MODE_CREATE), RecordConfig.RATE, 1, capped = false)
        backup.write(Tone.make(RecordConfig.RATE, 1), RecordConfig.RATE)
        backup.abort()

        assertEquals(1, takes.recover())
        val saved = clip("$tag-m4a")
        assertNotNull(saved)
        assertEquals("$tag-m4a.m4a", saved!!.name)
        assertEquals(1_000.0, saved.durationMs.toDouble(), 60.0)
        assertTrue(!active.journal.exists() && !active.backup.exists())
        assertEquals(0, takes.recover())
    }

    @Test fun wavTakeCutShortGetsItsHeaderFixed() {
        val name = "$tag-wav.wav"
        val c = RecordConfig.DEFAULT.copy(format = Format.WAV)
        val pending = store.create(name, c.format.mime)
        takes.begin(name, c, pending)
        val out = FileOutputStream(pending.pfd.fileDescriptor)
        out.write(WavHeader.build(RecordConfig.RATE, 1, 0))
        val pcm = Tone.make(RecordConfig.RATE / 2, 1)
        out.write(ByteArray(pcm.size * 2) { i -> (pcm[i / 2].toInt() shr (8 * (i % 2))).toByte() })
        pending.pfd.close()

        assertEquals(1, takes.recover())
        val saved = clip("$tag-wav")
        assertNotNull(saved)
        val head = ByteArray(WavHeader.SIZE)
        DataInputStream(context.contentResolver.openInputStream(saved!!.uri)).use { it.readFully(head) }
        assertEquals((RecordConfig.RATE / 2 * 2).toLong(), WavHeader.parse(head)!!.dataBytes)
    }

    @Test fun takeWithNothingRecordedIsDropped() {
        val name = "$tag-empty.m4a"
        val c = RecordConfig.DEFAULT
        val pending = store.create(name, c.format.mime)
        takes.begin(name, c, pending)
        pending.pfd.close()

        assertEquals(0, takes.recover())
        assertNull(clip("$tag-empty"))
    }

    @Test fun louderCopyPeaksJustUnderFullScale() {
        val name = "$tag-quiet.wav"
        val out = store.create(name, Format.WAV.mime)
        val enc = WavEncoder(out.pfd, RecordConfig.RATE, 1)
        enc.write(Tone.make(RecordConfig.RATE, 1, db = -20.0), RecordConfig.RATE)
        enc.finish()
        store.publish(out)
        val quiet = clip("$tag-quiet")!!

        val r = Louder.make(context, quiet, store)
        assertEquals(Louder.Result.Saved("$tag-quiet louder"), r)
        val loud = clip("$tag-quiet louder")!!
        var peak = 0
        Transcoder.decode(context, loud.uri) { p, count, _, _ -> peak = maxOf(peak, Levels.peak(p, count)) }
        assertEquals(Levels.LOUDER_TARGET_DB, Levels.toDb(peak), 0.2)
    }
}
