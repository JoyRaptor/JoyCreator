package cc.joycreator.joybrush.core.brush

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Frozen v17 dual-mask arithmetic, bounds and independent periodic oracle. */
class DualTipTest {
    private val image = DualImageSpec("brushes/dual.png")
    private fun recipe(scale: Float = .3f, spacing: Float = .75f, scatter: Float = .35f) =
        DualTipSpec(shape = null, image = image, scale = scale, spacing = spacing, scatter = scatter)
    private fun set(mask: ImageMask = ImageMask.fromR8(3, 2,
        byteArrayOf(0, -1, 64, -1, 32, -1)), names: List<String> = listOf(image.image)) =
        SampledTipSet(names, listOf(mask))

    @Test fun arithmeticModesAreDistinctAndDoNotExpandPrimary() {
        assertEquals(.15f, DualTips.combine(.6f, .25f, DualMode.multiply))
        assertEquals(.35f, DualTips.combine(.6f, .25f, DualMode.subtract), .000001f)
        assertEquals(.25f, DualTips.combine(.6f, .25f, DualMode.min))
        assertEquals(0f, DualTips.combine(.2f, .9f, DualMode.subtract))
        for (mode in DualMode.entries) for (a in 0..10) for (b in 0..10) {
            val result = DualTips.combine(a / 10f, b / 10f, mode)
            assertTrue(result in 0f..(a / 10f))
        }
    }

    @Test fun sameSeedIsImmutableAndOtherStreamsCannotAffectIt() {
        val spec = recipe()
        val masks = set()
        val expected = DualTips.bake(spec, 123, masks)
        val legacy = SplitMix(123)
        repeat(1000) { legacy.nextFloat() }
        val actual = DualTips.bake(spec, 123, masks)
        assertContentEquals(expected.bytes(), actual.bytes())
        assertFalse(expected.bytes().contentEquals(DualTips.bake(spec, 124, masks).bytes()))
        val exported = expected.bytes()
        exported.fill(0)
        assertContentEquals(actual.bytes(), expected.bytes())
        assertEquals(expected.stats, actual.stats)
    }

    @Test fun textureLookupRepeatsAcrossEverySeamAndHandlesMissingCoordinates() {
        val mask = DualTips.bake(recipe(), 23, set())
        for (x in listOf(-1.25f, -.5f, -.001f, 0f, .7f, 1.25f)) {
            val a = mask.sample(x, .123f)
            val b = mask.sample(x + mask.period, .123f - 2 * mask.period)
            assertTrue(kotlin.math.abs(a - b) < .0001f)
        }
        assertEquals(0f, mask.sample(Float.NaN, 0f))
        assertEquals(0f, mask.sample(0f, Float.POSITIVE_INFINITY))
    }

    @Test fun candidateBoundsMatchLargeIndependentPeriodicOracle() {
        // Wide secondary support, maximal jitter, rectangular/tiny images and edge pixels all
        // exercise copies outside the nearest neighboring lattice cell. Oracle scans +/-8
        // periods (well beyond max support4 / period>=2 plus <=1 pitch displacement).
        val inputs = listOf(
            recipe(2f, .125f, 1f) to ImageMask.fromR8(1, 1, byteArrayOf(-1)),
            recipe(.5f, .75f, 1f) to set().mask(0),
        )
        for ((spec, source) in inputs) {
            val seed = 933L
            // This deliberately huge-support oracle exercises the complete lattice rather
            // than the default interactive work allowance (separately tested below).
            val baked = DualTips.bake(spec, seed, set(source), DualBakeLimits(maxSampleWork = 64_000_000))
            val bytes = baked.bytes()
            val n = baked.stats.cellsPerAxis
            val pitch = baked.period / n
            val texel = baked.period / baked.width
            for (y in listOf(0, 1, 19, 127, 254, 255)) for (x in listOf(0, 1, 34, 128, 254, 255)) {
                var value = 0f
                for (j in 0 until n) for (i in 0 until n) {
                    val random = SplitMix(seed xor 0x4455414c4c415454L xor ((j * n + i).toLong() * -0x61c8864680b583ebL))
                    val cx = (i + .5f + (2f * random.nextFloat() - 1f) * spec.scatter) * pitch
                    val cy = (j + .5f + (2f * random.nextFloat() - 1f) * spec.scatter) * pitch
                    for (dy in -8..8) for (dx in -8..8) {
                        val qx = ((x + .5f) * texel - (cx + dx * baked.period)) / spec.scale
                        val qy = ((y + .5f) * texel - (cy + dy * baked.period)) / spec.scale
                        value = max(value, source.sample(qx, qy))
                    }
                }
                assertEquals((value * 255f).roundToInt(), bytes[y * baked.width + x].toInt() and 255,
                    "scale=${spec.scale} ($x,$y)")
            }
        }
    }

    @Test fun proceduralSourceUsesRoundedQuadAndHardProfile() {
        val round = DualTips.bake(DualTipSpec(scale = .4f, spacing = 2f, scatter = 0f), 7)
        val square = DualTips.bake(DualTipSpec(shape = QuadShapeSpec(roundness = 0f), scale = .4f,
            spacing = 2f, scatter = 0f), 7)
        assertFalse(round.bytes().contentEquals(square.bytes()))
        assertTrue(round.bytes().any { it.toInt() != 0 })
        assertTrue(square.bytes().any { it.toInt() == 0 })
        val empty = QuadShapeSpec(points = List(4) { QuadPoint(0f, 0f) })
        assertTrue(DualTips.bake(DualTipSpec(shape = empty), 7).bytes().all { it.toInt() == 0 })
    }

    @Test fun imageRecipeAndBudgetsRefuseWholeBakeInsteadOfPartialMasks() {
        assertFailsWith<BrushException> { DualTips.bake(recipe(), 1) }
        assertFailsWith<BrushException> { DualTips.bake(recipe(), 1, set(names = listOf("brushes/wrong.png"))) }
        assertFailsWith<BrushException> { DualTips.bake(recipe(), 1, set(), DualBakeLimits(maxPixels = 65535)) }
        assertFailsWith<BrushException> { DualTips.bake(recipe(), 1, set(), DualBakeLimits(maxCandidates = 1)) }
        assertFailsWith<BrushException> { DualTips.bake(recipe(), 1, set(), DualBakeLimits(maxSampleWork = 1)) }
        assertFailsWith<BrushException> { DualTips.bake(DualTipSpec(), 1, limits = DualBakeLimits(maxSampleWork = 1)) }
        assertTrue(DualTips.problems(DualTipSpec(scale = .05f, spacing = .125f)).isNotEmpty())
        assertTrue(DualTips.problems(DualTipSpec(image = image)).isNotEmpty())
        assertTrue(DualTips.problems(DualTipSpec(shape = null)).isNotEmpty())
        assertTrue(DualTips.problems(DualTipSpec(shape = QuadShapeSpec(profile = TipProfileSpec()))).isNotEmpty())
        assertTrue(DualTips.problems(DualTipSpec(scale = Float.NaN)).isNotEmpty())
    }

    @Test fun bakingDifferentModesDoesNotConsumeGeometryRandomness() {
        val masks = set()
        val reference = DualTips.bake(recipe(), -12, masks)
        for (mode in DualMode.entries) {
            val alternate = DualTips.bake(recipe().copy(mode = mode), -12, masks)
            assertContentEquals(reference.bytes(), alternate.bytes())
            assertEquals(reference.period, alternate.period)
            assertEquals(reference.stats, alternate.stats)
        }
    }
}
