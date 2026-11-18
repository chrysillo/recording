package app.recording

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * What's needed to finish a take after the process died mid-recording, kept beside it in app
 * storage until the take is saved or discarded. Pure, so it can be written and read in tests.
 *
 * [uri] or [part] is the unfinished clip (a pending MediaStore entry, or a hidden file on Android
 * 8–9). [backup] is a raw PCM copy for M4A and Opus takes, whose containers can't be finished
 * afterwards; WAV takes have none, since their own header can be repaired.
 */
data class TakeJournal(
    val name: String,
    val format: Format,
    val channels: Int,
    val bitRate: Int,
    val uri: String?,
    val part: String?,
    val backup: String?,
) {
    fun write(): String {
        val p = Properties()
        p["name"] = name
        p["format"] = format.key
        p["channels"] = channels.toString()
        p["bitRate"] = bitRate.toString()
        uri?.let { p["uri"] = it }
        part?.let { p["part"] = it }
        backup?.let { p["backup"] = it }
        return StringWriter().also { p.store(it, null) }.toString()
    }

    companion object {
        /** Null when the text isn't a whole journal (cut short, or from another version). */
        fun read(text: String): TakeJournal? {
            val p = Properties()
            try { p.load(StringReader(text)) } catch (e: IllegalArgumentException) { return null }
            val name = p.getProperty("name")?.takeIf { it.isNotBlank() } ?: return null
            val format = Format.entries.firstOrNull { it.key == p.getProperty("format") } ?: return null
            val channels = p.getProperty("channels")?.toIntOrNull()?.takeIf { it in 1..2 } ?: return null
            val bitRate = p.getProperty("bitRate")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val uri = p.getProperty("uri")
            val part = p.getProperty("part")
            if (uri == null && part == null) return null
            return TakeJournal(name, format, channels, bitRate, uri, part, p.getProperty("backup"))
        }
    }
}
