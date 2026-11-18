# Recording (Android)

Records and plays audio clips. Tap the mic button to record and again to pause; for the whole take, Save and Discard (with a confirmation) sit beside it. Clips are listed below it with their length and size, and each has a ⋮ menu to rename, share or delete it. A mic chip under the button opens a picker of the phone's mics (Main, Back, Top) with what each is best for, and shares can go out as small voice notes that WhatsApp plays in the chat.

## Running it
1. Open this folder in Android Studio and let Gradle sync.
2. Set **Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK** to JDK 21 (e.g. `jbr-21`). Gradle 8 cannot run on Java 25, and fails with a bare `25.0.3` error.
3. Turn on USB debugging on the phone, plug it in, press ▶.

From a terminal: `./gradlew assembleDebug test` with `JAVA_HOME` pointing at a JDK 21. `test` runs the Kotlin unit tests and, when Node is installed, the JS tests in `app/src/test/js` (`node --test app/src/test/js/*.test.js` on its own).

On-phone tests (`app/src/androidTest`: each encoder round trip, crash recovery and Make louder against the real MediaStore; their clips are named `Test…` and deleted afterwards): `./gradlew connectedAndroidTest`, which uninstalls the app when it's done. To keep it installed, `./gradlew assembleDebug assembleDebugAndroidTest`, `adb install -r` both APKs, then `adb shell am instrument -w app.recording.test/androidx.test.runner.AndroidJUnitRunner`.

