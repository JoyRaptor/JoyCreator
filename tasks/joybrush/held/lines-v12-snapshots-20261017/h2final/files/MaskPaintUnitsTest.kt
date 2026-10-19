package cc.joycreator.joybrush.core.fill

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.select.SelectionMask
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Editable lines v1.2 H2: the fill pen's own pixels (coverage x colour x opacity, premultiplied), kept as a slab on a plane
 * that holds lines. [MaskPaint.units] must be exactly what a FILL leaves on an EMPTY layer, so the slab and the plain
 * fill never disagree about a pixel.
 */
class MaskPaintUnitsTest {
    private val red = 0xFFFF0000.toInt()

    @Test fun theUnitsAreExactlyWhatAFillLeavesOnAnEmptyLayer() {
        val mask = SelectionMask.rect(RectPx(250, 20, 12, 40))   // over a tile seam
        val units = MaskPaint.units(mask, 0xFF3366CC.toInt(), 0.6f)
        val painted = MaskPaint.apply(emptyMap(), mask, 0xFF3366CC.toInt(), 0.6f, MaskPaintMode.FILL)
        assertEquals(painted.keys, units.keys)
        for ((key, bytes) in units) assertContentEquals(assertNotNull(painted[key]), bytes, "tile $key")
    }

    @Test fun anOpaqueRedPixelIsOpaqueRedAndAHalfOpacityOneIsHalfPremultiplied() {
        val mask = SelectionMask.rect(RectPx(5, 5, 4, 4))
        val full = assertNotNull(MaskPaint.units(mask, red, 1f)[Tiles.key(0, 0)])
        val at = (5 * Tiles.SIZE + 5) * 4
        assertEquals(listOf(255, 0, 0, 255), (0..3).map { full[at + it].toInt() and 0xFF })
        val half = assertNotNull(MaskPaint.units(mask, red, 0.5f)[Tiles.key(0, 0)])
        // 255 * 0.5 = 127.5 rounds half up to 128, and the premultiplied red is the same 128.
        assertEquals(listOf(128, 0, 0, 128), (0..3).map { half[at + it].toInt() and 0xFF })
        assertEquals(0, half[0].toInt(), "outside the mask the unit is empty")
    }

    @Test fun anEmptyMaskAndAnOpacityOfZeroGiveNothingAndANanOpacityIsOne() {
        assertTrue(MaskPaint.units(SelectionMask.EMPTY, red, 1f).isEmpty())
        assertTrue(MaskPaint.units(SelectionMask.rect(RectPx(0, 0, 4, 4)), red, 0f).isEmpty())
        val nan = MaskPaint.units(SelectionMask.rect(RectPx(0, 0, 4, 4)), red, Float.NaN)
        assertEquals(255, nan.getValue(Tiles.key(0, 0))[3].toInt() and 0xFF)
    }

    @Test fun theColourAlphaIsIgnoredAsItIsForTheFill() {
        val mask = SelectionMask.rect(RectPx(0, 0, 4, 4))
        val a = MaskPaint.units(mask, 0x00FF0000, 1f).getValue(Tiles.key(0, 0))
        val b = MaskPaint.units(mask, 0xFFFF0000.toInt(), 1f).getValue(Tiles.key(0, 0))
        assertContentEquals(a, b)
    }
}
