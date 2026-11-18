package app.recording

import java.util.Locale

/** File names for clips: defaults, uniqueness and renames. Pure. */
object ClipNames {
    const val MAX_LENGTH = 80
    // Characters that file systems or share targets reject, plus control characters.
    private val BAD = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    private val NUMBERED = Regex("Recording(\\d{1,9})(\\.[^.]*)?", RegexOption.IGNORE_CASE)

    /**
     * "Recording1", "Recording2"…: one more than the highest number already used in [taken], in any
     * format, so a deleted clip's number isn't handed out again while later ones exist.
     */
    fun next(taken: Set<String>): String {
        val highest = taken.mapNotNull { NUMBERED.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull() ?: 0
        return "Recording${highest + 1}"
    }

    fun extension(name: String): String = name.substringAfterLast('.', "")

    fun base(name: String): String = if ('.' in name) name.substringBeforeLast('.') else name

    /** "base.ext", or "base (2).ext" and so on when that name is taken (compared ignoring case). */
    fun unique(base: String, ext: String, taken: Set<String>): String {
        val lower = taken.map { it.lowercase(Locale.ROOT) }.toSet()
        fun full(b: String) = if (ext.isEmpty()) b else "$b.$ext"
        if (full(base).lowercase(Locale.ROOT) !in lower) return full(base)
        var n = 2
        while (full("$base ($n)").lowercase(Locale.ROOT) in lower) n++
        return full("$base ($n)")
    }

    /** The name for a louder copy of [oldName], in format [ext]: "Recording3 louder.m4a", numbered if taken. */
    fun louder(oldName: String, ext: String, taken: Set<String>): String =
        unique(base(oldName).take(MAX_LENGTH - 7).trimEnd() + " louder", ext, taken)

    /** What the user typed, made safe for a file name; null when nothing usable is left. */
    fun clean(input: String): String? {
        val s = input.replace(BAD, " ").replace(Regex("\\s+"), " ").trim().trim('.').trim()
        return s.take(MAX_LENGTH).trim().ifEmpty { null }
    }

    /**
     * The new file name for [oldName] renamed to [input]: the extension is kept (and not doubled if
     * typed), and a clash with another clip gets a number. Null when the input can't be used.
     */
    fun rename(oldName: String, input: String, taken: Set<String>): String? {
        val ext = extension(oldName)
        val typed = input.trim()
        val bare = if (ext.isNotEmpty() && typed.endsWith(".$ext", ignoreCase = true)) typed.dropLast(ext.length + 1) else typed
        val b = clean(bare) ?: return null
        val others = taken.filterNot { it.equals(oldName, ignoreCase = true) }.toSet()
        return unique(b, ext, others)
    }
}
