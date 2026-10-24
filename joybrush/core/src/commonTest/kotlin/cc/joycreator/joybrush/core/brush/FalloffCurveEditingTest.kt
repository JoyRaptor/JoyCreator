package cc.joycreator.joybrush.core.brush

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** DRAFT — meaningful model/geometry fixtures; not compiled or run. */
class FalloffCurveEditingTest {
    private fun profile(points: List<List<Float>>) = TipProfileSpec(TipProfile.custom, points)
    private fun bits(p: TipProfileSpec) = p.curve.map { pair -> pair.map { it.toBits() } }
    private fun valid(p: TipProfileSpec) {
        assertTrue(BrushValidate.validate(BrushPreset(id = "falloff-test", name = "Falloff", size = Param(1f), tip = TipSpec(profile = p))).isEmpty())
    }

    @Test fun openingAndNoOpSnapshotPreserveEveryStoredFloatAndDoNotAliasInput() {
        val points = mutableListOf(
            mutableListOf(-0f, .13f), mutableListOf(Float.MIN_VALUE, .91f),
            mutableListOf(.125f, .125f), mutableListOf(.8f, .7f), mutableListOf(1f, 0f),
        )
        val original = profile(points)
        val before = bits(original)
        val opened = FalloffCurveEditing.checked(original)
        val done = FalloffCurveEditing.snapshot(opened)
        assertEquals(before, bits(opened)); assertEquals(before, bits(done))
        assertNotSame(original.curve, opened.curve); assertNotSame(original.curve[2], opened.curve[2])
        points[2][1] = .99f
        assertEquals(before, bits(done)); assertEquals(before, bits(opened))
        valid(opened)
    }

    @Test fun interiorDragUsesStrictRepresentableNeighborBoundsWithoutMergingPoints() {
        val half = .5f
        val p = FalloffCurveEditing.checked(profile(listOf(
            listOf(0f, 1f), listOf(Float.fromBits(half.toBits() - 1), .9f),
            listOf(half, .5f), listOf(Float.fromBits(half.toBits() + 1), .2f), listOf(1f, 0f),
        )))
        val moved = FalloffCurveEditing.move(p, 2, 1f, -2f)
        assertEquals(half.toBits(), moved.curve[2][0].toBits()); assertEquals(0f, moved.curve[2][1])
        assertEquals(p.curve.size, moved.curve.size)
        p.curve.indices.filter { it != 2 }.forEach { assertEquals(bits(p)[it], bits(moved)[it]) }
        assertTrue(moved.curve.zipWithNext().all { (a, b) -> a[0] < b[0] }); valid(moved)
    }

    @Test fun endpointsStayPresentFirstRadiusRawBitsStayFixedAndRimIsFullyFixed() {
        val p = FalloffCurveEditing.checked(profile(listOf(listOf(-0f, .3f), listOf(.4f, .6f), listOf(1f, 0f))))
        val changed = FalloffCurveEditing.move(p, 0, .9f, .8f)
        assertEquals((-0f).toBits(), changed.curve.first()[0].toBits()); assertEquals(.8f, changed.curve.first()[1])
        assertSame(p, FalloffCurveEditing.move(p, 2, 0f, 1f))
        assertFailsWith<BrushException> { FalloffCurveEditing.remove(p, 0) }
        assertFailsWith<BrushException> { FalloffCurveEditing.remove(p, 2) }
        valid(changed)
    }

    @Test fun addingInsertsAnInterpolatedPointAndKeepsAllOriginalPointsExactly() {
        val p = FalloffCurveEditing.checked(profile(listOf(listOf(0f, .6f), listOf(.4f, .8f), listOf(1f, 0f))))
        val edit = FalloffCurveEditing.addAfter(p, 0)
        assertEquals(1, edit.selected)
        assertEquals(.2f, edit.profile.curve[1][0]); assertEquals(.7f, edit.profile.curve[1][1], .000001f)
        assertEquals(bits(p), edit.profile.curve.filterIndexed { i, _ -> i != edit.selected }.map { point -> point.map { it.toBits() } })
        valid(edit.profile)
    }

    @Test fun addingAtRimUsesPreviousSegmentAndRemovingInteriorRecoversOriginalBits() {
        val p = FalloffCurveEditing.checked(profile(listOf(listOf(0f, .4f), listOf(.7f, .2f), listOf(1f, 0f))))
        val added = FalloffCurveEditing.addAfter(p, p.curve.lastIndex)
        assertEquals(2, added.selected)
        assertTrue(added.profile.curve[2][0] > .7f && added.profile.curve[2][0] < 1f)
        val removed = FalloffCurveEditing.remove(added.profile, added.selected)
        assertEquals(bits(p), bits(removed.profile)); valid(removed.profile)
    }

