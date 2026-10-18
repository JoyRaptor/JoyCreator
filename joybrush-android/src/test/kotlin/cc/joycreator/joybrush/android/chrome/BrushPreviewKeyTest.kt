package cc.joycreator.joybrush.android.chrome

import cc.joycreator.joybrush.androidkit.preview.BrushPreviewStyle
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What names a stored brush preview: the brush as the person has it, the size, and the look. */
class BrushPreviewKeyTest {

    private val style = BrushPreviewStyle()

    private fun brush(id: String = "ink:pen", size: Float = 12f) = BrushPreset(id = id, name = "Pen", size = Param(size))

    @Test fun theSameBrushSizeAndLookGiveTheSameKeyEveryTime() {
        val a = BrushPreviewKey.of(brush(), 900, 90, style)
        val b = BrushPreviewKey.of(brush(), 900, 90, style)
        assertEquals(a, b)
        assertEquals(a.fileName, b.fileName)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun theFileNameIsIdHashAndSize() {
        val key = BrushPreviewKey.of(brush("ink:pen"), 900, 90, style)
        assertTrue(key.fileName, Regex("ink_pen_[0-9a-f]{16}_900x90\\.png").matches(key.fileName))
    }

    @Test fun anySettingOfTheBrushChangesTheKeyButNotTheIdPart() {
        val a = BrushPreviewKey.of(brush(size = 12f), 900, 90, style)
        val b = BrushPreviewKey.of(brush(size = 13f), 900, 90, style)
        assertNotEquals(a.hash, b.hash)
        assertEquals(a.idPart, b.idPart)
    }

    @Test fun aDifferentSizeOfPictureOrLookIsAnotherPicture() {
        val base = BrushPreviewKey.of(brush(), 900, 90, style)
        assertNotEquals(base, BrushPreviewKey.of(brush(), 901, 90, style))
        assertNotEquals(base, BrushPreviewKey.of(brush(), 900, 91, style))
        assertEquals(base.hash, BrushPreviewKey.of(brush(), 901, 90, style).hash, "the size is in the name, not the hash")
        assertNotEquals(base.hash, BrushPreviewKey.of(brush(), 900, 90, style.copy(ink = 0xFF000000.toInt())).hash)
    }

    @Test fun aNewRendererVersionWouldMakeEveryNameNew() {
        // The version is part of the hashed text: a different text gives a different hash.
        assertNotEquals(BrushPreviewKey.hash("v1|x"), BrushPreviewKey.hash("v2|x"))
        assertEquals(16, BrushPreviewKey.hash("anything").length)
        assertTrue(BrushPreviewKey.hash("anything").all { it in "0123456789abcdef" })
    }

    @Test fun anIdBecomesASafeFileNamePart() {
        assertEquals("media_dry_HB", BrushPreviewKey.sanitize("media:dry:HB"))
        assertEquals("my_brush_2", BrushPreviewKey.sanitize("my brush/2"))
        assertEquals("a.b-c", BrushPreviewKey.sanitize("a.b-c"))
        assertEquals("brush", BrushPreviewKey.sanitize(":::"))
        assertEquals("brush", BrushPreviewKey.sanitize(""))
        assertEquals(48, BrushPreviewKey.sanitize("x".repeat(200)).length)
        assertTrue(BrushPreviewKey.sanitize("..\\..\\evil").none { it == '/' || it == '\\' })
    }

    @Test fun aStoredNameParsesBackToItsParts() {
        val key = BrushPreviewKey.of(brush("media:wet:Round"), 640, 77, style)
        val back = assertNotNull(BrushPreviewKey.parse(key.fileName))
        assertEquals(key, back)
        assertEquals("media_wet_Round", back.idPart)
        assertEquals(640, back.width)
        assertEquals(77, back.height)
    }

    @Test fun aFileThatIsNotOurNameDoesNotParse() {
        assertNull(BrushPreviewKey.parse("notes.txt"))
        assertNull(BrushPreviewKey.parse("pen_0123456789abcdef_900x90.png.tmp"))
        assertNull(BrushPreviewKey.parse("pen_nothex_900x90.png"))
        assertNull(BrushPreviewKey.parse("pen_0123456789abcdef_900.png"))
        assertNull(BrushPreviewKey.parse("_0123456789abcdef_900x90.png"))
    }

    @Test fun aBrushThatCannotBeWrittenAsAFileStillGetsAKey() {
        val broken = brush(size = Float.NaN)
        val key = BrushPreviewKey.of(broken, 100, 50, style)
        assertEquals(16, key.hash.length)
    }
}
