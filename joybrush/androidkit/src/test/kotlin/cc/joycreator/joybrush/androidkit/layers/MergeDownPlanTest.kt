package cc.joycreator.joybrush.androidkit.layers

import cc.joycreator.joybrush.androidkit.layers.MergeDownPlan.Result
import cc.joycreator.joybrush.androidkit.layers.MergeDownPlan.Side
import cc.joycreator.joybrush.androidkit.layers.MergeDownPlan.Way
import cc.joycreator.joybrush.core.doc.BlendMode
import kotlin.test.*

/** LAYER_OPS L2/L3: what Merge Down does with each pair of layers, or why it can't. */
class MergeDownPlanTest {
    private val u = Side("Ink")
    private val l = Side("Colour")

    private fun refused(r: Result) = assertIs<Result.Refused>(r).sentence
    private fun merge(r: Result) = assertIs<Result.Merge>(r)

    @Test fun plainPixelLayersMergeAsPixels() {
        assertEquals(Result.Merge(Way.PIXELS), MergeDownPlan.decide(u, l))
    }

    @Test fun aPlainUpperLayerMovesItsLinesAcrossAndBothStayEditable() {
        assertEquals(Result.Merge(Way.MOVE_ITEMS), MergeDownPlan.decide(u.copy(hasItems = true), l.copy(hasItems = true)))
        assertEquals(Result.Merge(Way.MOVE_ITEMS), MergeDownPlan.decide(u.copy(hasItems = true), l), "lines onto a pixel layer")
        assertEquals(Result.Merge(Way.MOVE_ITEMS), MergeDownPlan.decide(u, l.copy(hasItems = true)), "pixels onto a line layer")
    }

    @Test fun anUpperLayerWithASettingIsPressedIntoOneSheetAndTheLowerKeepsItsLines() {
        val r = merge(MergeDownPlan.decide(u.copy(opacity = 0.5f, hasItems = true), l.copy(hasItems = true)))
        assertEquals(Way.PRESS, r.way); assertEquals("normal", r.sheetOp); assertFalse(r.flattenLower)
        assertEquals("Lines on \"Ink\" became paint to keep its opacity; \"Colour\" keeps its lines.", r.said)
        val multiply = merge(MergeDownPlan.decide(u.copy(blend = BlendMode.MULTIPLY), l.copy(hasItems = true)))
        assertEquals(Way.PRESS, multiply.way); assertEquals("multiply", multiply.sheetOp); assertNull(multiply.said, "no lines of U to lose")
        assertEquals(Way.PRESS, merge(MergeDownPlan.decide(u.copy(hasItems = true, itemsAllNormal = false), l)).way,
            "an erasing line only makes sense against its own layer")
        assertEquals(Way.PRESS, merge(MergeDownPlan.decide(u.copy(clip = true), l.copy(hasItems = true))).way)
    }

    @Test fun aBlendNoSheetCanKeepMakesBothLayersPaint() {
        val r = merge(MergeDownPlan.decide(u.copy(blend = BlendMode.OVERLAY, hasItems = true), l.copy(hasItems = true)))
        assertEquals(Way.PIXELS, r.way); assertTrue(r.flattenLower)
        assertEquals("Lines on \"Ink\" became paint and lines on \"Colour\" became paint to keep the overlay blend.", r.said)
    }

    @Test fun onAnAnimationBoardOnlyTheFrameMergesWhenBothLayersAnimateThere() {
        assertEquals(Result.Merge(Way.PIXELS), MergeDownPlan.decide(u.copy(hasFrames = true, animatesHere = true), l.copy(hasFrames = true, animatesHere = true)))
        assertTrue(refused(MergeDownPlan.decide(u.copy(animatesHere = false), l.copy(animatesHere = true))).contains("doesn't animate"))
        assertTrue(refused(MergeDownPlan.decide(u.copy(animatesHere = true), l.copy(animatesHere = false))).contains("still layer"))
    }

    @Test fun refusalsAreSentences() {
        assertTrue(refused(MergeDownPlan.decide(u, null)).contains("bottom layer"))
        assertTrue(refused(MergeDownPlan.decide(u.copy(locked = true), l)).contains("locked"))
        assertTrue(refused(MergeDownPlan.decide(u, l.copy(locked = true))).contains("locked"))
        assertTrue(refused(MergeDownPlan.decide(u.copy(visible = false), l)).contains("hidden"))
        assertTrue(refused(MergeDownPlan.decide(u.copy(hasFrames = true), l)).contains("animation frames"))
        assertIs<Result.Merge>(MergeDownPlan.decide(u.copy(hasMedia = true), l.copy(hasMedia = true)), "realistic paint merges (§2.3)")
        assertTrue(refused(MergeDownPlan.decide(u, l.copy(blend = BlendMode.SCREEN))).contains("Normal"))
        assertTrue(refused(MergeDownPlan.decide(u, l.copy(opacity = 0.4f))).contains("40%"))
        assertTrue(refused(MergeDownPlan.decide(u, l.copy(hasMask = true))).contains("mask"))
        assertTrue(refused(MergeDownPlan.decide(u, l.copy(clip = true))).contains("clipped"))
    }
}
