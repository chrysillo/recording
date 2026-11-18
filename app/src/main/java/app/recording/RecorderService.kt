package app.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * Records inside a foreground service, so a take keeps going with the screen off or another app
 * in front. A notification shows the running time with Pause/Resume, Discard and Save buttons.
 */
class RecorderService : Service() {
    /** Told about the take on the main thread. Set by the visible activity. */
    interface Listener {
        fun onRecState(state: String, ms: Long, message: String?)
        fun onLevel(db: Double, clipped: Boolean, ms: Long)
        /** A clip was saved some other way than a take ending normally (rebuilt from its backup). */
        fun onClipsChanged()
    }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_DISCARD = "discard"
        // Discard from the notification asks first, in the notification itself.
        private const val ACTION_DISCARD_ASK = "discardAsk"
        private const val ACTION_DISCARD_KEEP = "discardKeep"
        private const val CONFIRM_MS = 8_000L
        private const val CHANNEL = "recording"
        private const val NOTE_ID = 1
        // Longer than any take; only a guard against a lock that's never released.
        private const val WAKE_LOCK_MS = 12L * 60 * 60 * 1000

        @Volatile var listener: Listener? = null
        @Volatile var recording = false
            private set
        @Volatile var elapsedMs = 0L
            private set
        @Volatile var paused = false
            private set

        fun start(context: Context, c: RecordConfig) {
            context.startForegroundService(Intent(context, RecorderService::class.java).setAction(ACTION_START)
                .putExtra("format", c.format.key).putExtra("quality", if (c.highQuality) "high" else "standard")
                .putExtra("channels", c.channels).putExtra("processing", c.processing.key).putExtra("mic", c.mic))
        }

        /** Ends the take and saves it. */
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        /** Ends the take and deletes it; the page asks for confirmation first. */
        fun discard(context: Context) = send(context, ACTION_DISCARD)

        private fun send(context: Context, action: String) {
            if (recording) context.startService(Intent(context, RecorderService::class.java).setAction(action))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var recorder: Recorder? = null
    // Set in onDestroy; a take still opening then is stopped as soon as its recorder exists.
    private var destroyed = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopRequested = false
    private var discardRequested = false
    private var confirming = false
    private val cancelConfirm = Runnable { confirming = false; refreshNote() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // A second start while a take is opening or running changes nothing, but it still came
            // through startForegroundService, which must be answered with startForeground.
            ACTION_START -> if (!recording) begin(intent) else goForeground()
            ACTION_STOP, ACTION_DISCARD -> when {
                // Ending a paused take wakes the recording thread without restarting the mic.
                recorder != null -> recorder?.stop(discardTake = intent?.action == ACTION_DISCARD)
                recording -> { stopRequested = true; discardRequested = intent?.action == ACTION_DISCARD }  // still opening the mic
                else -> stopSelf()
            }
            ACTION_PAUSE -> recorder?.let { if (!paused) { it.pause(); setPaused(true) } }
            ACTION_RESUME -> resumeIfPaused()
            ACTION_DISCARD_ASK -> if (recording) {
                confirming = true
                refreshNote()
                main.removeCallbacks(cancelConfirm)
                main.postDelayed(cancelConfirm, CONFIRM_MS)
            }
            ACTION_DISCARD_KEEP -> cancelConfirm.run()
            else -> if (!recording) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        // A foreground service must call startForeground promptly, even if it then fails.
        try {
            goForeground()
        } catch (e: RuntimeException) {
            fail("Recording couldn't start: ${e.message}")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone access is off"); return
        }
        val c = RecordConfig.of(intent.getStringExtra("format"), intent.getStringExtra("quality"),
            intent.getIntExtra("channels", 1), intent.getStringExtra("processing"), intent.getStringExtra("mic"),
            opusAvailable = Build.VERSION.SDK_INT >= 29)
        recording = true
        stopRequested = false
        discardRequested = false
        paused = false
        elapsedMs = 0
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recording:take").apply { acquire(WAKE_LOCK_MS) }
        Work.takes.execute { open(c) }
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTE_ID, notification())
    }

