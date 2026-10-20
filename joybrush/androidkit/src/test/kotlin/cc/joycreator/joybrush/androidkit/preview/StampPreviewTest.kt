package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.ResponseSpec
import cc.joycreator.joybrush.core.brush.SmudgeSpec
import cc.joycreator.joybrush.core.brush.ColorDynamicsSpec
import cc.joycreator.joybrush.core.brush.ColorJitter
import cc.joycreator.joybrush.core.brush.StrokeColors
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.StrokeBlend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stamp stroke worked out for the engine, off the GPU: repeatable, and carrying what the canvas would hand it. */
class StampPreviewTest {

    private fun brush(blend: String = "normal", accumulate: String = "wash", engine: String = "stamp", opacity: Float = 0.6f, size: Float = 18f) =
        BrushPreset(id = "t", name = "T", engine = engine, blend = blend, accumulate = accumulate, size = Param(size), opacity = Param(opacity))

    private fun build(p: BrushPreset, w: Int = 800, h: Int = 100): StampPreview.Prepared {
        val shown = PreviewPlan.shown(p, h)
        return StampPreview.build(shown, CannedStroke.pen(w, h, shown.size.base))
    }

    @Test fun theSameBrushAndBoxGiveTheSameDabsEveryTime() {
        for (p in BrushLibrary.builtIn().filter { PreviewPlan.kindOf(it) == PreviewKind.STAMP }) {
            val a = build(p)
            val b = build(p)
            assertEquals(a.dabs, b.dabs, p.id)
            assertEquals(a.strokeOpacity, b.strokeOpacity, p.id)
            assertEquals(a.tip, b.tip, p.id)
        }
    }

    @Test fun colourPreviewUsesItsPaletteAndTheSecondaryChangesItsCacheKey() {
        val p = brush().copy(color = ColorJitter(dynamics = ColorDynamicsSpec(secondaryMix = 1f)))
        val samples = CannedStroke.pen(800, 100, p.size.base)
        val warm = StampPreview.build(p, samples, StrokeColors(0xFFFF0000.toInt(), 0xFFFFFF00.toInt()))
        val cool = StampPreview.build(p, samples, StrokeColors(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        assertTrue(warm.dabs.map { it.color } != cool.dabs.map { it.color })
        assertEquals(warm.dabs.map { it.copy(color = null) }, cool.dabs.map { it.copy(color = null) })
        val style = BrushPreviewStyle()
        assertTrue(style.key != style.copy(secondaryInk = 0xFF0000FF.toInt()).key)
    }

    @Test fun everyShippedStampBrushLaysDabsInsideThePictureOrJustBeyondItsEdge() {
        for (p in BrushLibrary.builtIn().filter { PreviewPlan.kindOf(it) in setOf(PreviewKind.STAMP, PreviewKind.SMUDGE) }) {
            val built = build(p, 800, 100)
            assertTrue(built.dabs.isNotEmpty(), "${p.id} lays dabs")
            val shown = PreviewPlan.shown(p, 100)
            val room = shown.size.base * (2f + 2f * p.scatter.amount.base) + 8f   // scatter reaches up to 1.42 x amount x diameter off the path
            assertTrue(built.dabs.all { it.x > -room && it.x < 800 + room && it.y > -room && it.y < 100 + room }, "${p.id} stays near its box")
        }
    }

    @Test fun aWashStrokeCarriesItsOpacityOnEveryDabAndCommitsAtFull() {
        val built = build(brush(accumulate = "wash", opacity = 0.6f))
        assertEquals(Accumulate.WASH, built.accumulate)
        assertEquals(1f, built.strokeOpacity)
        assertTrue(built.dabs.all { it.cap == 0.6f }, "the dabs carry the ceiling")
    }

    @Test fun aBuildUpStrokeCommitsAtTheBrushOpacityWithNoCeilingOnTheDabs() {
        val built = build(brush(accumulate = "buildup", opacity = 0.6f))
        assertEquals(Accumulate.BUILD_UP, built.accumulate)
        assertEquals(0.6f, built.strokeOpacity)
        assertTrue(built.dabs.all { it.cap == 1f })
    }

    @Test fun theBlendFollowsTheBrushAndAnEraserErases() {
        assertEquals(StrokeBlend.NORMAL, build(brush()).blend)
        assertEquals(StrokeBlend.ERASE, build(brush(blend = "erase")).blend)
        assertEquals(StrokeBlend.MULTIPLY, build(brush(blend = "multiply")).blend)
        assertEquals(StrokeBlend.SCREEN, build(brush(blend = "screen")).blend)
    }

    @Test fun onlyASmudgeBrushCarriesSmudgeNumbersAndTheyAreTheBrushsOwn() {
        assertNull(build(brush()).smudge)
        val spec = SmudgeSpec(pickup = 0.7f, load = 0.2f, texturePickup = 0.3f, paint = true)
        val s = assertNotNull(build(brush(engine = ENGINE_SMUDGE).copy(smudge = spec)).smudge)
        assertEquals(0.7f, s.pickup)
        assertEquals(0.2f, s.load)
        assertEquals(0.3f, s.texturePickup)
        assertTrue(s.paint)
    }

    @Test fun theBrushsOwnPressureCurveIsAppliedBeforeTheDabber() {
        val plain = brush()
        // A curve that stays low for most of the pen's range: the same stroke is felt lighter.
        val late = plain.copy(response = ResponseSpec(pressure = listOf(0.9f, 0f, 1f, 0.1f)))
        val straight = build(plain).dabs
        val curved = build(late).dabs
        assertTrue(curved.first().pressure < straight.first().pressure, "${curved.first().pressure} < ${straight.first().pressure}")
        assertTrue(curved.map { it.pressure } != straight.map { it.pressure })
    }
}
