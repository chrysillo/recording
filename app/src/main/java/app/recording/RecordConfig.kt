package app.recording

/** File formats the app can record to. [ext] and [mime] are what the Recordings folder gets. */
enum class Format(val key: String, val ext: String, val mime: String) {
    M4A("m4a", "m4a", "audio/mp4"),
    WAV("wav", "wav", "audio/x-wav"),
    OPUS("opus", "ogg", "audio/ogg"),
}

/** Natural sound (no auto-gain or noise suppression), or the phone's voice processing. */
enum class Processing(val key: String) { MUSIC("music"), VOICE("voice") }

/** Settings for one recording. Built only through [of], which treats every value as untrusted. Pure. */
data class RecordConfig(
    val format: Format,
    val highQuality: Boolean,
    val channels: Int,
    val processing: Processing,
    /** "main", "back", "top" or "external"; see [MicCatalog.resolve]. */
    val mic: String,
) {
    /** Encoder bit rate in bits per second. */
    val bitRate: Int
        get() = when (format) {
            Format.M4A -> if (highQuality) 256_000 else 128_000
            Format.OPUS -> if (highQuality) 96_000 else 48_000
            Format.WAV -> RATE * channels * 16
        }

    companion object {
        /** Every take is captured at 48 kHz: most phones' native rate, and the rate Opus works at. */
        const val RATE = 48_000
        private val MICS = MicPosition.entries.map { it.key }.toSet() + MicCatalog.EXTERNAL
        val DEFAULT = RecordConfig(Format.M4A, false, 1, Processing.MUSIC, MicPosition.MAIN.key)

        fun of(format: String?, quality: String?, channels: Int?, processing: String?, mic: String?, opusAvailable: Boolean): RecordConfig {
            val f = Format.entries.firstOrNull { it.key == format }
                ?.takeIf { it != Format.OPUS || opusAvailable } ?: DEFAULT.format
            return RecordConfig(
                format = f,
                highQuality = quality == "high",
                channels = if (channels == 2) 2 else 1,
                processing = Processing.entries.firstOrNull { it.key == processing } ?: DEFAULT.processing,
                mic = mic?.takeIf { it in MICS } ?: DEFAULT.mic,
            )
        }
    }
}
