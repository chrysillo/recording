package app.recording

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Process
import java.io.IOException

/**
 * Reads the mic with AudioRecord on its own thread and passes every buffer to [encoder].
 * [start] throws if the mic can't be opened; after that, the result arrives in [Listener.onFinished]
 * on the recording thread. [pause] releases the mic until [resume]; the take continues without a gap. A take cut short (a mic unplugged, the disk full) is still finished and
 * kept up to that point.
 */
class Recorder(
    private val source: Int,
    private val device: AudioDeviceInfo?,
    private val channels: Int,
    private val encoder: Encoder,
    private val listener: Listener,
) {
    interface Listener {
        fun onLevel(db: Double, clipped: Boolean, ms: Long)
        /**
         * [saved] is false when nothing usable was written or the take was discarded; [problem] says what
         * cut the take short, if anything.
         */
        fun onFinished(saved: Boolean, discarded: Boolean, problem: Exception?, ms: Long)
    }

    companion object {
        private const val RATE = RecordConfig.RATE
        private const val CHUNK_MS = 20
        private const val LEVEL_EVERY_MS = 50
    }

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var discard = false
    private val wake = Object()
    private lateinit var record: AudioRecord

    @SuppressLint("MissingPermission")  // RecorderService checks RECORD_AUDIO before creating a Recorder
    fun start() {
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IOException("This phone can't record in this format")
        record = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 4, RATE * channels * 2 / 5))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); throw IOException("The microphone couldn't be opened") }
        device?.let { record.preferredDevice = it }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IOException("The microphone is in use by another app")
        }
        running = true
        Thread({ loop() }, "recorder").start()
    }

    /** Returns at once; the thread finishes (or, with [discardTake], abandons) the file and then calls [Listener.onFinished]. */
    fun stop(discardTake: Boolean = false) {
        discard = discardTake
        running = false
        synchronized(wake) { wake.notifyAll() }
    }

    fun pause() { paused = true }

    fun resume() = synchronized(wake) { paused = false; wake.notifyAll() }

    /** Stops the mic while paused, so the privacy indicator goes off, and restarts it on resume. */
    private fun waitWhilePaused() {
        record.stop()
        synchronized(wake) { while (paused && running) wake.wait() }
        if (!running) return
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("The microphone is in use by another app")
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(RATE * CHUNK_MS / 1000 * channels)
        var frames = 0L
        var reported = 0L
        var peak = 0
        var problem: Exception? = null
        try {
            while (running) {
                if (paused) { waitWhilePaused(); continue }
                val n = record.read(buf, 0, buf.size)
                if (n < 0) throw IOException("The microphone stopped (error $n)")
                if (n == 0) continue
                encoder.write(buf, n)
                frames += n / channels
                peak = maxOf(peak, Levels.peak(buf, n))
                if ((frames - reported) * 1000 >= RATE.toLong() * LEVEL_EVERY_MS) {
                    listener.onLevel(Levels.toDb(peak), Levels.clipped(peak), frames * 1000 / RATE)
                    reported = frames
                    peak = 0
                }
            }
        } catch (e: Exception) {
            problem = e
        }
        try { record.stop() } catch (_: IllegalStateException) { /* never started */ }
        record.release()
        var saved = false
        if (discard) {
            encoder.abort()
        } else {
            // finish() releases the encoder whether or not it succeeds.
            try { encoder.finish(); saved = true } catch (e: Exception) { if (problem == null) problem = e }
        }
        listener.onFinished(saved, discard, problem, frames * 1000 / RATE)
    }
}
