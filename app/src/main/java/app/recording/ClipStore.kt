package app.recording

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A saved recording in the Recordings folder. */
data class Clip(val id: Long, val name: String, val size: Long, val durationMs: Long, val mime: String, val uri: Uri)

/**
 * A recording being written, hidden from other apps until [ClipStore.publish]: a pending MediaStore
 * entry ([uri]), or on Android 8–9 a hidden [part] file that becomes [file].
 */
class NewClip(val uri: Uri?, val part: File?, val file: File?, val pfd: ParcelFileDescriptor)

/**
 * The clips in the public Recordings folder, through MediaStore so no storage permission is needed
 * for the app's own files on Android 10+. Android 8–9 write the file directly and scan it in.
 * Every method does I/O: call off the main thread.
 */
class ClipStore(private val context: Context) {
    sealed class Result {
        data object Done : Result()
        data object Invalid : Result()
        /** The system must ask the user first (a clip from before a reinstall); run [retry] if they agree. */
        class NeedsConsent(val sender: IntentSender, val retry: (() -> Result)?) : Result()
    }

    companion object {
        private const val SUBFOLDER = "Recording"
        private const val SETTLE_SECONDS = 5L
    }

    private val resolver = context.contentResolver
    private val durations = ConcurrentHashMap<String, Long>()

    private val collection: Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    /** Where clips go, as other apps show it. */
    val folder: String =
        if (Build.VERSION.SDK_INT >= 31) "${Environment.DIRECTORY_RECORDINGS}/$SUBFOLDER"
        else "${Environment.DIRECTORY_MUSIC}/$SUBFOLDER"

