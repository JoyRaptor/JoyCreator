package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.LineStyleSpec
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import kotlin.test.*

/** Editable lines v1.2 H2: what becomes a line, what becomes a pixel slab, and what stays refused in words. */
class LineAdmissionTest {
    private fun pen(engine: String = "stamp", blend: String = "normal", tip: TipSpec = TipSpec()) =
        BrushPreset(id = "pen", name = "Pen", engine = engine, size = Param(8f), blend = blend, tip = tip)

    private fun refusal(p: BrushPreset?, erase: Boolean = false, tiled: Boolean = false, realistic: Boolean = false,
                        smoothing: Float = 0.3f, zoom: Float = 1f) =
        LineAdmission.lineRefusal(p, erase, smoothing, zoom, tiled, realistic)

    @Test fun anOrdinaryProceduralStampBrushCanBeALine() {
        assertNull(refusal(pen()))
        assertNull(refusal(pen(blend = "erase")))
        assertNull(refusal(pen(blend = "multiply")))
    }

    @Test fun aTaperedOrDashedBrushIsALineAndOnlyAScreenWidthWireIsNot() {
        // Unibrush U6: the stroke is shaped at the lift and its line replays shaped.
        assertNull(refusal(pen().copy(line = LineStyleSpec(taperIn = 1f, taperOut = 3f))))
        assertNull(refusal(pen().copy(line = LineStyleSpec(dashOn = 2f, dashOff = 1f))))
        assertEquals("screen-width wire brushes can't be kept as lines yet", refusal(pen().copy(line = LineStyleSpec(screenWidthPx = 1.5f))))
    }

    @Test fun aPaintBehindStrokeStaysPixelsBecauseItIsPaintedAsPlainPaintLive() {
        assertEquals("paint-behind brushes can't be kept as lines yet", refusal(pen(blend = "behind")))
    }

    @Test fun theBrushesThatCannotBeLinesSayWhyInWords() {
        assertEquals("the built-in round brush can't be kept as a line", refusal(null))
        assertEquals("smudge doesn't work on lines yet", refusal(pen(ENGINE_SMUDGE)))
        assertEquals("the fill pen doesn't work on lines yet", refusal(pen(ENGINE_FILL)))
        assertTrue(refusal(pen(ENGINE_MEDIA))!!.contains("realistic"))
        assertTrue(refusal(pen(tip = TipSpec(source = "image", image = "tip.png")))!!.contains("picture-tip"))
        assertTrue(refusal(pen(blend = "overlay"))!!.contains("overlay"))
        assertEquals("lines don't work on a tiled board yet", refusal(pen(), tiled = true))
        assertEquals("it already holds realistic paint", refusal(pen(), realistic = true))
        assertEquals("the smoothing setting is out of range", refusal(pen(), smoothing = Float.NaN))
        assertEquals("the smoothing setting is out of range", refusal(pen(), zoom = 0f))
    }

    @Test fun aLayerThatHoldsItemsStillPaintsEveryStrokeThatIsNotRealisticMedia() {
        // Smudge, fill, picture tips, the eraser and the built-in brush are slabs there now, never refused.
        for (p in listOf(null, pen(ENGINE_SMUDGE), pen(ENGINE_FILL), pen(tip = TipSpec(source = "image", image = "t.png")), pen(blend = "erase")))
            assertNull(LineAdmission.slabRefusal(p, tiledBoard = false), "stroke with ${p?.engine} blend ${p?.blend}")
    }

    @Test fun realisticMediaAndATiledBoardStayRefusedOnALayerThatHoldsItems() {
        assertTrue(LineAdmission.slabRefusal(pen(ENGINE_MEDIA), tiledBoard = false)!!.contains("realistic"))
        assertEquals("lines don't work on a tiled board yet", LineAdmission.slabRefusal(pen(), tiledBoard = true))
    }

    @Test fun theSentenceNamesTheLayerAndSaysWhatToDo() {
        assertEquals("\"Sketch\" holds editable lines, and smudge doesn't work on lines yet. Use another layer for it.",
            LineAdmission.refusalSentence("Sketch", "smudge doesn't work on lines yet"))
    }

    @Test fun strokesOverLinesAreRefusedInWordsOnlyOnceTheSlabBudgetIsFull() {
        assertNull(LineAdmission.slabBudgetRefusal(0))
        assertNull(LineAdmission.slabBudgetRefusal(LineAdmission.SLAB_TILE_BUDGET - 1))
        assertNotNull(LineAdmission.slabBudgetRefusal(LineAdmission.SLAB_TILE_BUDGET))
    }
}
