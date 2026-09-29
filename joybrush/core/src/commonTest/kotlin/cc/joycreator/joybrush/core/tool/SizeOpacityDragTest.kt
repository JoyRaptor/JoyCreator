package cc.joycreator.joybrush.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JB-2.16a, spec Tests 1–8, in that order, plus the guards the spec's Decision 5 asks for. Every
 * expected value carries its derivation (LEAD_RULINGS R9) so a number here is never just "whatever
 * the code does".
 */
class SizeOpacityDragTest {

    /** The tolerance the spec names for the no-jump test; comfortably above Float noise here. */
    private val eps = 1e-4f

    /** A drag that has not moved yet: 10 px brush, half opacity, zoom 1, no density scaling. */
    private fun drag(
        startSize: Float = 10f,
        startOpacity: Float = 0.5f,
        screenPerDoc: Float = 1f,
        density: Float = 1f,
    ): SizeOpacityDrag = SizeOpacityDrag(startSize, startOpacity, screenPerDoc, density)

    // ---- 1 ----------------------------------------------------------------------------------------

    @Test
    fun nothingMovesBeforeTwelvePixelsAndThirteenAcrossIsSize() {
        val d = drag()
        d.move(11f, 0f)
        assertEquals(SizeOpacityDrag.Axis.NONE, d.axis)
        assertEquals(10f, d.size, eps)
        assertEquals(0.5f, d.opacity, eps)

        // 11 px down is still short of the 12 dp lock as well — the travel is a distance, not a
        // per-axis test.
        d.move(0f, 11f)
        assertEquals(SizeOpacityDrag.Axis.NONE, d.axis)
        assertEquals(10f, d.size, eps)
        assertEquals(0.5f, d.opacity, eps)

        d.move(13f, 0f)
        assertEquals(SizeOpacityDrag.Axis.SIZE, d.axis)
    }

    // ---- 2 ----------------------------------------------------------------------------------------

    @Test
    fun sizeIsExponentialAndClampedAtBothEnds() {
        val d = drag()
        d.move(13f, 0f)          // locks SIZE; the lock offset is 13 px, and never changes after this
        assertEquals(SizeOpacityDrag.Axis.SIZE, d.axis)

        d.move(13f + 160f, 0f)   // 160 px beyond the lock: 10 * 2^(160/160) = 20
        assertEquals(20f, d.size, eps)

        d.move(13f - 160f, 0f)   // 160 px back the other way: 10 * 2^(-160/160) = 5
        assertEquals(5f, d.size, eps)

        d.move(-10000f, 0f)      // 10 * 2^(-62.6) is a speck: the floor holds it at 0.5
        assertEquals(0.5f, d.size, eps)

        d.move(10000f, 0f)       // 10 * 2^(62.4) is enormous: the ceiling holds it at 4096
        assertEquals(4096f, d.size, eps)
    }

    // ---- 3 ----------------------------------------------------------------------------------------

    @Test
    fun theValueDoesNotJumpWhenTheAxisLocks() {
        val d = drag(startSize = 37f)
        d.move(13f, 0f)
        assertEquals(SizeOpacityDrag.Axis.SIZE, d.axis)
        // 13 px of travel, lock offset 13 px, so 37 * 2^0 = 37: the start value, unchanged.
        assertEquals(37f, d.size, eps)
        assertEquals(0.5f, d.opacity, eps)
    }

    @Test
    fun anEvenDragLocksToSizeAndDoesNotJump() {
        val d = drag(startSize = 37f)
        // The straight-line travel from the start is sqrt(9*9 + 9*9) = 12.73 dp, past the 12 dp lock,
        // and |dx| == |dy| is a tie, which the spec gives to SIZE.
        d.move(9f, 9f)
        assertEquals(SizeOpacityDrag.Axis.SIZE, d.axis)
        assertEquals(37f, d.size, eps)
    }

    // ---- 4 ----------------------------------------------------------------------------------------

    @Test
    fun opacityIsLinearAndUpIsMore() {
        val d = drag()
        d.move(0f, -13f)             // travel 13 dp, |dy| > |dx|, so OPACITY; lock offset dy = -13
        assertEquals(SizeOpacityDrag.Axis.OPACITY, d.axis)

        d.move(0f, -13f - 150f)      // 150 px further up: 0.5 - (-150/300) = 1.0
        assertEquals(1.0f, d.opacity, eps)
        assertEquals(10f, d.size, eps)  // the size axis never moved

        d.move(0f, -13f + 300f)      // 300 px back down: 0.5 - (300/300) = -0.5, so the floor holds
        assertEquals(0.01f, d.opacity, eps)
        assertEquals(10f, d.size, eps)

        // And up again it comes back off the floor rather than sticking there.
        d.move(0f, -13f - 150f)
        assertEquals(1.0f, d.opacity, eps)
    }