    /** Creates the file and its journal, and starts the mic. Runs on [Work.takes]. */
    private fun open(c: RecordConfig) {
        val store = ClipStore(this)
        val takes = Takes(this, store)
        val am = getSystemService(AudioManager::class.java)
        val taken = store.names()
        val name = ClipNames.unique(ClipNames.next(taken), c.format.ext, taken)
        val clip = try { store.create(name, c.format.mime) } catch (e: Exception) {
            main.post { fail("Couldn't create the file: ${e.message}") }; return
        }
        var active: Takes.Active? = null
        var encoder: Encoder? = null
        val rec = try {
            active = takes.begin(name, c, clip)
            val enc = Encoder.create(c.format, clip.pfd, RecordConfig.RATE, c.channels, c.bitRate)
            val backup = active.backup?.let { f ->
                try {
                    WavEncoder(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE), RecordConfig.RATE, c.channels, capped = false)
                } catch (e: Exception) { enc.abort(); throw e }
            }
            encoder = if (backup != null) TeeEncoder(enc, backup) else enc
            Recorder(Mics.source(am, c.processing), Mics.device(am, c.mic), c.channels, encoder,
                Events(store, clip, takes, active)).also { it.start() }
        } catch (e: Exception) {
            // The mic didn't start: release the codec and close the files before deleting them.
            try { encoder?.abort() } catch (_: Exception) { /* deleting it anyway */ }
            store.discard(clip)
            active?.let { takes.end(it) }
            main.post { fail(e.message ?: "The microphone couldn't be opened") }
            return
        }
        main.post {
            // Destroyed while the mic was opening: don't leave it recording without the service.
            if (destroyed) { rec.stop(discardTake = discardRequested); return@post }
            recorder = rec
            listener?.onRecState("recording", 0, null)
            if (stopRequested) rec.stop(discardTake = discardRequested)
        }
    }

    private inner class Events(
        private val store: ClipStore,
        private val clip: NewClip,
        private val takes: Takes,
        private val active: Takes.Active,
    ) : Recorder.Listener {
        override fun onLevel(db: Double, clipped: Boolean, ms: Long) {
            elapsedMs = ms
            main.post { listener?.onLevel(db, clipped, ms) }
        }

        override fun onFinished(saved: Boolean, discarded: Boolean, problem: Exception?, ms: Long) {
            // The encoder couldn't finish an M4A or Opus file, but the backup has the audio: rebuild it now.
            val rebuild = !saved && !discarded && ms >= 1000 && (active.backup?.let { it.length() > WavHeader.SIZE } == true)
            val message = when {
                discarded -> null
                rebuild -> "The take couldn't be finished (${problem?.message}); it's being rebuilt from its backup"
                saved && problem != null -> "The take stopped early (${problem.message}); it was saved up to there"
                !saved && ms < 1000 -> "Too short to save"
                !saved -> "The take couldn't be saved: ${problem?.message}"
                else -> null
            }
            try { if (saved) store.publish(clip) else store.discard(clip) } catch (_: Exception) { /* reported below */ }
            if (rebuild) {
                // Before any next take can open: Work.takes runs one job at a time.
                Work.takes.execute {
                    try { takes.recover() } catch (_: Exception) { /* left for the next start */ }
                    main.post { listener?.onClipsChanged() }
                }
            } else {
                takes.end(active)
            }
            main.post { finish(if (saved) "stopped" else if (discarded) "discarded" else "error", ms, message) }
        }
    }

    private fun fail(message: String) = finish("error", 0, message)

    private fun resumeIfPaused() {
        val r = recorder ?: return
        if (!paused) return
        r.resume()
        setPaused(false)
    }

    private fun refreshNote() {
        if (recording) getSystemService(NotificationManager::class.java).notify(NOTE_ID, notification())
    }

    private fun setPaused(p: Boolean) {
        paused = p
        refreshNote()
        listener?.onRecState(if (p) "paused" else "recording", elapsedMs, null)
    }

    private fun finish(state: String, ms: Long, message: String?) {
        main.removeCallbacks(cancelConfirm)
        confirming = false
        recorder = null
        recording = false
        paused = false
        elapsedMs = 0
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        listener?.onRecState(state, ms, message)
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(cancelConfirm)
        recorder?.stop()
        super.onDestroy()
    }

    /** Icon buttons in a custom layout: standard notification actions show as text only on most phones. */
    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun action(code: Int, a: String) =
            PendingIntent.getService(this, code, Intent(this, RecorderService::class.java).setAction(a), flags)
        // The same buttons in both layouts: collapsed on one line, expanded with the buttons below.
        fun views(layout: Int) = RemoteViews(packageName, layout).also { v ->
            v.setTextViewText(R.id.note_title, getString(when {
                confirming -> R.string.note_title_confirm; paused -> R.string.note_title_paused; else -> R.string.note_title_recording
            }))
            // The chronometer counts from a base on the elapsed-realtime clock; paused, it shows the time frozen.
            v.setChronometer(R.id.note_time, SystemClock.elapsedRealtime() - elapsedMs, null, !paused)
            fun show(id: Int, on: Boolean) = v.setViewVisibility(id, if (on) View.VISIBLE else View.GONE)
            listOf(R.id.note_discard, R.id.note_toggle, R.id.note_save).forEach { show(it, !confirming) }
            listOf(R.id.note_confirm, R.id.note_keep).forEach { show(it, confirming) }
            v.setImageViewResource(R.id.note_toggle, if (paused) R.drawable.ic_n_mic else R.drawable.ic_n_pause)
            v.setContentDescription(R.id.note_toggle, getString(if (paused) R.string.note_resume else R.string.note_pause))
            v.setOnClickPendingIntent(R.id.note_toggle, if (paused) action(2, ACTION_RESUME) else action(3, ACTION_PAUSE))
            v.setOnClickPendingIntent(R.id.note_discard, action(4, ACTION_DISCARD_ASK))
            v.setOnClickPendingIntent(R.id.note_save, action(1, ACTION_STOP))
            v.setOnClickPendingIntent(R.id.note_keep, action(5, ACTION_DISCARD_KEEP))
            v.setOnClickPendingIntent(R.id.note_confirm, action(6, ACTION_DISCARD))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.notification_recording))
            .setCustomBigContentView(views(R.layout.notification_recording_big))
            .build()
    }

}
