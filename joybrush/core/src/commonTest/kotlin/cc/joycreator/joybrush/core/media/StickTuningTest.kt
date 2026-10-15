package cc.joycreator.joybrush.core.media

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A stick's own tuning (brush version 11, the flagship's): an empty tuning paints exactly as the shared tables do, and
 * the tuned curves really reach the contact.
 */
class StickTuningTest {
    private val proto2 = Sticks.ALL.getValue("Proto 2")

    @Test fun anEmptyTuningIsTheSharedTables() {
        val tuned = proto2.copy(tuning = StickTuning())
        assertEquals(StickMaterial.of(proto2), StickMaterial.of(tuned))
        for (t in listOf(0.0, 0.3, 0.6, 0.8, 0.95, 1.0)) for (p in listOf(0.05, 0.3, 0.7, 1.0)) {
            val a = stickContact(proto2, t * PI / 2, p, 0.05)!!
            val b = stickContact(tuned, t * PI / 2, p, 0.05)!!
            for ((x, y) in listOf(a.d to b.d, a.r0 to b.r0, a.w0 to b.w0, a.w1 to b.w1, a.w2 to b.w2, a.l1 to b.l1, a.l2 to b.l2, a.xMax to b.xMax))
                assertEquals(x, y, 1e-12, "t=$t p=$p")
        }
    }

    @Test fun straightCurvesAreTheIdentity() {
        for (x in listOf(0.0, 0.1, 0.37, 0.5, 0.9, 1.0)) assertEquals(x, stickCurve(LINEAR_CURVE, x), 1e-6)
    }

    @Test fun thePointCurveHoldsThePointOnThroughTheTilt() {
        // Bent below the line (the flagship's), the point keeps more of the pressure while the side comes in.
        val straight = proto2.copy(tuning = StickTuning())
        val holds = proto2.copy(tuning = StickTuning(pointCurve = listOf(0.75, 0.05, 0.95, 0.35)))
        val a = stickContact(straight, 0.8 * PI / 2, 0.5, 0.05)!!
        val b = stickContact(holds, 0.8 * PI / 2, 0.5, 0.05)!!
        assert(b.w0 > a.w0 + 0.05) { "point share ${b.w0} vs ${a.w0}" }
        assertEquals(1.0, b.w0 + b.w1 + b.w2, 1e-12, "the shares still add up to the whole squeeze")
    }

    @Test fun theFlagshipUsesItsOwnPressAndReach() {
        val pp = Sticks.ALL.getValue("Proto Pencil")
        assertEquals(1.4, pp.tuning!!.press.depth)
        assertNotEquals(StickMaterial.of(pp.copy(tuning = null)).abrasion, StickMaterial.of(pp).abrasion)
        assertEquals(9.2, StickMaterial.of(pp).lutPress.toothStiffness)
    }
}
