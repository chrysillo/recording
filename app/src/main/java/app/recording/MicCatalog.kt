package app.recording

import java.util.Locale

enum class MicKind { BUILT_IN, WIRED, USB, BLUETOOTH }

/** One input the phone reports, reduced to what the choice needs. */
data class MicInput(val id: Int, val kind: MicKind, val address: String, val product: String)

/** Where a built-in mic sits; phones that report one built-in input give only [MAIN]. */
enum class MicPosition(val key: String) { MAIN("main"), BACK("back"), TOP("top") }

/** Maps the phone's inputs to the Main / Back / Top choice, plus a plugged-in mic. Pure. */
object MicCatalog {
    const val EXTERNAL = "external"

    fun position(m: MicInput): MicPosition? {
        if (m.kind != MicKind.BUILT_IN) return null
        val a = m.address.lowercase(Locale.ROOT)
        return when {
            "back" in a || "rear" in a -> MicPosition.BACK
            "top" in a || "front" in a -> MicPosition.TOP
            else -> MicPosition.MAIN
        }
    }

    /** One input per position, lowest id first; some phones list the same mic twice. */
    fun builtIns(inputs: List<MicInput>): Map<MicPosition, MicInput> =
        inputs.filter { it.kind == MicKind.BUILT_IN }.sortedBy { it.id }
            .groupBy { position(it) ?: MicPosition.MAIN }.mapValues { it.value.first() }
            .toSortedMap(compareBy { it.ordinal })

    /** A USB or wired mic, if one is plugged in. Bluetooth is left out: its link is call quality. */
    fun external(inputs: List<MicInput>): MicInput? =
        inputs.filter { it.kind == MicKind.USB || it.kind == MicKind.WIRED }
            .minWithOrNull(compareBy({ if (it.kind == MicKind.USB) 0 else 1 }, { it.id }))

    fun externalLabel(m: MicInput): String = when (m.kind) {
        MicKind.USB -> "USB"
        else -> "Headset"
    }

    /** The input for a saved choice; anything not connected falls back to the main mic. */
    fun resolve(choice: String, inputs: List<MicInput>): MicInput? {
        val builtIn = builtIns(inputs)
        val main = builtIn[MicPosition.MAIN] ?: builtIn.values.firstOrNull()
        if (choice == EXTERNAL) return external(inputs) ?: main
        val pos = MicPosition.entries.firstOrNull { it.key == choice } ?: return main
        return builtIn[pos] ?: main
    }
}
