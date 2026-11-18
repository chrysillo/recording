package app.recording

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build

/** Maps the phone's input devices to [MicInput]s for [MicCatalog], and settings to audio sources. */
object Mics {
    fun inputs(am: AudioManager): List<Pair<MicInput, AudioDeviceInfo>> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { d ->
            val kind = kind(d.type) ?: return@mapNotNull null
            val address = if (Build.VERSION.SDK_INT >= 28) d.address.orEmpty() else ""
            MicInput(d.id, kind, address, d.productName?.toString().orEmpty()) to d
        }

    private fun kind(type: Int): MicKind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicKind.BUILT_IN
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicKind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicKind.BLUETOOTH
        else -> if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET) MicKind.BLUETOOTH else null
    }

    /** The device for a saved choice ("main", "back", "top" or "external"), or null to let the system choose. */
    fun device(am: AudioManager, choice: String): AudioDeviceInfo? {
        val all = inputs(am)
        val pick = MicCatalog.resolve(choice, all.map { it.first }) ?: return null
        return all.firstOrNull { it.first.id == pick.id }?.second
    }

    fun unprocessedSupported(am: AudioManager) =
        am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    fun source(am: AudioManager, p: Processing): Int = when (p) {
        // Raw where the phone offers it; voice recognition is otherwise the source with the least processing.
        Processing.MUSIC -> if (unprocessedSupported(am)) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        Processing.VOICE -> MediaRecorder.AudioSource.MIC
    }
}
