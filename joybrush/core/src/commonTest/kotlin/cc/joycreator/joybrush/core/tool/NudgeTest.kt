package cc.joycreator.joybrush.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals

/** JB-2.16a, spec Test 8: the owner's "nudge scales with zoom". */
class NudgeTest {

    private val eps = 1e-6f

    @Test
    fun oneNudgeIsOneScreenPixel() {
        assertEquals(1f, Nudge.stepDoc(1f), eps)
        assertEquals(0.25f, Nudge.stepDoc(4f), eps)   // zoomed in, a nudge is a quarter of a doc px
        assertEquals(2f, Nudge.stepDoc(0.5f), eps)    // zoomed out, two doc px
    }

    @Test
    fun theBigNudgeIsTenScreenPixelsOnTheSameScale() {
        assertEquals(5f, Nudge.stepDoc(2f, big = true), eps)
        assertEquals(2.5f, Nudge.stepDoc(4f, big = true), eps)
        assertEquals(10f, Nudge.stepDoc(1f, big = true), eps)
    }

    @Test
    fun aZoomThatIsNotAUsableNumberIsReadAsOne() {
        assertEquals(1f, Nudge.stepDoc(0f), eps)
        assertEquals(1f, Nudge.stepDoc(-4f), eps)
        assertEquals(1f, Nudge.stepDoc(Float.NaN), eps)
        assertEquals(1f, Nudge.stepDoc(Float.POSITIVE_INFINITY), eps)
        assertEquals(10f, Nudge.stepDoc(0f, big = true), eps)
    }

    @Test
    fun theStepIsTheDocumentDistanceForThatManyScreenPixels() {
        // At zoom 2 one screen px is half a document px, so the small nudge travels 0.5 doc px and
        // two nudges travel exactly one — the whole rule in one line.
        assertEquals(0.5f, Nudge.stepDoc(2f), eps)
        assertEquals(1f, 2f * Nudge.stepDoc(2f), eps)
    }
}
