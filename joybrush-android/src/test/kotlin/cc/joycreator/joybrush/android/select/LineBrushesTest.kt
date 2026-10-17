package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.core.brush.BLEND_MULTIPLY
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.TipSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which library brushes a line may be re-brushed to: the ones the engine can replay. */
class LineBrushesTest {

    @Test fun anInkPenAndAGlazeCanBeALine() {
        assertTrue(LineBrushes.canBeLine(testBrush("ink")))
        assertTrue(LineBrushes.canBeLine(testBrush("glaze").copy(blend = BLEND_MULTIPLY)))
        assertNull(LineBrushes.refusal(testBrush("ink")))
    }

    @Test fun anEraserIsNotAPen() {
        val eraser = testBrush("eraser").copy(blend = "erase")
        assertFalse(LineBrushes.canBeLine(eraser))
        assertNotNull(LineBrushes.refusal(eraser))
    }

    @Test fun brushesThatReadThePixelsUnderThePenAreNotLines() {
        assertFalse(LineBrushes.canBeLine(testBrush("smudge", engine = "smudge")))
        assertFalse(LineBrushes.canBeLine(testBrush("wet", engine = "wet")))
        assertFalse(LineBrushes.canBeLine(testBrush("fill", engine = ENGINE_FILL)))
    }

    @Test fun otherEnginesAreNotLinesYet() {
        assertFalse(LineBrushes.canBeLine(testBrush("sable", engine = "tuft")))
        assertFalse(LineBrushes.canBeLine(testBrush("oil", engine = "media")))
    }

    @Test fun aPictureTipIsNotALineYet() {
        val picture = testBrush("chalk").copy(tip = TipSpec(source = "image", image = "chalk.png"))
        assertFalse(LineBrushes.canBeLine(picture))
    }

    @Test fun anUnknownBlendIsNotALine() {
        assertFalse(LineBrushes.canBeLine(testBrush("odd").copy(blend = "dodge")))
    }

    @Test fun thePickerKeepsLibraryOrderAndDropsWhatCannotBeALine() {
        val library = listOf(
            testBrush("ink"),
            testBrush("smudge", engine = "smudge"),
            testBrush("eraser").copy(blend = "erase"),
            testBrush("marker"),
        )
        assertEquals(listOf("ink", "marker"), LineBrushes.forPicker(library).map { it.id })
    }
}