## How it's built
No third-party dependencies, only the Android framework (JUnit for unit tests and AndroidX Test for the on-phone ones; the JS tests use Node's built-in runner). The UI is a WebView page; all audio and file work is native.

| File | Role |
|---|---|
| `app/src/main/assets/index.html` | Markup for the two screens (home, Settings), an SVG icon sprite, and the script order |
| `assets/css/app.css` | Layout and components; colours only through theme tokens |
| `assets/js/store.js`, `format.js` | Validated settings in `localStorage`; duration and size text (both pure, tested) |
| `assets/js/ui.js`, `app.js` | DOM helpers, the bottom sheet, dialog and snackbar; router (`R.app.back()` is called by the Android back button and closes a sheet or dialog first), `.seg[data-setting]` bindings, start-up |
| `assets/js/recorder.js`, `clips.js`, `mic.js`, `settings.js` | Record/pause button with Discard (✕) and Save (✓) beside it during a take, a halo behind it that grows with the input level (amber when hot, red on an over), timer; clip list, inline player, ⋮ menu (rename, share, make louder, delete with Undo); the mic chip and its picker (Main / Back / Top, plus a plugged-in USB or headset mic), each with its best use; Settings notes |
| `design/theme.json` | The only place colours are defined (light and dark). `:app:generateTheme` turns it into `theme.css` (CSS variables; dark via `prefers-color-scheme` unless `<html data-theme="light|dark">` overrides it) and `colors.xml` (`light_*`, `dark_*`, plus day/night `theme_*` aliases) at build time |
| `res/values{,-night,-v27,-night-v27}/themes.xml` | Day/night app theme: window background and system bars |
| `MainActivity.kt` | Hosts the WebView (navigation limited to bundled assets), the JS bridge, permissions, the system's consent dialogs for clips from an earlier install, and the share sheet |
| `ClipActions.kt`, `Work.kt` | What the page does to clips (list, rename, delete, Make louder, share as a voice note), run on process-wide worker threads so a job outlives the screen that started it |
| `RecorderService.kt` | Foreground service (type `microphone`) that owns a take: notification with a custom layout (`res/layout/notification_recording.xml`) of icon buttons: Pause/Resume, Discard (asks Keep / Discard in place, for 8 s) and Save; partial wake lock, creates and publishes or discards the file |
| `Recorder.kt` | `AudioRecord` at 48 kHz on its own urgent-audio thread; feeds an `Encoder` and reports peak levels. Pausing stops the `AudioRecord` (the mic indicator goes off) and the take continues without a gap on resume. A take cut short (mic unplugged, disk full) is finished and kept |
| `Encoders.kt` | `WavEncoder` (writes PCM, fills the header in at the end) and `CodecEncoder` (MediaCodec AAC or Opus into a MediaMuxer MP4 or Ogg file) |
| `ClipStore.kt` | The clips, through MediaStore: list, create (pending, or a hidden `.part` file on Android 8–9, until finished), publish, rename, delete |
| `Takes.kt`, `TakeJournal.kt` | A journal per take in `files/takes/` while it records, and on the next start, recovery of takes a crash cut short (see Recording) |
| `Player.kt` | One `MediaPlayer` at a time, reporting position every 200 ms |
| `Transcoder.kt`, `Louder.kt`, `ShareProvider.kt` | Decodes clips to PCM; converts a clip to a mono 32 kbps Ogg Opus voice note in the cache; "Make louder" saves a copy peak-normalised to −1 dB (at most +30 dB) as a new clip in the same format; serves it to the app picked in the share sheet (not exported, per-URI grants) |
| `Mics.kt` | Maps the phone's input devices for `MicCatalog`, and processing choices to audio sources |
| `MicCatalog.kt`, `RecordConfig.kt`, `ClipNames.kt`, `WavHeader.kt`, `Levels.kt`, `Pcm.kt`, `TakeJournal.kt` | Pure Kotlin, unit-tested: built-in mics by position (from the device address: bottom, back, top) and the plugged-in mic; validated recording settings and bit rates; numbered, unique and renamed file names; WAV header write/parse/repair; peak to dBFS; downmix and resampling; the take journal's format |

### Bridge
- JS to native: `Android.caps()` and `Android.mics()` (return JSON), `startRecording(configJson)`, `pauseRecording()`, `resumeRecording()`, `stopRecording()` (saves), `discardRecording()` (deletes; the page confirms first), `listClips()`, `play(id)` (plays, or pauses/resumes the current clip), `pause()`, `seek(ms)`, `rename(id, name)`, `delete(id)`, `share(id, voiceNote)`, `louder(id)`, `setTheme('system'|'light'|'dark')`.
- Native to JS: `onRecState(state, ms, message)` with `recording`, `paused`, `stopped`, `discarded`, `error`, `denied` or `idle`; `onLevel(peakDb, clipped, ms)` about 20 times a second; `onClips([{ id, name, size, dur }])`; `onPlay(id, posMs, durMs, playing)` (`durMs` 0 means stopped); `onMics({ positions, external, unprocessed })` (`positions` from `main`, `back`, `top`; `external` is `"USB"`, `"Headset"` or null); `onShare('preparing'|'ready'|'error')`; `onToast(message)`; `onBusy(message)` while Make louder runs; `onHidden()` when the app goes to the background (a delete still offering Undo goes through then).
- Ids are strings, and the native side acts only on ids from the last list it sent. The config is re-validated by `RecordConfig.of`; names go through `ClipNames.rename` (unsafe characters removed, extension kept, clashes numbered).

### Recording
- Formats: **M4A** (AAC-LC, 128 or 256 kbps; the default), **WAV** (16-bit PCM), **Opus** (Ogg, 48 or 96 kbps; Android 10+). All at 48 kHz, mono or stereo. FLAC isn't offered: Android has an encoder but no muxer for it, and the raw stream lacks a length.
- Sound (Settings): **Natural** uses the `UNPROCESSED` source where the phone supports it, otherwise `VOICE_RECOGNITION` (no auto-gain or noise suppression); **Voice** uses `MIC`.
- Microphone: **Main**, **Back** or **Top**, shown only for the mics the phone reports separately (a Sony Xperia 1 VI reports all three); a USB or wired mic adds a fourth choice while it's plugged in. Bluetooth isn't offered (call quality). A choice that isn't connected falls back to Main. The chip is locked during a take.
- A take survives the app being killed. While it records, `files/takes/` holds a journal naming the unfinished clip, and for M4A and Opus a raw WAV backup of the audio (about 5.5 MB a minute mono, deleted on Save or Discard). On the next start, a WAV take gets its header fixed, and an M4A or Opus take is rebuilt from the backup in its format (or as WAV if that fails), under its own name. Recovery waits if a take is recording.
- A WAV take stops and is saved at 4 GB, the most its header can describe (about 6 hours in stereo, 12 in mono).
- Clips are saved to `Recordings/Recording` (Android 12+) or `Music/Recording` (Android 8–11) in shared storage, so other apps see them and they survive an uninstall. New clips are named `Recording1`, `Recording2`… (one more than the highest number in the folder).

### Permissions
`RECORD_AUDIO` (asked on the first tap of the button; required); `POST_NOTIFICATIONS` and `READ_MEDIA_AUDIO`/`READ_EXTERNAL_STORAGE` (asked once alongside it; optional, the latter so clips from before a reinstall show up); `WRITE_EXTERNAL_STORAGE` on Android 8–9 only; `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`. Renaming or deleting a clip made by an earlier install shows the system's consent dialog.

- Settings are stored in `localStorage` under the key `recording`: `{ theme, format, quality, channels, processing, mic }`. The theme is also kept in SharedPreferences so the system bars are right before the page loads.
- Targets Android 16 (SDK 36, edge-to-edge, so the root view pads for the system bars; back uses `OnBackInvokedCallback` on Android 13+); min SDK 26. The activity handles rotation itself.
- Release builds are shrunk with R8; the default rules keep the `@JavascriptInterface` methods.

## Priorities
Held to the highest bar: audio capture and anything that writes or deletes the user's recordings (no lost or corrupt takes: `Recorder`, `Encoders`, `ClipStore`, `RecorderService`), and the native bridge.

## Known debt
- On Android 8–9, a Make louder copy cut short by a crash stays as a hidden `.part` file in the Recordings folder.
- An M4A or Opus take writes its audio twice (the backup) while it records, so it needs that much free space in app storage too.
- Whether WhatsApp shows a shared voice note as a voice message (with auto-play of the next one) or as an audio file depends on WhatsApp; not yet checked.
- `RecorderService` itself (notification buttons, screen off, a mic unplugged mid-take) has no automated test; check it by hand on a phone.

## Status
**v0.2:** record (M4A, WAV, Opus; mono or stereo) with pause, resume, save and discard; play, rename, share (voice note or original), delete with Undo; microphone and processing choice with explanations. See [docs/ROADMAP.md](docs/ROADMAP.md).
