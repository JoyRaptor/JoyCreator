package cc.joycreator.joybrush.androidkit.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JB-2.03a — the eyedropper's numbers, as tests and not folklore.
 *
 * The first four are [Eyedropper.seen], the ONE-layer colour rule (paint at the layer's opacity, over the paper). The shipped eyedropper
 * does not use it: it reads the composited screen. The rest are the cancel rule, the cancel circle's size in px, the pill's hit test and
 * where a finger samples.
 */
class EyedropperTest {

    private fun px(r: Int, g: Int, b: Int, a: Int) = byteArrayOf(r.toByte(), g.toByte(), b.toByte(), a.toByte())

    @Test
    fun anEmptySpotShowsThePaper() {
        assertEquals(0xFFF6F4EE.toInt(), Eyedropper.seen(null, 0, 1f, 0xFFF6F4EE.toInt()))
    }

    @Test
    fun opaquePaintIsItselfWhateverThePaper() {
        // Premultiplied opaque red over white paper, layer opacity 1: red.
        assertEquals(0xFFFF0000.toInt(), Eyedropper.seen(px(255, 0, 0, 255), 0, 1f, 0xFFFFFFFF.toInt()))
    }

    @Test
    fun halfPaintOverPaperIsTheMix() {
        // Premultiplied 50% blue (0,0,128,128) over white: r = 0 + 255*(1-128/255) = 127; b = 128 + 127 = 255.
        val c = Eyedropper.seen(px(0, 0, 128, 128), 0, 1f, 0xFFFFFFFF.toInt())
        assertEquals(127, (c shr 16) and 0xFF)
        assertEquals(255, c and 0xFF)
        assertEquals(0xFF, (c ushr 24) and 0xFF, "an eyedropper result is always opaque")
    }

    @Test
    fun theLayersOpacityScalesThePaint() {
        // Opaque black at layer opacity 0.5 over white: 0*0.5 + 255*(1-0.5) = 127.5 -> 128 (rounded half up).
        val c = Eyedropper.seen(px(0, 0, 0, 255), 0, 0.5f, 0xFFFFFFFF.toInt())
        assertEquals(128, (c shr 16) and 0xFF)
    }

    @Test
    fun theCancelCircleIsARealCircle() {
        assertTrue(Eyedropper.insideCircle(10f, 10f, 10f, 10f, 12f))
        assertTrue(Eyedropper.insideCircle(22f, 10f, 10f, 10f, 12f))
        assertFalse(Eyedropper.insideCircle(19f, 19f, 10f, 10f, 12f), "a corner of the bounding square is outside")
    }

    /**
     * R10, and the audit MINOR: [Eyedropper.CANCEL_CIRCLE_DP] is a DIAMETER, so the radius the hit test and the drawn circle use is half
     * of it, and that half is multiplied by the density at the use site.
     * 24 dp across = 12 dp radius; at density 1 that is 12 px, at density 3 (a 480 dpi screen) 12 × 3 = 36 px.
     */
    @Test
    fun theCancelCircleIsTwentyFourDpAcrossAtEveryDensity() {
        assertEquals(12f, Eyedropper.cancelRadiusPx(1f))
        assertEquals(36f, Eyedropper.cancelRadiusPx(3f))
        // It is the DIAMETER that is 24 dp, so the circle is 24 dp wide: at density 3 that is 24 × 3 = 72 px across, and
        // 2 × 36 px is that. If the constant ever stops being a diameter, this is the line that says so.
        assertEquals(72f, Eyedropper.cancelRadiusPx(3f) * 2f)
    }

    /**
     * The BLOCKER (JB-2.03a audit): a long-press and a lift, with no travel at all, must TAKE the colour.
     *
     * The hold begins at (400, 900). The ring appears with the cancel circle on that point, and the touch is on it. The circle is a way
     * BACK, so while the touch is still on it there is nothing to go back from: it is not armed, the ring's top half shows the new colour,
     * and `eyedropEnd` computes `took = take && !overCancel` = true. This is the spec's own check, "long-press again and lift → red".
     */
    @Test
    fun aHoldThatLiftsWhereItBeganTakesTheColour() {
        val r = Eyedropper.cancelRadiusPx(3f) // 36 px
        var armed = false
        // The frame the ring appears on: the touch is on the circle's own centre.
        armed = Eyedropper.armsCancel(400f, 900f, 400f, 900f, r, armed)
        assertFalse(armed, "a circle the touch has not left is not yet a way back")
        assertFalse(Eyedropper.overCancel(400f, 900f, 400f, 900f, r, armed), "so a lift here takes the colour")
    }

    /**
     * The other half of Decision 2, and the spec's check "slide back to the faint start circle and lift → unchanged".
     *
     * 36 px is the radius, and the boundary counts as inside, so 37 px out is the first position that arms the circle. 410 px is 10 px
     * out from the centre: inside, and now a cancel.
     */
    @Test
    fun slidingBackToTheStartCircleAfterLeavingItCancels() {
        val r = Eyedropper.cancelRadiusPx(3f) // 36 px
        var armed = false
        assertFalse(Eyedropper.armsCancel(436f, 900f, 400f, 900f, r, armed), "36 px is the boundary, and the boundary is inside")
        armed = Eyedropper.armsCancel(437f, 900f, 400f, 900f, r, armed)
        assertTrue(armed, "37 px is the first move outside the circle")
        assertFalse(Eyedropper.overCancel(437f, 900f, 400f, 900f, r, armed), "outside the circle there is no cancel")
        assertTrue(Eyedropper.overCancel(410f, 900f, 400f, 900f, r, armed), "back inside: a lift changes nothing")
        // Out again: taking, not cancelling. The circle stays armed — it is a place, not a one-shot.
        assertFalse(Eyedropper.overCancel(500f, 900f, 400f, 900f, r, armed))
        assertTrue(Eyedropper.armsCancel(500f, 900f, 400f, 900f, r, armed))
    }

    /**
     * The drag-off from the colour pill has no circle at all (the pill itself is the cancel), so nothing is armed and nothing cancels
     * here: overCancel is the long-press's rule only, and it must stay out of the drag-off's way.
     */
    @Test
    fun aDragOffHasNoCircleAndSoNoCancel() {
        var armed = false
        armed = Eyedropper.armsCancel(400f, 900f, 400f, 900f, 0f, armed)
        assertFalse(armed)
        assertFalse(Eyedropper.overCancel(400f, 900f, 400f, 900f, 0f, armed))
        // A radius of 0 is no circle at all, whatever any arm flag says: `insideCircle` with r = 0 is true only on the exact centre, and
        // the drag-off's cancel is the pill, decided by the screen.
        assertFalse(Eyedropper.armsCancel(500f, 900f, 400f, 900f, 0f, false), "there is nothing to arm")
        assertFalse(Eyedropper.overCancel(400f, 900f, 400f, 900f, 0f, true), "and nothing to be over")
    }

    /**
     * The drag-off's own lift test (JB-2.03a Decision 2, "drag back onto the pill and lift = cancel"). A 120 × 120 px swatch has columns
     * and rows 0 … 119, so 119.9 is the last pixel inside it and 120 — the view's own width — is one past the edge and must NOT count as
     * being on the pill, or a pick that should be taken is thrown away.
     */
    @Test
    fun thePillHitTestStopsOnePixelBeforeTheEdge() {
        assertTrue(Eyedropper.overPill(0f, 0f, 120, 120), "the top-left pixel is on the pill")
        assertTrue(Eyedropper.overPill(119.9f, 119.9f, 120, 120), "the last pixel is on the pill")
        assertFalse(Eyedropper.overPill(120f, 60f, 120, 120), "one pixel past the right edge is not")
        assertFalse(Eyedropper.overPill(60f, 120f, 120, 120), "one pixel past the bottom edge is not")
        assertFalse(Eyedropper.overPill(-0.5f, 60f, 120, 120), "half a pixel left of it is not")
    }

    /** [Eyedropper.seen] is the one-layer rule, not the shipped screen read; it must not read a neighbouring pixel's bytes. */
    @Test
    fun aPixelIndexOutsideTheBufferIsRefusedNotClamped() {
        val one = px(255, 0, 0, 255) // 4 bytes: exactly one pixel
        assertEquals(0xFFFF0000.toInt(), Eyedropper.seen(one, 0, 1f, 0xFFFFFFFF.toInt()))
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            Eyedropper.seen(one, 1, 1f, 0xFFFFFFFF.toInt())
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            Eyedropper.seen(one, -4, 1f, 0xFFFFFFFF.toInt())
        }
    }

    /** Owner, 2026-09-30: "needs a big version for fat fingers". A pen samples under its tip; a finger above it. */
    @Test
    fun aPenSamplesUnderItsTipAndAFingerSamplesAboveItsTip() {
        assertEquals(Pair(100f, 500f), Eyedropper.samplePoint(100f, 500f, finger = false, density = 2f))
        // 76 dp at density 2 is 152 px above.
        assertEquals(Pair(100f, 348f), Eyedropper.samplePoint(100f, 500f, finger = true, density = 2f))
    }

    @Test
    fun nearTheTopEdgeTheFingerRingGoesBelowSoItIsNeverCutOff() {
        // At y = 150, 152 px up would leave -2 px for a ring that needs 92 px (its half) of room: it goes below instead.
        assertEquals(Pair(100f, 302f), Eyedropper.samplePoint(100f, 150f, finger = true, density = 2f))
        // At y = 244 there is exactly room (244 - 152 = 92): it stays above.
        assertEquals(Pair(100f, 92f), Eyedropper.samplePoint(100f, 244f, finger = true, density = 2f))
    }

    @Test
    fun theFingerRingIsBigEnoughToSeeAroundAFingertip() {
        // A fingertip on a phone is about 10 mm (~ 60 dp). The ring must be wider than that, and lifted clear of it.
        kotlin.test.assertTrue(Eyedropper.FINGER_RING_DP >= 2 * Eyedropper.RING_DP)
        kotlin.test.assertTrue(Eyedropper.FINGER_LIFT_DP - Eyedropper.FINGER_RING_DP / 2f >= 25f, "the ring clears the fingertip")
    }
}
