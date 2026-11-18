package app.recording

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The canonical 44-byte header of a 16-bit PCM WAV file. Pure, so it can be written, read and repaired in tests. */
object WavHeader {
    const val SIZE = 44
    private const val BITS = 16
    // The RIFF size fields are unsigned 32-bit; past this a file can't describe its own length.
    const val MAX_DATA = 0xFFFFFFFFL - 36

    data class Info(val rate: Int, val channels: Int, val dataBytes: Long)

    fun build(rate: Int, channels: Int, dataBytes: Long): ByteArray {
        val data = dataBytes.coerceIn(0, MAX_DATA)
        val b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + data).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        b.putShort(1)  // PCM
        b.putShort(channels.toShort())
        b.putInt(rate)
        b.putInt(rate * channels * BITS / 8)
        b.putShort((channels * BITS / 8).toShort())
        b.putShort(BITS.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(data.toInt())
        return b.array()
    }

    /** Reads a header this app wrote; anything else (other layouts, other sample formats) gives null. */
    fun parse(header: ByteArray): Info? {
        if (header.size < SIZE) return null
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(header, at, 4, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE" || tag(12) != "fmt " || tag(36) != "data") return null
        if (b.getShort(20).toInt() != 1 || b.getShort(34).toInt() != BITS) return null
        val channels = b.getShort(22).toInt()
        val rate = b.getInt(24)
        if (channels !in 1..2 || rate <= 0) return null
        return Info(rate, channels, b.getInt(40).toLong() and 0xFFFFFFFFL)
    }

    /**
     * A new header matching a file of [fileLength] bytes, for a take cut short before its header was
     * finished; null when the header is already right or isn't one of ours.
     */
    fun repair(header: ByteArray, fileLength: Long): ByteArray? {
        val info = parse(header) ?: return null
        val block = info.channels * BITS / 8
        val actual = ((fileLength - SIZE).coerceAtLeast(0) / block) * block
        if (actual.coerceAtMost(MAX_DATA) == info.dataBytes) return null
        return build(info.rate, info.channels, actual)
    }
}