    // ---- 5 ----------------------------------------------------------------------------------------

    @Test
    fun onceLockedToSizeAVerticalMoveChangesNothing() {
        val d = drag()
        d.move(13f, 0f)
        d.move(13f, 5000f)
        assertEquals(SizeOpacityDrag.Axis.SIZE, d.axis)
        assertEquals(10f, d.size, eps)
        assertEquals(0.5f, d.opacity, eps)

        // Dragging all the way back to where the finger went down shrinks the size again and does
        // NOT hand the gesture back to the vertical axis. 13 px back is 10 * 2^(-13/160) = 9.45, so
        // the size is checked as a bound rather than as a hand-computed float.
        d.move(0f, 0f)
        assertEquals(SizeOpacityDrag.Axis.SIZE, d.axis)
        assertTrue(d.size > 5f)
        assertTrue(d.size < 10f)
        assertEquals(0.5f, d.opacity, eps)
    }

    // ---- 6 ----------------------------------------------------------------------------------------

    @Test
    fun densityScalesBothThresholds() {
        val d = drag(density = 2f)

        d.move(23f, 0f)              // the lock is 12 dp, and this screen is 2 dp to the px
        assertEquals(SizeOpacityDrag.Axis.NONE, d.axis)

        d.move(24f, 0f)              // exactly 12 dp
        assertEquals(SizeOpacityDrag.Axis.SIZE, d.axis)
        assertEquals(10f, d.size, eps)

        d.move(24f + 320f, 0f)       // 320 px = 160 dp: 10 * 2^(320/320) = 20
        assertEquals(20f, d.size, eps)
    }

    // ---- 7 ----------------------------------------------------------------------------------------

    @Test
    fun thePreviewCircleIsDrawnAtItsTrueScreenSize() {
        // A 10 px brush at zoom 4 covers 40 screen px across, so the swatch circle is radius 20.
        val d = drag(startSize = 10f, screenPerDoc = 4f)
        assertEquals(10f, d.size, eps)
        assertEquals(20f, d.previewRadiusScreenPx, eps)

        // Half a brush is half a circle at any zoom.
        val small = drag(startSize = 3f, screenPerDoc = 0.5f)
        assertEquals(0.75f, small.previewRadiusScreenPx, eps)
    }

    // ---- Decision 5: the guards -------------------------------------------------------------------

    @Test
    fun aNonFiniteOffsetIsIgnoredAndTheLastGoodValuesStand() {
        val d = drag()
        d.move(13f, 0f)
        d.move(173f, 0f)                 // one doubling
        val sizeAtLastGood = d.size
        val opacityAtLastGood = d.opacity

        d.move(Float.NaN, 0f)
        d.move(Float.POSITIVE_INFINITY, 0f)
        d.move(Float.NEGATIVE_INFINITY, 0f)
        d.move(173f, Float.NaN)
        d.move(173f, Float.POSITIVE_INFINITY)

        assertEquals(sizeAtLastGood, d.size, eps)
        assertEquals(opacityAtLastGood, d.opacity, eps)
    }

    @Test
    fun aStartSizeOrZoomThatIsNotAUsableNumberIsReadAsOne() {
        val d = drag(startSize = Float.NaN, screenPerDoc = 0f, density = 0f)
        assertEquals(1f, d.size, eps)
        // A 1 px brush has a 0.5 px radius, and a zoom of 0 is read as 1, so the circle is 0.5 px.
        assertEquals(0.5f, d.previewRadiusScreenPx, eps)

        // The density is read as 1 too, so the lock is still 12 px rather than zero.
        d.move(11f, 0f)
        assertEquals(SizeOpacityDrag.Axis.NONE, d.axis)
        d.move(13f, 0f)
        assertEquals(SizeOpacityDrag.Axis.SIZE, d.axis)
    }

    @Test
    fun aNegativeStartSizeIsReadAsOne() {
        val d = drag(startSize = -20f)
        assertEquals(1f, d.size, eps)
    }

    @Test
    fun aStartOpacityOutsideTheRangeIsPulledIntoIt() {
        assertEquals(1.0f, drag(startOpacity = 5f).opacity, eps)
        assertEquals(0.01f, drag(startOpacity = 0f).opacity, eps)
        assertEquals(1.0f, drag(startOpacity = Float.NaN).opacity, eps)
    }
}
