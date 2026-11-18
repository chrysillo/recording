package app.recording

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the page can do to the clips: list, rename, delete (with the system's consent for clips
 * from an earlier install), Make louder and share. The work runs in order on [Work.clips], which
 * outlives the activity, so a job finishes even if the screen closes first; results go to [host].
 */
class ClipActions(context: Context, private val store: ClipStore, private val host: Host) {
    interface Host {
        /** Runs page code on the main thread; does nothing once the page is gone. */
        fun js(code: String)
        /** Shows the system's consent dialog; called on the main thread. */
        fun askConsent(sender: IntentSender, retry: (() -> ClipStore.Result)?)
        /** Opens the share sheet; called on the main thread. */
        fun send(uri: Uri, mime: String, name: String)
    }

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** The clips last sent to the page; the bridge only acts on these ids. */
    @Volatile private var clips = emptyMap<Long, Clip>()

    fun clip(id: String): Clip? = id.toLongOrNull()?.let { clips[it] }

    fun toast(message: String) = host.js("window.onToast && onToast(${JSONObject.quote(message)})")

    fun list() = Work.clips.execute { listNow() }

    /** Sends the list to the page. Runs on [Work.clips]. */
    private fun listNow() {
        val list = try { store.list() } catch (e: Exception) { emptyList() }
        clips = list.associateBy { it.id }
        val json = JSONArray(list.map {
            JSONObject().put("id", it.id.toString()).put("name", it.name).put("size", it.size).put("dur", it.durationMs)
        })
        host.js("window.onClips && onClips($json)")
    }

    fun rename(c: Clip, name: String) = guarded("Couldn't rename the clip") { handle(store.rename(c, name)) }

    fun delete(c: Clip) = guarded("Couldn't delete the clip") { handle(store.delete(c)) }

    /** The user agreed in the consent dialog: do what it was asked for. */
    fun retry(retry: () -> ClipStore.Result) = guarded("Couldn't change the clip") { handle(retry()) }

    fun louder(c: Clip) {
        host.js("window.onBusy && onBusy(${JSONObject.quote("Making “${ClipNames.base(c.name)}” louder…")})")
        guarded("Couldn’t make it louder") {
            when (val r = Louder.make(context, c, store)) {
                is Louder.Result.Saved -> { toast("Saved “${r.name}”"); listNow() }
                is Louder.Result.AlreadyLoud -> toast("It’s already as loud as it can be without distorting")
            }
        }
    }

    /** The original file, or (Android 10+) a small Opus voice note made from it. */
    fun share(c: Clip, voiceNote: Boolean) {
        if (!voiceNote || Build.VERSION.SDK_INT < 29) { main.post { host.send(c.uri, c.mime, c.name) }; return }
        host.js("window.onShare && onShare('preparing')")
        Work.clips.execute {
            try {
                val dir = ShareProvider.dir(context.cacheDir)
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }  // only the latest voice note is kept
                val out = File(dir, ClipNames.base(c.name) + ".ogg")
                Transcoder.toVoiceNote(context, c.uri, out)
                host.js("window.onShare && onShare('ready')")
                main.post { host.send(ShareProvider.uriFor(out), "audio/ogg", out.name) }
            } catch (e: Exception) {
                host.js("window.onShare && onShare('error')")
            }
        }
    }

    /** Acts on a rename or delete result. Runs on [Work.clips]. */
    private fun handle(r: ClipStore.Result) {
        when (r) {
            is ClipStore.Result.Done -> listNow()
            is ClipStore.Result.Invalid -> toast("That name can't be used")
            is ClipStore.Result.NeedsConsent -> main.post { host.askConsent(r.sender, r.retry) }
        }
    }

    // MediaStore and MediaCodec throw several unrelated types; any of them means the job failed.
    private fun guarded(failure: String, work: () -> Unit) = Work.clips.execute {
        try { work() } catch (e: Exception) { toast(failure); listNow() }
    }
}
