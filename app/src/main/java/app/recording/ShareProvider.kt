package app.recording

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the voice notes made for sharing (cache/share/) to the app picked in the share sheet.
 * Not exported: other apps only get the one URI they were granted. Saved clips are shared by
 * their MediaStore URI instead.
 */
class ShareProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "app.recording.share"
        fun dir(cacheDir: File) = File(cacheDir, "share")
        fun uriFor(file: File): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.name).build()
    }

    // Only plain names inside the share folder; no paths.
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (uri.pathSegments.size != 1 || name.startsWith(".")) throw FileNotFoundException()
        val f = File(dir((context ?: throw FileNotFoundException()).cacheDir), name)
        if (!f.isFile) throw FileNotFoundException()
        return f
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "audio/ogg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // Share targets ask for the name and size before reading.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}
