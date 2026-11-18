package app.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads clips back as PCM, and re-encodes them: voice notes for sharing, louder copies. */
object Transcoder {
    private const val NOTE_RATE = 48_000
    private const val NOTE_BIT_RATE = 32_000
    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_TRIES = 1_000

    /** One block of interleaved 16-bit PCM, with the rate and channel count it's in. */
    fun interface PcmSink { fun block(pcm: ShortArray, count: Int, rate: Int, channels: Int) }

    /** Decodes the first audio track of [src] and streams it to [sink]. Returns that track's format. */
    fun decode(context: Context, src: Uri, sink: PcmSink): MediaFormat {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, src, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio in this file")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val out = Shape(format, sink)
            if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_RAW) readRaw(ex, format, out) else decodeTrack(ex, format, out)
            return format
        } finally {
            ex.release()
        }
    }

    /** Turns a clip into a mono 32 kbps Ogg Opus voice note, the format WhatsApp uses for its own. */
    fun toVoiceNote(context: Context, src: Uri, out: File) {
        // Opus encoding and the Ogg muxer arrived in Android 10.
        if (Build.VERSION.SDK_INT < 29) throw IOException("Voice notes need Android 10 or later")
        val pfd = ParcelFileDescriptor.open(out,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        val enc = CodecEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS, NOTE_RATE, 1, NOTE_BIT_RATE, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, pfd)
        var resampler: LinearResampler? = null
        var resamplerRate = 0
        try {
            decode(context, src) { pcm, count, rate, channels ->
                if (resampler == null || rate != resamplerRate) { resampler = LinearResampler(rate, NOTE_RATE); resamplerRate = rate }
                val mono = Pcm.downmix(pcm, count, channels)
                val note = resampler?.process(mono, mono.size) ?: mono
                if (note.isNotEmpty()) enc.write(note, note.size)
            }
        } catch (e: Exception) {
            enc.abort(); out.delete(); throw e
        }
        enc.finish()
    }

    /** Keeps track of the PCM's shape, which a decoder may only report once it starts. */
    private class Shape(format: MediaFormat, private val sink: PcmSink) {
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        fun changed(f: MediaFormat) {
            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }

        fun push(bytes: ByteBuffer) {
            val shorts = bytes.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val pcm = ShortArray(shorts.remaining()).also { shorts.get(it) }
            if (pcm.isNotEmpty()) sink.block(pcm, pcm.size, rate, channels)
        }
    }

    // WAV comes out of the extractor as raw PCM: no decoder needed.
    private fun readRaw(ex: MediaExtractor, format: MediaFormat, out: Shape) {
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT) {
            throw IOException("Only 16-bit WAV can be converted")
        }
        val buf = ByteBuffer.allocate(1 shl 16)
        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            buf.limit(n)
            out.push(buf)
            ex.advance()
        }
    }

    private fun decodeTrack(ex: MediaExtractor, format: MediaFormat, out: Shape) {
        val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Unknown format"))
        try {
            dec.configure(format, null, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i) ?: throw IOException("Decoder gave no input buffer")
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> out.changed(dec.outputFormat)
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (++idle > MAX_IDLE_TRIES) throw IOException("Decoder stalled")
                    o >= 0 -> {
                        idle = 0
                        val b = dec.getOutputBuffer(o)
                        if (b != null && info.size > 0) {
                            b.position(info.offset); b.limit(info.offset + info.size)
                            out.push(b.slice())
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } finally {
            try { dec.stop() } catch (_: IllegalStateException) { /* not started */ }
            dec.release()
        }
    }
}
