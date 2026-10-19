package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.ENGINE_PUSH
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.brush.MediaPresets
import cc.joycreator.joybrush.core.brush.MediaSpec
import cc.joycreator.joybrush.core.brush.Param
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Which engine path each kind of brush takes, and the size it is shown at. */
class PreviewPlanTest {

    private fun brush(engine: String = "stamp", blend: String = "normal", size: Float = 20f, media: MediaSpec? = null) =
        BrushPreset(id = "t", name = "T", engine = engine, blend = blend, size = Param(size), media = media)

    @Test fun everyEngineTakesTheCanvasOwnPath() {
        assertEquals(PreviewKind.STAMP, PreviewPlan.kindOf(brush()))
        assertEquals(PreviewKind.STAMP, PreviewPlan.kindOf(brush(engine = ENGINE_PUSH)), "the canvas draws push with the stamp path")
        assertEquals(PreviewKind.STAMP, PreviewPlan.kindOf(brush(engine = "wet")))
        assertEquals(PreviewKind.TUFT, PreviewPlan.kindOf(brush(engine = ENGINE_TUFT)))
        assertEquals(PreviewKind.SMUDGE, PreviewPlan.kindOf(brush(engine = ENGINE_SMUDGE)))
        assertEquals(PreviewKind.FILL, PreviewPlan.kindOf(brush(engine = ENGINE_FILL)))
        assertEquals(PreviewKind.WATERCOLOUR, PreviewPlan.kindOf(brush(engine = ENGINE_WATERCOLOUR)))
    }

    @Test fun anErasingWatercolourLineIsDrawnByTheStampPathLikeOnTheCanvas() {
        assertEquals(PreviewKind.STAMP, PreviewPlan.kindOf(brush(engine = ENGINE_WATERCOLOUR, blend = "erase")))
    }

    @Test fun theEraserFlagIsOrthogonalToTheEngine() {
        assertTrue(PreviewPlan.erases(brush(blend = "erase")))
        assertTrue(PreviewPlan.erases(brush(engine = ENGINE_TUFT, blend = "erase")))
        assertEquals(PreviewKind.TUFT, PreviewPlan.kindOf(brush(engine = ENGINE_TUFT, blend = "erase")))
        assertTrue(!PreviewPlan.erases(brush(blend = "multiply")))
    }

    @Test fun aMediaBrushIsToldApartByItsMedium() {
        val all = MediaPresets.ALL
        assertTrue(all.isNotEmpty())
        for (p in all) {
            val expected = when (p.media!!.medium) {
                MEDIUM_DRY -> PreviewKind.MEDIA_DRY
                MEDIUM_WET -> PreviewKind.MEDIA_WET
                MEDIUM_PASTE -> PreviewKind.MEDIA_PASTE
                else -> error("unknown medium")
            }
            assertEquals(expected, PreviewPlan.kindOf(p), p.id)
        }
        assertTrue(all.any { PreviewPlan.kindOf(it) == PreviewKind.MEDIA_DRY })
        assertTrue(all.any { PreviewPlan.kindOf(it) == PreviewKind.MEDIA_WET })
        assertTrue(all.any { PreviewPlan.kindOf(it) == PreviewKind.MEDIA_PASTE })
    }

    @Test fun aMediaBrushWithoutItsMediaSectionHasNothingToDraw() {
        assertEquals(PreviewKind.NONE, PreviewPlan.kindOf(brush(engine = ENGINE_MEDIA)))
    }

    @Test fun everyShippedBrushHasSomethingToDraw() {
        val shipped = BrushLibrary.builtIn()
        assertTrue(shipped.size > 10, "the shipped library loads in a unit test (${shipped.size})")
        for (p in shipped) assertNotEquals(PreviewKind.NONE, PreviewPlan.kindOf(p), p.id)
        val kinds = shipped.map { PreviewPlan.kindOf(it) }.toSet()
        assertTrue(kinds.containsAll(setOf(PreviewKind.STAMP, PreviewKind.TUFT, PreviewKind.SMUDGE, PreviewKind.FILL,
            PreviewKind.WATERCOLOUR, PreviewKind.MEDIA_DRY, PreviewKind.MEDIA_WET, PreviewKind.MEDIA_PASTE)), "kinds seen: $kinds")
    }

    @Test fun aBigBrushIsShownNoBiggerThanTheRowAllowsAndASmallOneAtItsOwnSize() {
        assertEquals(110f * PreviewPlan.MAX_SHARE, PreviewPlan.shown(brush(size = 300f), 110).size.base, 0.001f)
        assertEquals(3f, PreviewPlan.shown(brush(size = 3f), 110).size.base, 0.001f)
    }

    @Test fun showingABrushChangesOnlyItsBaseSize() {
        val p = brush(size = 300f)
        val shown = PreviewPlan.shown(p, 100)
        assertEquals(p.copy(size = p.size.copy(base = shown.size.base)), shown)
    }
}
