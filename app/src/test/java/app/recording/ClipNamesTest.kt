package app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipNamesTest {
    @Test fun firstClipIsRecording1() {
        assertEquals("Recording1", ClipNames.next(emptySet()))
        assertEquals("Recording1", ClipNames.next(setOf("Chorus idea.m4a", "Recording.m4a", "Recording 2.m4a")))
    }

    @Test fun nextNumberFollowsTheHighestInAnyFormat() {
        assertEquals("Recording4", ClipNames.next(setOf("Recording1.m4a", "Recording3.wav", "recording2.ogg")))
        assertEquals("Recording3", ClipNames.next(setOf("Recording2.m4a")))
        assertEquals("Recording11", ClipNames.next(setOf("Recording10.m4a", "Recording9.m4a")))
    }

    @Test fun uniqueAddsANumberIgnoringCase() {
        assertEquals("Take.m4a", ClipNames.unique("Take", "m4a", setOf("Other.m4a")))
        assertEquals("Take (2).m4a", ClipNames.unique("Take", "m4a", setOf("take.M4A")))
        assertEquals("Take (3).m4a", ClipNames.unique("Take", "m4a", setOf("Take.m4a", "Take (2).m4a")))
    }

    @Test fun cleanRemovesUnsafeCharacters() {
        assertEquals("a b c", ClipNames.clean("  a/b\\c  "))
        assertEquals("line one", ClipNames.clean("line\none"))
        assertNull(ClipNames.clean("  /// "))
        assertNull(ClipNames.clean("..."))
        assertEquals(ClipNames.MAX_LENGTH, ClipNames.clean("x".repeat(500))!!.length)
    }

    @Test fun renameKeepsTheExtension() {
        assertEquals("Chorus idea.m4a", ClipNames.rename("Recording 1.m4a", "Chorus idea", emptySet()))
        assertEquals("Chorus idea.wav", ClipNames.rename("Recording 1.wav", "Chorus idea.wav", emptySet()))
        assertEquals("Chorus idea.mp3.wav", ClipNames.rename("Recording 1.wav", "Chorus idea.mp3", emptySet()))
    }

    @Test fun renameAvoidsOtherClipsButNotItself() {
        assertEquals("Verse (2).m4a", ClipNames.rename("a.m4a", "Verse", setOf("a.m4a", "Verse.m4a")))
        assertEquals("Verse.m4a", ClipNames.rename("verse.m4a", "Verse", setOf("verse.m4a")))
    }

    @Test fun renameRejectsEmptyNames() {
        assertNull(ClipNames.rename("a.m4a", "   ", emptySet()))
        assertNull(ClipNames.rename("a.m4a", ".m4a", emptySet()))
    }

    @Test fun louderCopyName() {
        assertEquals("Recording3 louder.m4a", ClipNames.louder("Recording3.m4a", "m4a", setOf("Recording3.m4a")))
        assertEquals("Recording3 louder (2).m4a", ClipNames.louder("Recording3.m4a", "m4a", setOf("Recording3 louder.m4a")))
        assertEquals(ClipNames.MAX_LENGTH, ClipNames.louder("x".repeat(80) + ".wav", "wav", emptySet()).length - 4)
    }
}
