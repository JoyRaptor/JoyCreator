package cc.joycreator.joybrush.core.dynamics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CurveTest {

    @Test
    fun identityPassesValuesThrough() {
        for (i in 0..20) {
            val x = i / 20f
            assertEquals(x, Curve.IDENTITY.eval(x), 1e-5f)
        }
    }

    @Test
    fun interpolatesBetweenPointsAndClampsOutside() {
        val c = Curve(listOf(0.2f to 0f, 0.6f to 1f))
        assertEquals(0f, c.eval(0f))
        assertEquals(0.5f, c.eval(0.4f), 0.01f)
        assertEquals(1f, c.eval(1f))
        assertEquals(1f, c.eval(7f))
        assertEquals(0f, c.eval(-3f))
    }

    @Test
    fun unsortedPointsAreSorted() {
        val c = Curve(listOf(1f to 1f, 0f to 0.5f))
        assertEquals(0.75f, c.eval(0.5f), 0.01f)
    }

    @Test
    fun nanInputIsTreatedAsZero() {
        assertEquals(0.25f, Curve(listOf(0f to 0.25f, 1f to 1f)).eval(Float.NaN))
    }

    // ── review JB-0.01 F2: a non-finite POINT poisons every input ─────────────────────────────────

    /**
     * The reviewer's case verbatim: `Curve(listOf(0f to Float.NaN))` used to build a table of NaN, so
     * `eval` returned NaN for EVERY input — and `Dynamics.eval` is unclamped, so a size like that
     * steps the dab loop by Infinity and the stroke stops after one dab (LEAD_RULINGS R1).
     *
     * POLICY: FAITHFUL, and for a curve that has to be faithful rather than loud. A loud refusal of
     * the whole curve would throw away the 63 good points of a 64-point curve over one bad float, and
     * `Curve` is built on the render thread during a stroke, where throwing is worse than drawing
     * something. The one point that is not a number is not a point of the curve the file describes, so
     * it is dropped and the rest of the curve is kept.
     */
    @Test
    fun aNonFinitePointIsDroppedAndTheRestOfTheCurveIsKept() {
        val c = Curve(listOf(0f to Float.NaN, 0.25f to 0.5f, 1f to 1f))
        // 0.01, not 0: eval reads the 256-entry table, which resamples a corner rather than
        // interpolating the exact curve (the same tolerance `interpolatesBetweenPointsAndClampsOutside`
        // needs). What is being asserted is that the value is a real number near the real curve.
        assertEquals(0.5f, c.eval(0.25f), 0.01f)
        assertEquals(1f, c.eval(1f), 1e-6f)
        for (x in 0..20) assertTrue(c.eval(x / 20f).isFinite(), "eval(${x / 20f}) is not finite")
        // and the dropped point is not in the curve at all
        assertEquals(listOf(0.25f to 0.5f, 1f to 1f), c.points)
    }

    /** An Infinity is dropped exactly like a NaN: `cos`/`exp` downstream cannot tell them apart. */
    @Test
    fun anInfinitePointIsDroppedToo() {
        val c = Curve(listOf(0f to 0f, 0.5f to Float.POSITIVE_INFINITY, 1f to 1f))
        assertEquals(listOf(0f to 0f, 1f to 1f), c.points)
        for (x in 0..20) assertTrue(c.eval(x / 20f).isFinite(), "eval(${x / 20f}) is not finite")
        // a non-finite X is dropped as well, for the same reason
        val cx = Curve(listOf(Float.NEGATIVE_INFINITY to 0.5f, 1f to 1f))
        assertEquals(listOf(1f to 1f), cx.points)
        assertTrue(cx.eval(0.5f).isFinite())
    }

    /**
     * With NOT ONE usable point there is nothing left to keep, and the two remaining answers are both
     * forbidden: a made-up value (this module never invents a reading) or a silent NaN back again. So
     * this is the one case that IS loud, and it names itself.
     */
    @Test
    fun aCurveWithNoFinitePointAtAllIsRefusedByName() {
        for (bad in listOf(
            listOf(0f to Float.NaN),
            listOf(0f to Float.NEGATIVE_INFINITY),
            listOf(Float.NaN to 0f, Float.NEGATIVE_INFINITY to 1f),
            listOf(Float.POSITIVE_INFINITY to 0f, Float.NaN to 1f),
        )) {
            val e = assertFailsWith<IllegalArgumentException> { Curve(bad) }
            assertTrue(
                e.message?.contains("finite") == true,
                "the message should say what is wrong, was: ${e.message}",
            )
        }
    }
}
