package app.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.IOException

/** Plays one clip at a time and reports its position. Main thread only. */
class Player(private val context: Context, private val listener: (id: Long, posMs: Int, durMs: Int, playing: Boolean) -> Unit) {
    companion object { private const val TICK_MS = 200L }

    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var id = -1L

    private val tick = object : Runnable {
        override fun run() {
            report()
            if (mp?.isPlaying == true) main.postDelayed(this, TICK_MS)
        }
    }

    /** Plays [clipId], or pauses/resumes it if it's the current clip. Returns false if the file can't be played. */
    fun toggle(clipId: Long, uri: Uri): Boolean {
        val p = mp
        if (p != null && clipId == id) {
            if (p.isPlaying) p.pause() else p.start()
            ticking()
            return true
        }
        stop()
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            next.setDataSource(context, uri)
            next.prepare()
        } catch (e: IOException) {
            next.release(); return false
        } catch (e: RuntimeException) {
            next.release(); return false
        }
        next.setOnCompletionListener { done ->
            done.seekTo(0)
            listener(id, 0, done.duration, false)
        }
        mp = next
        id = clipId
        next.start()
        ticking()
        return true
    }

    fun pause() {
        mp?.let { if (it.isPlaying) it.pause() }
        ticking()
    }

    fun seek(ms: Int) {
        mp?.let { it.seekTo(ms.coerceIn(0, it.duration)) }
        report()
    }

    fun stop() {
        main.removeCallbacks(tick)
        val p = mp ?: return
        val was = id
        mp = null
        id = -1
        p.release()
        listener(was, 0, 0, false)
    }

    private fun ticking() {
        main.removeCallbacks(tick)
        tick.run()
    }

    private fun report() {
        val p = mp ?: return
        listener(id, p.currentPosition, p.duration, p.isPlaying)
    }
}
