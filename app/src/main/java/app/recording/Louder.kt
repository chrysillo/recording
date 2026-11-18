package app.recording

import android.content.Context
import android.media.MediaFormat
import android.os.Build

/**
 * "Make louder": a copy of a clip raised so its loudest sample sits just under full scale, saved
 * as a new clip in the same format. Two passes over the file: find the peak, then apply the gain.
 * Natural (raw) takes are quiet on many phones, so this is how they get to a normal level.
 */
object Louder {
    sealed class Result {
        data class Saved(val name: String) : Result()
        data object AlreadyLoud : Result()
    }

    /** Runs off the main thread; long takes take a few seconds. Throws if the clip can't be read or written. */
    fun make(context: Context, clip: Clip, store: ClipStore): Result {
        var peak = 0
        val source = Transcoder.decode(context, clip.uri) { pcm, count, _, _ -> peak = maxOf(peak, Levels.peak(pcm, count)) }
        val gain = Levels.louderGain(peak) ?: return Result.AlreadyLoud

        val format = when (ClipNames.extension(clip.name).lowercase()) {
            "wav" -> Format.WAV
            "ogg", "opus" -> if (Build.VERSION.SDK_INT >= 29) Format.OPUS else Format.M4A
            else -> Format.M4A
        }
        val bitRate = if (source.containsKey(MediaFormat.KEY_BIT_RATE)) source.getInteger(MediaFormat.KEY_BIT_RATE)
            else RecordConfig.DEFAULT.copy(format = format).bitRate
        val name = ClipNames.louder(clip.name, format.ext, store.names())
        val out = store.create(name, format.mime)
        var enc: Encoder? = null
        try {
            Transcoder.decode(context, clip.uri) { pcm, count, rate, channels ->
                val e = enc ?: Encoder.create(format, out.pfd, rate, channels, bitRate).also { enc = it }
                e.write(Pcm.gain(pcm, count, gain), count)
            }
            (enc ?: throw java.io.IOException("No audio in this file")).finish()
            store.publish(out)
        } catch (e: Exception) {
            enc?.abort()
            store.discard(out)
            throw e
        }
        return Result.Saved(ClipNames.base(name))
    }
}
