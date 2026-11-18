package app.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Takes interleaved 16-bit PCM and writes one file. Owns the file descriptor and closes it. */
interface Encoder {
    fun write(pcm: ShortArray, count: Int)
    /** Completes the file. Throws if nothing usable was written. */
    fun finish()
    /** Gives up on the file; the caller deletes it. */
    fun abort()

    companion object {
        fun create(format: Format, pfd: ParcelFileDescriptor, rate: Int, channels: Int, bitRate: Int): Encoder = when (format) {
            Format.WAV -> WavEncoder(pfd, rate, channels)
            Format.M4A -> CodecEncoder(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels, bitRate,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, pfd)
            Format.OPUS -> if (Build.VERSION.SDK_INT >= 29) CodecEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS, rate,
                channels, bitRate, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, pfd)
                else { pfd.close(); throw IOException("Opus needs Android 10 or later") }
        }
    }
}

/**
 * Writes PCM straight to disk. The header is written empty first and filled in by [finish].
 * With [capped], writing stops at the 4 GB a WAV header can describe (about 6 hours in stereo);
 * a backup copy, read back by its length rather than its header, isn't capped.
 */
class WavEncoder(
    pfd: ParcelFileDescriptor,
    private val rate: Int,
    private val channels: Int,
    private val capped: Boolean = true,
) : Encoder {
    private val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    private val file = out.channel
    private var buf = ByteBuffer.allocate(0)
    private var bytes = 0L

    init {
        file.truncate(0)
        file.write(ByteBuffer.wrap(WavHeader.build(rate, channels, 0)))
    }

    override fun write(pcm: ShortArray, count: Int) {
        if (capped && bytes + count * 2 > WavHeader.MAX_DATA) throw IOException("WAV files can't be longer than 4 GB")
        if (buf.capacity() < count * 2) buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.clear()
        buf.asShortBuffer().put(pcm, 0, count)
        buf.limit(count * 2)
        while (buf.hasRemaining()) file.write(buf)
        bytes += count * 2
    }

    override fun finish() {
        try {
            if (bytes == 0L) throw IOException("Nothing was recorded")
            file.write(ByteBuffer.wrap(WavHeader.build(rate, channels, bytes)), 0)
            file.force(true)
        } finally {
            out.close()
        }
    }

    override fun abort() {
        try { out.close() } catch (_: IOException) { /* the file is deleted next */ }
    }
}

/** Encodes with a MediaCodec encoder (AAC or Opus) into a MediaMuxer container (MP4 or Ogg). */
class CodecEncoder(
    mime: String,
    private val rate: Int,
    private val channels: Int,
    bitRate: Int,
    container: Int,
    private val pfd: ParcelFileDescriptor,
) : Encoder {
    companion object {
        private const val TIMEOUT_US = 10_000L
        // How long finish() waits for the encoder to flush before giving up on it (in TIMEOUT_US steps).
        private const val MAX_DRAIN_TRIES = 500
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(mime)
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var frames = 0L

    init {
        try {
            val format = MediaFormat.createAudioFormat(mime, rate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, rate * channels * 2 / 10)
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(pfd.fileDescriptor, container)
        } catch (e: Exception) {
            codec.release()
            pfd.close()
            throw e
        }
    }

    override fun write(pcm: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val idx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (idx < 0) { drain(false); continue }
            val input = codec.getInputBuffer(idx) ?: throw IOException("Encoder gave no input buffer")
            input.clear()
            // Whole frames only, so the timestamps stay exact.
            val n = minOf(count - offset, input.remaining() / 2 / channels * channels)
            if (n <= 0) throw IOException("Encoder input buffer too small")
            input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, offset, n)
            codec.queueInputBuffer(idx, 0, n * 2, frames * 1_000_000 / rate, 0)
            frames += n / channels
            offset += n
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        var tries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++tries > MAX_DRAIN_TRIES) throw IOException("Encoder did not finish")
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)
                    // Codec config travels in the output format; the muxer must not get it as a sample.
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (out != null && info.size > 0 && started) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun finish() {
        try {
            var idx = -1
            var tries = 0
            while (idx < 0) {
                idx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (idx < 0) { drain(false); if (++tries > MAX_DRAIN_TRIES) throw IOException("Encoder did not finish") }
            }
            codec.queueInputBuffer(idx, 0, 0, frames * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            if (!started || frames == 0L) throw IOException("Nothing was recorded")
            muxer.stop()
        } finally {
            release()
        }
    }

    override fun abort() {
        try { if (started) muxer.stop() } catch (_: IllegalStateException) { /* the file is deleted next */ }
        release()
    }

    private fun release() {
        try { codec.stop() } catch (_: IllegalStateException) { /* already stopped */ }
        codec.release()
        muxer.release()
        pfd.close()
    }
}

/**
 * Feeds [primary] and keeps a raw WAV [backup] of the same PCM, so an M4A or Opus take can be
 * rebuilt if the process dies before [primary]'s container is finished. If the backup can't be
 * written (the disk is full), it's dropped and the take carries on without it.
 */
class TeeEncoder(private val primary: Encoder, private var backup: Encoder?) : Encoder {
    override fun write(pcm: ShortArray, count: Int) {
        primary.write(pcm, count)
        val b = backup ?: return
        try { b.write(pcm, count) } catch (e: IOException) { b.abort(); backup = null }
    }

    /** The backup is only needed until [primary] is complete; the caller deletes its file. */
    override fun finish() {
        try { primary.finish() } finally { backup?.abort(); backup = null }
    }

    override fun abort() {
        primary.abort()
        backup?.abort()
        backup = null
    }
}