    @Suppress("DEPRECATION")  // the direct path is how Android 8–9 store public files
    private fun legacyDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUBFOLDER)

    private fun selection(): Pair<String, Array<String>> =
        if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" to arrayOf("$folder/")
        else @Suppress("DEPRECATION") "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("${legacyDir().absolutePath}/%")

    fun list(): List<Clip> {
        val (where, args) = selection()
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.Audio.AudioColumns.DURATION)
        val out = ArrayList<Clip>()
        resolver.query(collection, cols, where, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uri = ContentUris.withAppendedId(collection, id)
                val size = c.getLong(2)
                val dur = c.getLong(4).takeIf { it > 0 } ?: duration(id, size, uri)
                out += Clip(id, c.getString(1) ?: "", size, dur, c.getString(3) ?: "audio/*", uri)
            }
        }
        return out
    }

    // MediaStore fills in durations when it scans, which can lag behind a new file.
    private fun duration(id: Long, size: Long, uri: Uri): Long = durations.getOrPut("$id:$size") {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: RuntimeException) {
            0L
        } finally {
            r.release()
        }
    }

    fun names(): Set<String> = list().map { it.name }.toSet()

    private fun mimeFor(fileName: String, fallback: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ClipNames.extension(fileName).lowercase()) ?: fallback

    fun create(fileName: String, mime: String): NewClip {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                // MediaStore renames a file whose extension doesn't match its type, so take the type from the extension.
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(fileName, mime))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create the file")
            val pfd = try {
                resolver.openFileDescriptor(uri, "rw")
            } catch (e: Exception) {
                resolver.delete(uri, null, null); throw e
            } ?: run { resolver.delete(uri, null, null); throw IOException("Couldn't open the file") }
            return NewClip(uri, null, null, pfd)
        }
        val dir = legacyDir()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't create ${dir.path}")
        // A dot file, which the media scanner and file managers skip, until it's published.
        val part = File(dir, ".$fileName.part")
        val pfd = ParcelFileDescriptor.open(part,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        return NewClip(null, part, File(dir, fileName), pfd)
    }

    /**
     * The unfinished clip a [TakeJournal] points to, opened for writing again; null when it's gone.
     * Android 10+ lets the app reopen its own pending entry by URI, so this works there too.
     */
    fun reopen(j: TakeJournal): NewClip? = try {
        if (j.uri != null) {
            val uri = Uri.parse(j.uri)
            resolver.openFileDescriptor(uri, "rw")?.let { NewClip(uri, null, null, it) }
        } else {
            val part = File(j.part ?: return null)
            if (!part.isFile || part.parentFile != legacyDir()) null
            else NewClip(null, part, File(legacyDir(), j.name),
                ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_READ_WRITE))
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /** Makes a finished recording visible to the list and to other apps. */
    fun publish(c: NewClip) {
        if (c.uri != null) {
            resolver.update(c.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else if (c.part != null && c.file != null) {
            // The name was free when the take began; a rename since may have taken it.
            val taken = legacyDir().list()?.toSet().orEmpty()
            val dest = File(legacyDir(), ClipNames.unique(ClipNames.base(c.file.name), ClipNames.extension(c.file.name), taken))
            if (!c.part.renameTo(dest)) throw IOException("Couldn't save ${dest.name}")
            scan(dest)
        }
    }

    fun discard(c: NewClip) {
        try { c.pfd.close() } catch (_: IOException) { /* deleting it anyway */ }
        if (c.uri != null) resolver.delete(c.uri, null, null)
        c.part?.delete()
    }

    /** Waits briefly for the scanner so the list shows the file straight away. */
    private fun scan(file: File) {
        val done = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, _ -> done.countDown() }
        done.await(SETTLE_SECONDS, TimeUnit.SECONDS)
    }

    fun rename(clip: Clip, input: String): Result {
        val newName = ClipNames.rename(clip.name, input, names()) ?: return Result.Invalid
        if (newName == clip.name) return Result.Done
        return guarded(clip, { rename(clip, input) }) {
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(clip.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null)
            } else {
                val from = File(legacyDir(), clip.name)
                val to = File(legacyDir(), newName)
                if (!from.renameTo(to)) throw IOException("Couldn't rename ${clip.name}")
                resolver.delete(clip.uri, null, null)
                scan(to)
            }
        }
    }

    /**
     * Deletes [clips]. Clips from an earlier install need the user's consent: Android 11+ asks once
     * for all of them, and its request deletes them by itself; Android 10 asks one clip at a time,
     * and each yes carries on with the rest.
     */
    fun delete(clips: List<Clip>): Result {
        val refused = ArrayList<Clip>()
        var ask: RecoverableSecurityException? = null
        for (clip in clips) {
            try {
                resolver.delete(clip.uri, null, null)
                if (Build.VERSION.SDK_INT < 29) File(legacyDir(), clip.name).delete()
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT < 29) throw e
                refused += clip
                if (ask == null && e is RecoverableSecurityException) ask = e
            }
        }
        val first = ask
        return when {
            refused.isEmpty() -> Result.Done
            Build.VERSION.SDK_INT >= 30 ->
                Result.NeedsConsent(MediaStore.createDeleteRequest(resolver, refused.map { it.uri }).intentSender, null)
            first != null -> Result.NeedsConsent(first.userAction.actionIntent.intentSender) { delete(refused) }
            else -> throw SecurityException("Couldn't delete ${refused.size} recordings")
        }
    }

    /**
     * Runs [action]; when it's refused because the clip belongs to an earlier install, returns the
     * system's consent request instead, with [retry] to run if the user agrees.
     */
    private fun guarded(clip: Clip, retry: () -> Result, action: () -> Unit): Result = try {
        action(); Result.Done
    } catch (e: SecurityException) {
        when {
            Build.VERSION.SDK_INT >= 30 ->
                Result.NeedsConsent(MediaStore.createWriteRequest(resolver, listOf(clip.uri)).intentSender, retry)
            Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException ->
                Result.NeedsConsent(e.userAction.actionIntent.intentSender, retry)
            else -> throw e
        }
    }

    /** Rewrites a WAV header that doesn't match the file's length. Returns whether the file is a usable WAV. */
    fun fixWav(pfd: ParcelFileDescriptor): Boolean {
        val ch = FileInputStream(pfd.fileDescriptor).channel
        val head = ByteBuffer.allocate(WavHeader.SIZE)
        while (head.hasRemaining() && ch.read(head, head.position().toLong()) > 0) { /* reading the header */ }
        if (WavHeader.parse(head.array()) == null || pfd.statSize <= WavHeader.SIZE) return false
        val fixed = WavHeader.repair(head.array(), pfd.statSize) ?: return true
        java.io.FileOutputStream(pfd.fileDescriptor).channel.write(ByteBuffer.wrap(fixed), 0)
        return true
    }
}
