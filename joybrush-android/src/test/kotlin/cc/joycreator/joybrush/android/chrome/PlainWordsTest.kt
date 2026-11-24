package cc.joycreator.joybrush.android.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JoyBrush's "no technical errors ever shown" rule, pinned with real messages the app can produce
 * (from the 11-24 sweep): technical tails are dropped, the plain front is kept, and a person's own
 * words and names pass untouched.
 */
class PlainWordsTest {

    @Test
    fun technicalTailsAreDroppedAndThePlainFrontKept() {
        assertEquals("Couldn't export. Your drawing is safe.",
            PlainWords.forPerson("Couldn't export. Your drawing is safe. this PNG renderer supports raster layers; vector rendering is not connected"))
        assertEquals("Couldn't open the drawing. Your previous drawing was kept.",
            PlainWords.forPerson("Couldn't open the drawing. Your previous drawing was kept. media tile \"1_0\" is 100 bytes, a media tile is 524288"))
        assertEquals("Couldn't export the board. Your drawing is safe.",
            PlainWords.forPerson("Couldn't export the board. Your drawing is safe. Editable lines or pixel slabs in layer \"l1\" cel \"c1\" cannot be exported by this renderer yet"))
        assertEquals("Couldn't save the copy. Your drawing is safe.",
            PlainWords.forPerson("Couldn't save the copy. Your drawing is safe. the archive could not be written: disk went away"))
        assertEquals("The autosave did not work.",
            PlainWords.forPerson("The autosave did not work: zip entry layers/a/b.rgba runs outside the file"))
        assertEquals("That recent drawing could not be opened.",
            PlainWords.forPerson("That recent drawing could not be opened: this archive cannot be read: ZipException: invalid END header"))
    }

    @Test
    fun aWhollyTechnicalMessageBecomesThePlainFallback() {
        for (m in listOf(
            "The phone has no graphics memory for this stroke (GL error 1285)",
            "A sliced display index has no owned projection",
            "board \"b3f9a2c1d7e4\" uses tween exposures; its export needs the live tween renderer",
            "java.lang.IllegalStateException: boom",
            "IllegalArgumentException",
            "this archive has no \"document.json\" entry",
        )) assertEquals(m, PlainWords.FALLBACK, PlainWords.forPerson(m))
        assertEquals(PlainWords.FALLBACK, PlainWords.forPerson(null))
        assertEquals(PlainWords.FALLBACK, PlainWords.forPerson("  "))
        assertEquals("Choose a smaller board size", PlainWords.forPerson("tile key tx_ty broken", "Choose a smaller board size"))
    }

    @Test
    fun plainMessagesAndAPersonsOwnNamesPassUntouched() {
        for (m in listOf(
            "\"Sketch\" is locked. Unlock it from its menu to change it.",
            "\"Sketch\" was hidden, so it is shown again for your paint.",
            "That shape is too small to fill.",
            "Nothing to fill there.",
            "Fill stroke is too long; draw a smaller shape",
            "The tile can't be larger than this on this phone.",
            "This tile touches too many painting tiles on this phone",
            "Choose a smaller brush for this tile",
            "12 squares of this drawing were lost by the graphics chip and are blank now. Everything else is saved.",
            "The graphics restarted. Reopen your saved drawing before continuing; the empty canvas will not be saved.",
            "Not enough memory to paste those lines. Try fewer lines.",
            "1 layer was left out of the layered file: \"Sketch\" (it is hidden)",
            "A GIF shows at most 50 drawings a second, so 3 very short drawings were left out of it; PNG frames keep every one",
            "Pencil, watercolour and oil paint on a paint layer, and an ink layer has no pixels for them.",
            "Picture exported",
        )) {
            assertFalse(m, PlainWords.isTechnical(m))
            assertEquals(m, PlainWords.forPerson(m))
        }
    }

    @Test
    fun theUsualTechnicalWordsAreCaught() {
        for (m in listOf(
            "the document lists tile \"3_4\" of layer \"a\" cel \"b\", and no tile was given",
            "a pixel slab at seq 4 on tile \"1_1\" is not in its cel's list",
            "DeferredMedia of x.joybrush cannot be read again: IOException",
            "the OpenRaster file could not be written: stack.xml",
            "512 of 1000 bytes arrived",
            "that board export is not connected yet",
            "a tile of layer \"Sketch\" could not be read back from the GPU",
        )) assertTrue(m, PlainWords.isTechnical(m))
    }
}
