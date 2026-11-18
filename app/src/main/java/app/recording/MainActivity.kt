package app.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity(), RecorderService.Listener, ClipActions.Host {
    companion object {
        private const val PAGE = "file:///android_asset/index.html"
        private const val ASSETS = "file:///android_asset/"
        private const val PREFS = "recording"
        private const val THEME_KEY = "theme"
        private val THEMES = setOf("system", "light", "dark")
        private const val ASKED_OPTIONAL = "askedOptional"
        private const val REQ_RECORD = 1
        private const val REQ_CONSENT = 2
        // Longest name or settings string the bridge accepts from the page.
        private const val MAX_ARG = 2000
        // Most clip ids one call can name (a selection), and the longest list of them read.
        private const val MAX_IDS = 1000
        private const val MAX_IDS_ARG = 40_000
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var backCallback: OnBackInvokedCallback? = null

    private lateinit var store: ClipStore
    private lateinit var actions: ClipActions
    private lateinit var player: Player
    private lateinit var audio: AudioManager
    private var pendingStart: RecordConfig? = null
    private var consentRetry: (() -> ClipStore.Result)? = null
    private var resumed = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = pushMics()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = pushMics()
    }

    @SuppressLint("SetJavaScriptEnabled")  // the page is bundled and navigation is limited to the app's assets
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ClipStore(this)
        actions = ClipActions(this, store, this)
        audio = getSystemService(AudioManager::class.java)
        player = Player(this) { id, pos, dur, playing -> js("window.onPlay && onPlay('$id',$pos,$dur,$playing)") }
        recoverTakes()
        RecorderService.listener = this
        // System bar colours come from Theme.Recording; the page follows the same day/night setting via theme.css.
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Bundled assets still load; other file:// and content:// URLs do not.
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                !request.url.toString().startsWith(ASSETS)
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl(PAGE)
        root = FrameLayout(this)
        root.addView(web)
        applyTheme(getSharedPreferences(PREFS, MODE_PRIVATE).getString(THEME_KEY, "system") ?: "system")
        setContentView(root)
        // Android 15+ draws edge-to-edge, so keep the page clear of the status and navigation bars.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { goBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        audio.registerAudioDeviceCallback(deviceCallback, Handler(mainLooper))
    }

    /**
     * Colours the window and the system bar icons for "light", "dark" or "system". The page swaps its
     * own colours through data-theme; this keeps the native background and bars in step with it.
     */
    private fun applyTheme(theme: String) {
        val night = when (theme) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val bg = getColor(if (night) R.color.dark_bg else R.color.light_bg)
        root.setBackgroundColor(bg)
        web.setBackgroundColor(bg)
        window.setBackgroundDrawable(ColorDrawable(bg))
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) { window.statusBarColor = bg; window.navigationBarColor = bg }
        // Otherwise the system lays a scrim from the (light) window theme over the navigation bar.
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
        } else {
            @Suppress("DEPRECATION")
            val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (night) window.decorView.systemUiVisibility and flags.inv()
                else window.decorView.systemUiVisibility or flags
        }
    }

    /** Lets the page close what it has open first; the app closes only when the page has nowhere to go back to. */
    private fun goBack() {
        web.evaluateJavascript("window.R && R.app && R.app.back()") { handled ->
            if (handled != "true") finish()
        }
    }

    @Deprecated("Used below Android 13; newer versions use the OnBackInvokedCallback")
    override fun onBackPressed() = goBack()

    override fun js(code: String) = runOnUiThread { if (!isDestroyed) web.evaluateJavascript(code, null) }

    /**
     * Finishes takes a crash left behind, then lists the clips. Skipped while a take is recording
     * (this screen was reopened during it); the next start does it. On [Work.takes], so it can't
     * overlap a take being opened.
     */
    private fun recoverTakes() = Work.takes.execute {
        if (!RecorderService.recording) {
            try { Takes(this, store).recover() } catch (_: Exception) { /* listed as it is */ }
        }
        actions.list()
    }

    // Recording

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Needed to record at all: the mic, and on Android 8–9 writing to shared storage. */
    private fun required(): List<String> =
        listOf(Manifest.permission.RECORD_AUDIO) +
            if (Build.VERSION.SDK_INT < 29) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList()

    /** Asked once alongside the mic: the notification, and seeing clips saved before a reinstall. */
    private fun optional(): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT >= 29 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyList()
    }

    private fun startRecording(c: RecordConfig) {
        if (RecorderService.recording) return
        player.stop()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val missing = required().filterNot(::granted) +
            if (prefs.getBoolean(ASKED_OPTIONAL, false)) emptyList() else optional().filterNot(::granted)
        if (missing.isEmpty()) { RecorderService.start(this, c); return }
        prefs.edit().putBoolean(ASKED_OPTIONAL, true).apply()
        pendingStart = c
        requestPermissions(missing.toTypedArray(), REQ_RECORD)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_RECORD) return
        val c = pendingStart
        pendingStart = null
        if (c != null && required().all(::granted)) RecorderService.start(this, c)
        else onRecState("denied", 0, null)
        actions.list()
    }

    override fun onRecState(state: String, ms: Long, message: String?) {
        js("window.onRecState && onRecState('$state',$ms,${JSONObject.quote(message.orEmpty())})")
        if (state == "stopped" || state == "error" || state == "discarded") actions.list()
    }

    override fun onClipsChanged() = actions.list()

    override fun onLevel(db: Double, clipped: Boolean, ms: Long) {
        if (resumed) js(String.format(Locale.ROOT, "window.onLevel && onLevel(%.1f,%b,%d)", db, clipped, ms))
    }

    private fun parseConfig(json: String): RecordConfig = try {
        val o = JSONObject(json.take(MAX_ARG))
        RecordConfig.of(o.optString("format"), o.optString("quality"), o.optInt("channels", 1),
            o.optString("processing"), o.optString("mic"), opusAvailable = Build.VERSION.SDK_INT >= 29)
    } catch (e: JSONException) {
        RecordConfig.DEFAULT
    }

    // Microphones

    /** Which of Main / Back / Top this phone has, and a plugged-in mic if there is one. */
    private fun micsJson(): String {
        val inputs = Mics.inputs(audio).map { it.first }
        val external = MicCatalog.external(inputs)
        return JSONObject()
            .put("positions", JSONArray(MicCatalog.builtIns(inputs).keys.map { it.key }))
            .put("external", external?.let { MicCatalog.externalLabel(it) } ?: JSONObject.NULL)
            .put("unprocessed", Mics.unprocessedSupported(audio)).toString()
    }

    private fun pushMics() = js("window.onMics && onMics(${micsJson()})")

    // Clips

    override fun askConsent(sender: IntentSender, retry: (() -> ClipStore.Result)?) {
        if (isDestroyed) { actions.list(); return }
        consentRetry = retry
        try {
            startIntentSenderForResult(sender, REQ_CONSENT, null, 0, 0, 0)
        } catch (e: IntentSender.SendIntentException) {
            consentRetry = null
            actions.list()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_CONSENT) return
        val retry = consentRetry
        consentRetry = null
        if (resultCode == RESULT_OK && retry != null) actions.retry(retry) else actions.list()
    }

    override fun send(uris: List<Uri>, mime: String, names: List<String>) {
        if (isDestroyed || uris.isEmpty()) return
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The grant covers the URIs in clipData, so every file goes in it.
        val clip = ClipData.newRawUri(names[0], uris[0])
        for (k in 1 until uris.size) clip.addItem(ClipData.Item(uris[k]))
        i.clipData = clip
        try { startActivity(Intent.createChooser(i, null)) } catch (e: ActivityNotFoundException) { actions.toast("No app to share with") }
    }

    // Lifecycle

    override fun onResume() {
        super.onResume()
        resumed = true
        web.onResume()
        // The take may have started, finished or failed while the page wasn't listening.
        val state = when { !RecorderService.recording -> "idle"; RecorderService.paused -> "paused"; else -> "recording" }
        js("window.onRecState && onRecState('$state',${RecorderService.elapsedMs},'')")
        actions.list()
        pushMics()
    }

    override fun onPause() {
        // Playback stops in the background; a recording carries on in RecorderService.
        resumed = false
        player.pause()
        // Before the page pauses: a delete still offering Undo goes through now.
        js("window.onHidden && onHidden()")
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        audio.unregisterAudioDeviceCallback(deviceCallback)
        if (RecorderService.listener === this) RecorderService.listener = null
        player.stop()
        // Work.clips carries on: a job still running reports to a page that's gone, which js() ignores.
        web.destroy()
        super.onDestroy()
    }

    private fun idList(json: String): List<String> = try {
        val a = JSONArray(json.take(MAX_IDS_ARG))
        (0 until minOf(a.length(), MAX_IDS)).map { a.optString(it) }
    } catch (e: JSONException) {
        emptyList()
    }

    /** Called by page JS on the bridge thread. Every argument is untrusted: ids must be listed clips. */
    inner class Bridge {
        @JavascriptInterface
        fun setTheme(theme: String) {
            if (theme !in THEMES) return
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(THEME_KEY, theme).apply()
            runOnUiThread { if (!isDestroyed) applyTheme(theme) }
        }

        @JavascriptInterface
        fun caps(): String = JSONObject().put("opus", Build.VERSION.SDK_INT >= 29)
            .put("voiceNote", Build.VERSION.SDK_INT >= 29).put("folder", store.folder).toString()

        @JavascriptInterface
        fun mics(): String = micsJson()

        @JavascriptInterface
        fun startRecording(config: String) {
            val c = parseConfig(config)
            runOnUiThread { this@MainActivity.startRecording(c) }
        }

        @JavascriptInterface
        fun stopRecording() = runOnUiThread { RecorderService.stop(this@MainActivity) }

        @JavascriptInterface
        fun pauseRecording() = runOnUiThread { RecorderService.pause(this@MainActivity) }

        @JavascriptInterface
        fun resumeRecording() = runOnUiThread { RecorderService.resume(this@MainActivity) }

        @JavascriptInterface
        fun discardRecording() = runOnUiThread { RecorderService.discard(this@MainActivity) }

        @JavascriptInterface
        fun listClips() = actions.list()

        @JavascriptInterface
        fun play(id: String) {
            val c = actions.clip(id) ?: return
            runOnUiThread { if (!player.toggle(c.id, c.uri)) actions.toast("This clip can't be played") }
        }

        @JavascriptInterface
        fun pause() = runOnUiThread { player.pause() }

        /** Closes the player: playback stops and the page hides it. */
        @JavascriptInterface
        fun stopPlayback() = runOnUiThread { player.stop() }

        @JavascriptInterface
        fun seek(ms: Double) {
            if (!ms.isFinite()) return
            runOnUiThread { player.seek(ms.coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()) }
        }

        @JavascriptInterface
        fun rename(id: String, name: String) {
            val c = actions.clip(id) ?: return
            actions.rename(c, name.take(MAX_ARG))
        }

        /** [ids] is a JSON array of clip ids: one clip, or a selection. */
        @JavascriptInterface
        fun delete(ids: String) {
            val list = actions.clips(idList(ids))
            if (list.isEmpty()) return
            runOnUiThread { player.stop() }
            actions.delete(list)
        }

        /** Shares the original files of the clips in [ids] (a JSON array), together. */
        @JavascriptInterface
        fun shareMany(ids: String) = actions.shareFiles(actions.clips(idList(ids)))

        /** The tick a long press gives elsewhere on the phone. */
        @JavascriptInterface
        fun haptic() = runOnUiThread { if (!isDestroyed) web.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

        @JavascriptInterface
        fun louder(id: String) {
            val c = actions.clip(id) ?: return
            actions.louder(c)
        }

        @JavascriptInterface
        fun share(id: String, voiceNote: Boolean) {
            val c = actions.clip(id) ?: return
            actions.share(c, voiceNote)
        }
    }
}
