package app.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Encodes a known tone through each encoder on the phone's own codecs and reads the file back. */
@RunWith(AndroidJUnit4::class)
class EncoderRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun wavMono() = roundTrip(Format.WAV, 1)
    @Test fun wavStereo() = roundTrip(Format.WAV, 2)
    @Test fun m4aMono() = roundTrip(Format.M4A, 1)
    @Test fun m4aStereo() = roundTrip(Format.M4A, 2)

    @Test fun opusMono() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        roundTrip(Format.OPUS, 1)
    }

    @Test fun opusStereo() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        roundTrip(Format.OPUS, 2)
    }

    @Test fun wavHeaderGetsTheLength() {
        val f = File(context.cacheDir, "cap.wav")
        val enc = WavEncoder(open(f), RecordConfig.RATE, 1)
        enc.write(Tone.make(RecordConfig.RATE / 10, 1), RecordConfig.RATE / 10)
        enc.finish()
        val head = f.readBytes().copyOf(WavHeader.SIZE)
        assertEquals((RecordConfig.RATE / 10 * 2).toLong(), WavHeader.parse(head)!!.dataBytes)
        f.delete()
    }

    private fun roundTrip(format: Format, channels: Int) {
        val f = File(context.cacheDir, "tone.${format.ext}")
        val c = RecordConfig.DEFAULT.copy(format = format, channels = channels)
        val enc = Encoder.create(format, open(f), RecordConfig.RATE, channels, c.bitRate)
        val frames = RecordConfig.RATE * 2
        val chunk = RecordConfig.RATE / 50   // 20 ms, as Recorder writes
        val pcm = Tone.make(frames, channels)
        var at = 0
        while (at < frames) {
            val n = minOf(chunk, frames - at) * channels
            enc.write(pcm.copyOfRange(at * channels, at * channels + n), n)
            at += n / channels
        }
        enc.finish()

        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.path)
            assertEquals(1, ex.trackCount)
            val t = ex.getTrackFormat(0)
            assertEquals(RecordConfig.RATE, t.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(channels, t.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            if (t.containsKey(MediaFormat.KEY_DURATION)) {
                assertEquals(2_000.0, t.getLong(MediaFormat.KEY_DURATION) / 1000.0, 50.0)
            }
        } finally {
            ex.release()
        }

        // Decoded back, the tone has the length and level it went in with.
        var decoded = 0L
        var peak = 0
        Transcoder.decode(context, Uri.fromFile(f)) { p, count, _, ch ->
            decoded += count / ch
            peak = maxOf(peak, Levels.peak(p, count))
        }
        assertEquals(2_000.0, decoded * 1000.0 / RecordConfig.RATE, 50.0)
        assertEquals(Tone.DB, Levels.toDb(peak), 1.5)
        assertTrue(f.delete())
    }

    private fun open(f: File) = ParcelFileDescriptor.open(f,
        ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
}

/** A 1 kHz sine at −6 dBFS, interleaved. */
object Tone {
    const val DB = -6.0

    fun make(frames: Int, channels: Int, db: Double = DB): ShortArray {
        val amp = 32768 * Math.pow(10.0, db / 20)
        return ShortArray(frames * channels) { i ->
            val t = (i / channels).toDouble() / RecordConfig.RATE
            (amp * Math.sin(2 * Math.PI * 1000 * t)).toInt().toShort()
        }
    }
}