    @Test fun authoritativePointBudgetRefusesInsteadOfTruncating() {
        val count = FalloffCurveEditing.maxPoints
        assertEquals(minOf(256, BrushValidate.MAX_CURVE_POINTS), count)
        val p = FalloffCurveEditing.checked(profile((0 until count).map { i -> val x = i / (count - 1f); listOf(x, 1f - x) }))
        val before = bits(p)
        assertFailsWith<BrushException> { FalloffCurveEditing.addAfter(p, count / 2) }
        assertEquals(before, bits(p)); assertEquals(count, p.curve.size)
        assertFailsWith<BrushException> {
            FalloffCurveEditing.checked(profile((0..count).map { i -> val x = i / count.toFloat(); listOf(x, 1f - x) }))
        }
    }

    @Test fun adjacentFloatGapRefusesInsertionAndNonFiniteDragsNeverPoisonProfile() {
        val p = FalloffCurveEditing.checked(profile(listOf(listOf(0f, .2f), listOf(Float.MIN_VALUE, .8f), listOf(1f, 0f))))
        val before = bits(p)
        assertFailsWith<BrushException> { FalloffCurveEditing.addAfter(p, 0) }
        assertFailsWith<BrushException> { FalloffCurveEditing.move(p, 1, Float.NaN, .3f) }
        assertFailsWith<BrushException> { FalloffCurveEditing.move(p, 1, .2f, Float.POSITIVE_INFINITY) }
        assertEquals(before, bits(p))
    }

    @Test fun malformedCurvesAreRefusedBeforeEditingWithoutNormalization() {
        listOf(
            listOf(listOf(0f, 1f)),
            listOf(listOf(0f, 1f), listOf(0f, .2f), listOf(1f, 0f)),
            listOf(listOf(0f, 1f), listOf(.5f, Float.NaN), listOf(1f, 0f)),
            listOf(listOf(0f, 1f), listOf(1f, .01f)),
            listOf(listOf(0f, 1f), listOf(.5f), listOf(1f, 0f)),
        ).forEach { points -> assertFailsWith<BrushException> { FalloffCurveEditing.checked(profile(points)) } }
        assertFailsWith<BrushException> { FalloffCurveEditing.checked(TipProfileSpec(TipProfile.smooth)) }
    }

    @Test fun geometryFlipsCoverageAndKeepsFullActiveTargetWithinView() {
        val g = assertNotNull(FalloffEditorGeometry.fit(280f, 200f, 22f))
        for (point in listOf(listOf(0f, 1f), listOf(.25f, .75f), listOf(1f, 0f))) {
            val screen = g.position(point)
            assertTrue(screen.x - 22f >= 0f && screen.x + 22f <= 280f)
            assertTrue(screen.y - 22f >= 0f && screen.y + 22f <= 200f)
            assertEquals(point[0], g.radius(screen.x), .000001f)
            assertEquals(point[1], g.coverage(screen.y), .000001f)
        }
        assertNull(FalloffEditorGeometry.fit(44f, 200f, 22f))
        assertNull(FalloffEditorGeometry.fit(Float.NaN, 200f, 22f))
    }

    @Test fun visuallyCoincidentPointsPickSelectedThenEarliestAndRejectFarOrInvalidHits() {
        val p = FalloffCurveEditing.checked(profile(listOf(listOf(0f, 1f), listOf(.5f, .2f),
            listOf(Float.fromBits(.5f.toBits() + 1), .2f), listOf(1f, 0f))))
        val g = FalloffEditorGeometry(100f, 50f, 1f, 1f)
        val hit = g.position(p.curve[1])
        assertEquals(hit, g.position(p.curve[2])) // viewport rounding, while saved radii remain distinct
        assertEquals(2, g.nearest(p, hit.x, hit.y, .0001f, 2))
        assertEquals(1, g.nearest(p, hit.x, hit.y, .0001f, -1))
        assertEquals(-1, g.nearest(p, -100f, 50f, 22f, 1))
        assertEquals(-1, g.nearest(p, Float.NaN, 50f, 22f, 1))
        assertEquals(-1, g.nearest(p, hit.x, hit.y, 0f, 1))
    }
}
