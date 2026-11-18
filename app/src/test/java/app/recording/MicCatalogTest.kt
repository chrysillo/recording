package app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicCatalogTest {
    // What a Sony Xperia 1 VI reports: three built-ins with addresses, plus Bluetooth when paired.
    private val bottom = MicInput(24, MicKind.BUILT_IN, "bottom", "Phone")
    private val top = MicInput(25, MicKind.BUILT_IN, "top", "Phone")
    private val back = MicInput(27, MicKind.BUILT_IN, "back", "Phone")
    private val bt = MicInput(30, MicKind.BLUETOOTH, "AA:BB", "Buds")
    private val wired = MicInput(31, MicKind.WIRED, "", "")
    private val usb = MicInput(40, MicKind.USB, "card=1", "Scarlett")

    @Test fun positionsInMainBackTopOrder() {
        assertEquals(listOf(MicPosition.MAIN, MicPosition.BACK, MicPosition.TOP), MicCatalog.builtIns(listOf(top, back, bottom, bt)).keys.toList())
    }

    @Test fun aSingleBuiltInWithoutAddressIsMain() {
        assertEquals(setOf(MicPosition.MAIN), MicCatalog.builtIns(listOf(MicInput(1, MicKind.BUILT_IN, "", ""))).keys)
    }

    @Test fun duplicatesKeepTheLowestId() {
        assertEquals(24, MicCatalog.builtIns(listOf(bottom.copy(id = 99), bottom))[MicPosition.MAIN]!!.id)
    }

    @Test fun resolvesEachPosition() {
        val all = listOf(bottom, top, back)
        assertEquals(24, MicCatalog.resolve("main", all)!!.id)
        assertEquals(27, MicCatalog.resolve("back", all)!!.id)
        assertEquals(25, MicCatalog.resolve("top", all)!!.id)
    }

    @Test fun missingChoicesFallBackToMain() {
        assertEquals(24, MicCatalog.resolve("back", listOf(bottom))!!.id)
        assertEquals(24, MicCatalog.resolve("external", listOf(bottom, bt))!!.id)
        assertEquals(24, MicCatalog.resolve("nonsense", listOf(bottom, back))!!.id)
        assertNull(MicCatalog.resolve("main", emptyList()))
    }

    @Test fun externalPrefersUsbAndIgnoresBluetooth() {
        assertNull(MicCatalog.external(listOf(bottom, bt)))
        assertEquals(31, MicCatalog.external(listOf(bottom, wired, bt))!!.id)
        assertEquals(40, MicCatalog.external(listOf(wired, usb))!!.id)
        assertEquals("USB", MicCatalog.externalLabel(usb))
        assertEquals("Headset", MicCatalog.externalLabel(wired))
        assertEquals(40, MicCatalog.resolve("external", listOf(bottom, usb))!!.id)
    }
}
