package cc.joycreator.joybrush.core.brush.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JBX-18: a picked file goes to the right importer by its name, or by its first bytes when the name says nothing. */
class BrushFileKindTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun theNameDecidesFirstWhateverItsCase() {
        assertEquals(listOf(BrushFileKind.ABR), BrushFileKind.candidates("Kyle's Inks.ABR", bytes(0x50, 0x4B)))
        assertEquals(listOf(BrushFileKind.PROCREATE_SET), BrushFileKind.candidates("set.brushset", bytes()))
        assertEquals(listOf(BrushFileKind.PROCREATE_BRUSH), BrushFileKind.candidates("one.brush", bytes()))
        assertEquals(listOf(BrushFileKind.KRITA_PRESET), BrushFileKind.candidates("a.kpp", bytes()))
        assertEquals(listOf(BrushFileKind.KRITA_BUNDLE), BrushFileKind.candidates("pack.bundle", bytes()))
        assertEquals(listOf(BrushFileKind.MYPAINT), BrushFileKind.candidates("charcoal.myb", bytes()))
    }

    @Test fun withoutATellingNameTheFirstBytesDecide() {
        assertEquals(listOf(BrushFileKind.KRITA_PRESET), BrushFileKind.candidates("download", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals(listOf(BrushFileKind.PROCREATE_BRUSH, BrushFileKind.PROCREATE_SET, BrushFileKind.KRITA_BUNDLE),
            BrushFileKind.candidates(null, bytes(0x50, 0x4B, 3, 4)))
        assertEquals(listOf(BrushFileKind.MYPAINT), BrushFileKind.candidates("x.txt", bytes(0xEF, 0xBB, 0xBF, 0x0A, 0x20, '{'.code)))
        assertEquals(listOf(BrushFileKind.ABR), BrushFileKind.candidates("x", bytes(0, 6, 0, 2)))
        assertEquals(listOf(BrushFileKind.ABR), BrushFileKind.candidates("x", bytes(0, 1)))
    }

    @Test fun anythingElseIsNotABrushFile() {
        assertTrue(BrushFileKind.candidates("photo.jpg", bytes(0xFF, 0xD8, 0xFF)).isEmpty())
        assertTrue(BrushFileKind.candidates("empty", bytes()).isEmpty())
        assertTrue(BrushFileKind.candidates("x", bytes(0, 0x20)).isEmpty())
    }
}
