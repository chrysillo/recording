package app.recording

import android.content.Context
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Keeps a [TakeJournal] in app storage (files/takes/) for each take while it records, and finishes
 * the takes a crash left behind:
 * - a WAV take gets its header fixed from the file's length and is published;
 * - an M4A or Opus take's own file can't be finished, so it's rebuilt from its PCM backup in the
 *   chosen format, or saved as WAV if that fails.
 * Runs on [Work.takes], so recovery never overlaps a take being opened.
 */
class Takes(private val context: Context, private val store: ClipStore) {
    companion object {
        private const val JOURNAL = ".take"
        // Whole stereo frames, so a read never splits one.
        private const val CHUNK_BYTES = 19_200
    }

    /** A take that's recording: its journal, and the backup file for M4A and Opus. */
    class Active(val journal: File, val backup: File?)

    private val dir = File(context.filesDir, "takes")

    /** Writes the journal for a take about to start; [Active.backup] is where its PCM copy goes. */
    fun begin(name: String, c: RecordConfig, clip: NewClip): Active {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't create ${dir.path}")
        val id = System.currentTimeMillis().toString()
        val backup = if (c.format == Format.WAV) null else File(dir, "$id.wav")
        val j = TakeJournal(name, c.format, c.channels, c.bitRate, clip.uri?.toString(), clip.part?.path, backup?.path)
        val journal = File(dir, id + JOURNAL)
        // Written whole and then renamed, so a crash never leaves half a journal.
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(j.write())
        if (!tmp.renameTo(journal)) throw IOException("Couldn't write ${journal.name}")
        return Active(journal, backup)
    }

    /** The take was saved or discarded: nothing left to recover. */
    fun end(a: Active) {
        a.backup?.delete()
        a.journal.delete()
    }

    /** Finishes every take left behind. Returns how many were saved. */
    fun recover(): Int {
        var saved = 0
        val journals = dir.listFiles { f -> f.name.endsWith(JOURNAL) }.orEmpty().sortedBy { it.name }
        for (f in journals) {
            val j = try { TakeJournal.read(f.readText()) } catch (e: IOException) { null }
            if (j != null && recover(j)) saved++
            j?.backup?.let { File(it).delete() }
            f.delete()
        }
        // Backups and temp files without a journal (a crash between writing them) are of no use.
        val left = dir.listFiles().orEmpty().filterNot { it.name.endsWith(JOURNAL) }
        left.forEach { it.delete() }
        return saved
    }

    private fun recover(j: TakeJournal): Boolean {
        val target = store.reopen(j)
        if (j.backup == null) {
            // A WAV take: its own file has everything up to the crash.
            val clip = target ?: return false
            val usable = try { store.fixWav(clip.pfd) } catch (e: IOException) { false }
            if (!usable) { store.discard(clip); return false }
            try { clip.pfd.close() } catch (_: IOException) { /* written already */ }
            store.publish(clip)
            return true
        }
        target?.let { store.discard(it) }
        val backup = File(j.backup)
        if (backup.length() <= WavHeader.SIZE) return false
        return try {
            rebuild(backup, j, j.format)
            true
        } catch (e: Exception) {
            // MediaCodec throws several unrelated types; a WAV can always be written.
            try { rebuild(backup, j, Format.WAV); true } catch (e2: Exception) { false }
        }
    }

    /** Encodes the backup's PCM into a new clip named like the lost one. */
    private fun rebuild(backup: File, j: TakeJournal, format: Format) {
        val name = ClipNames.unique(ClipNames.base(j.name), format.ext, store.names())
        val out = store.create(name, format.mime)
        val bitRate = if (format == j.format) j.bitRate else RecordConfig.DEFAULT.copy(format = format, channels = j.channels).bitRate
        val enc = try { Encoder.create(format, out.pfd, RecordConfig.RATE, j.channels, bitRate) } catch (e: Exception) {
            store.discard(out); throw e
        }
        try {
            FileInputStream(backup).use { input ->
                skipFully(input, WavHeader.SIZE.toLong())
                val bytes = ByteArray(CHUNK_BYTES)
                val shorts = ShortArray(CHUNK_BYTES / 2)
                while (true) {
                    val n = readUpTo(input, bytes)
                    // The backup is read by its length, and only whole frames are kept.
                    val count = n / 2 / j.channels * j.channels
                    if (count > 0) {
                        ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, count)
                        enc.write(shorts, count)
                    }
                    if (n < bytes.size) break
                }
            }
            enc.finish()
            store.publish(out)
        } catch (e: Exception) {
            enc.abort()
            store.discard(out)
            throw e
        }
    }

    private fun readUpTo(input: FileInputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val n = input.read(into, total, into.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipFully(input: FileInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) throw EOFException()
            left -= s
        }
    }
}
