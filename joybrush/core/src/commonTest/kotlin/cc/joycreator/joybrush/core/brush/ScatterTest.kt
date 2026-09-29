package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.paint.Dab
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScatterTest {

    /** A pen that reports everything at its midpoint, so a curve on `scatter.amount` is inert. */
    private val inputs = DabInputs(
        pressure = 1f, tilt = 0f, speedPxPerS = 0f, direction = 0f,
        lean = 0f, distancePx = 0f, random = 0.5f, strokeRandom = 0.5f, barrel = 0f,
    )

    /** [count] dabs at one point, at one angle — for the tests that care only about the copies. */
    private fun flat(count: Int, angle: Float = 0f, radius: Float = 1f): List<Dab> =
        List(count) { Dab(0f, 0f, radius, angle) }

    /** A straight run of [n] dabs 10 px apart along +x, so every group of copies is findable. */
    private fun run(n: Int, angle: Float = 0f, radius: Float = 1f): List<Dab> =
        List(n) { Dab(it * 10f, 0f, radius, angle) }

    /**
     * (across, along) for every copy, read back off dabs at ANGLE 0, where the tangent is +x and the
     * perpendicular is +y — so the two axes of the offset are the dab's own y and x, and nothing
     * needs inverting. The scale is the file's amount × the diameter, and both are read from the
     * same spec, so the pair recovered is the pair drawn.
     */
    private fun pairs(seed: Long, n: Int, spec: ScatterSpec): List<Pair<Float, Float>> {
        val scale = spec.amount.base * 2f
        return Scatter.expand(flat(n, 0f, 1f), spec, SplitMix(seed)) { inputs }
            .map { it.y / scale to it.x / scale }
    }

    private fun bin(v: Float, bins: Int): Int =
        ((v + 1f) / (2f / bins)).toInt().coerceIn(0, bins - 1)

    // ---- the file's two knobs do nothing at all ---------------------------------------------------

    @Test
    fun amountZeroAndCountOneLeavesTheStrokeExactlyWhereItWas() {
        // The -0.0f is the point of it. `x + 0f` on a negative zero is +0.0f, so a copy written that
        // way is not the value it started as — and "amount 0 changes nothing" has to mean the bits.
        val stroke = listOf(
            Dab(-0.0f, 5f, 3f, 0.4f, 0.5f, 0.6f, 0.7f),
            Dab(10f, -0.0f, 1f),
            Dab(20f, 30f, 0.5f, -1.2f),
        )
        val out = Scatter.expand(stroke, ScatterSpec(amount = Param(0f), count = 1), SplitMix(9L)) { inputs }
        assertEquals(stroke, out)
        // Not merely equal: the zero offset hands back the dab it was given, so there is no
        // arithmetic at all on the path a jitter-free brush takes for every dab of every stroke.
        for (i in out.indices) assertSame(stroke[i], out[i], "dab $i was rebuilt for nothing")

        // A count of 0 is one dab, not none: the floor in decision 2, and a brush nobody can draw
        // with is a brush that fails silently rather than loudly.
        val none = Scatter.expand(
            run(4), ScatterSpec(amount = Param(0.5f), count = 0), SplitMix(9L),
        ) { inputs }
        assertEquals(4, none.size)

        // And count 1 under a countJitter of 1 is still one, on every dab: round(1 - c) is 0 for
        // every c above 0.5, and those dabs must still exist.
        val thin = Scatter.expand(
            run(512), ScatterSpec(amount = Param(0f), count = 1, countJitter = 1f), SplitMix(3L),
        ) { inputs }
        assertEquals(512, thin.size)
    }

    // ---- the shape of the output ----------------------------------------------------------------

    @Test
    fun countThreeTurnsEveryDabIntoThreeAndKeepsTheDabItCameFrom() {
        val stroke = run(5, angle = 0.3f, radius = 3f)
        val out = Scatter.expand(
            stroke, ScatterSpec(amount = Param(0.3f), count = 3), SplitMix(11L),
        ) { inputs }
        assertEquals(15, out.size)
        // amount 0.3 of a diameter 6 is a scale of 1.8, so the furthest a copy can get in x is
        // 1.8 × (|sin 0.3| + |cos 0.3|) = 1.8 × 1.251 = 2.25 — and the dabs are 10 px apart, so 3 px
        // separates one group from the next with room to spare.
        for (i in 0 until 5) {
            val group = out.subList(i * 3, i * 3 + 3)
            // Every other field is the source dab's, and only x and y move.
            assertTrue(group.all { it.radius == 3f && it.angle == 0.3f }, "dab $i lost a field")
            assertTrue(group.all { abs(it.x - i * 10f) <= 3f }, "dab $i: ${group.map { it.x }}")
        }
    }

    @Test
    fun copiesKeepEveryOtherFieldOfTheSourceDab() {
        val one = Dab(10f, 20f, 3f, 0.5f, 0.4f, 0.6f, 0.7f)
        val out = Scatter.expand(
            listOf(one), ScatterSpec(amount = Param(1f), count = 5), SplitMix(2L),
        ) { inputs }
        assertEquals(5, out.size)
        for (c in out) {
            assertEquals(3f, c.radius)
            assertEquals(0.5f, c.angle)
            assertEquals(0.4f, c.flow)
            assertEquals(0.6f, c.cap)
            assertEquals(0.7f, c.pressure)
        }
        // The source is not modified: Dab is a value, and the list that came in is the list that
        // goes back out.
        assertEquals(Dab(10f, 20f, 3f, 0.5f, 0.4f, 0.6f, 0.7f), one)
    }

    // ---- where the copies land -------------------------------------------------------------------

    @Test
    fun bothAxesFalseKeepsEveryOffsetAcrossTheStroke() {
        // Angles spread right round, because a perpendicular that is only perpendicular to +x would
        // be a test that could not fail.
        val angles = listOf(0f, 0.3f, 1.0f, 2.0f, -2.0f, 3.1f, -0.7f, 1.57f, 0.9f, -1.4f, 2.7f, -0.2f,
            1.2f, -2.9f, 0.45f, 2.2f)
        val dabs = angles.mapIndexed { i, a -> Dab(i * 10f, 0f, 1f, a) }
        val copies = 4
        val out = Scatter.expand(
            dabs, ScatterSpec(amount = Param(1f), count = copies, bothAxes = false), SplitMix(4L),
        ) { inputs }
        assertEquals(angles.size * copies, out.size)

        val acrossAll = ArrayList<Float>()
        for (i in angles.indices) {
            val a = angles[i]
            val tx = cos(a.toDouble()).toFloat()
            val ty = sin(a.toDouble()).toFloat()
            for (c in out.subList(i * copies, i * copies + copies)) {
                val dx = c.x - dabs[i].x
                val dy = c.y - dabs[i].y
                // dx·t + dy·t = scale × across × (−sin·cos + cos·sin) = 0. The float error in that
                // is a handful of ulps of the offset, and the offset here is under 2 px.
                val along = dx * tx + dy * ty
                assertTrue(abs(along) < 1e-4f, "angle $a: offset ($dx, $dy) is $along along the stroke")
                acrossAll.add(dx * -ty + dy * tx)
            }
        }
        // …and it moved ACROSS, not nowhere. Not per copy — a single copy landing on the stroke
        // line is a legal draw, and asserting otherwise would fail about once in a thousand — but
        // across all 64 of them the box has to be filled. The range of 64 uniforms has an expected
        // value of 63/65 of the box and a spread of √2/64, which is about 0.02, so a range under
        // half the box is twenty sigma out; a scatter that did not move at all gives exactly 0.
        val spread = acrossAll.max() - acrossAll.min()
        assertTrue(spread > 1.5f, "the across offsets only spanned $spread of the 2 px box")
    }

    @Test
    fun everyOffsetStaysInsideTheBoxTheAmountDescribes() {
        val n = 2000
        val spec = ScatterSpec(amount = Param(1f), count = 1, bothAxes = false)
        // amount 1 of a diameter-2 dab: the box is 2 px across, and |offset| = 2 × |across| ≤ 2.
        val one = Scatter.expand(flat(n), spec, SplitMix(21L)) { inputs }
        var widest = 0f
        for (c in one) {
            val d = sqrt(c.x * c.x + c.y * c.y)
            assertTrue(d <= 2f, "an offset of $d px escaped a 2 px box")
            if (d > widest) widest = d
        }
        // …and the box is genuinely filled, not merely respected: the widest of 2000 draws of a
        // uniform in -1..1 comes within about 0.001 of 2, so anything under 1.8 would mean a scale
        // that had quietly shrunk.
        assertTrue(widest > 1.8f, "the box only ever reached $widest of 2")

        // With bothAxes the offsets are a sum of two perpendicular unit vectors, so the far CORNER
        // of the square is √2 out, not 1. Decision 3 asks for one scale factor and this is what one
        // scale factor gives; see the Questions in JB-1.05a.
        val both = Scatter.expand(flat(n), spec.copy(bothAxes = true), SplitMix(21L)) { inputs }
        val corner = 2f * sqrt(2f)
        var widestBoth = 0f
        for (c in both) {
            val d = sqrt(c.x * c.x + c.y * c.y)
            assertTrue(d <= corner, "an offset of $d px escaped a box with a $corner corner")
            if (d > widestBoth) widestBoth = d
        }
        assertTrue(widestBoth > 2.5f, "the corner was never approached: $widestBoth of $corner")
    }

    // ---- the stream -----------------------------------------------------------------------------

    @Test
    fun theSameSeedLeavesTheSameLeavesAndAnotherOneDoesNot() {
        fun go(seed: Long) = Scatter.expand(
            run(40, angle = 0.6f), ScatterSpec(amount = Param(0.7f), count = 4, countJitter = 0.5f),
            SplitMix(seed),
        ) { inputs }

        val first = go(4242L)
        // count 4 with a jitter of 0.5 is n = round(4 - 2c), and c is in 0..1, so n is 2, 3 or 4.
        // The range is a test of decision 2's arithmetic, not of the generator.
        assertTrue(first.size in 80..160, "${first.size} leaves from 40 dabs at count 4")
        assertEquals(first, go(4242L), "the same seed scattered differently")
        assertNotEquals(first, go(1L))
        // What is NOT asserted is that any two draws differ: SplitMix's float view is 24 bits, so
        // of 160 draws a repeat is a matter of chance rather than of a defect. See the report.
    }

    @Test
    fun eachDabDrawsOneCountAndTwoPerCopyOffTheStreamAndTickingBothAxesMovesNothing() {
        // All five dabs at the origin and at angle 0, so the across offset is exactly the y and the
        // along offset is exactly the x, and the two can be read apart bit for bit.
        val dabs = flat(5)
        val spec = ScatterSpec(amount = Param(1f), count = 3, countJitter = 0f)
        val seed = 31337L

        for (both in listOf(true, false)) {
            val rng = SplitMix(seed)
            Scatter.expand(dabs, spec.copy(bothAxes = both), rng) { inputs }
            // Exactly 5 × (1 + 2 × 3) = 35 draws, and the generator has to be sitting on the 36th.
            val replay = SplitMix(seed)
            repeat(5 * (1 + 2 * 3)) { replay.nextFloat() }
            assertEquals(replay.nextFloat(), rng.nextFloat(), "bothAxes=$both drew a different count")
        }

        // The other half of the same claim, and the one with teeth: if `b` were skipped when
        // bothAxes is off, the stream would be a float out of step from the SECOND dab onward and
        // the ACROSS offsets would move. Every y below must be bit-identical between the two runs.
        val off = Scatter.expand(dabs, spec.copy(bothAxes = false), SplitMix(seed)) { inputs }
        val on = Scatter.expand(dabs, spec.copy(bothAxes = true), SplitMix(seed)) { inputs }
        assertEquals(off.map { it.y }, on.map { it.y }, "the across axis moved with the checkbox")
        assertTrue(off.all { it.x == 0f }, "an along offset was applied with bothAxes off")
        assertTrue(on.any { it.x != 0f }, "bothAxes on and nothing moved along the stroke")

        // A stroke nobody drew is not a stroke, and must not take anything out of the stream: a
        // caller that expanded an empty list has to be sitting exactly where it was.
        val idle = SplitMix(seed)
        assertTrue(Scatter.expand(emptyList(), spec, idle) { inputs }.isEmpty())
        val fromNothing = SplitMix(seed)
        assertEquals(fromNothing.nextFloat(), idle.nextFloat(), "an empty stroke drew")
    }

    @Test
    fun countJitterThinsTheLeavesButNeverBelowOne() {
        val dabs = run(256)
        // countJitter 1 at count 4: n = round(4 × (1 - c)) is 4, 3, 2, 1 or 0, and the floor is 1.
        val out = Scatter.expand(
            dabs, ScatterSpec(amount = Param(0f), count = 4, countJitter = 1f), SplitMix(8L),
        ) { inputs }
        assertTrue(out.size in 256..1024, "${out.size} leaves from 256 dabs at count 4")

        // A count of 1 under a jitter of 1 is round(1 - c), which is 0 for every c above 0.5. Those
        // dabs must still exist, and over 256 of them the top half of the range is certain.
        val one = Scatter.expand(
            dabs, ScatterSpec(amount = Param(0f), count = 1, countJitter = 1f), SplitMix(8L),
        ) { inputs }
        assertEquals(256, one.size)

        // Jitters outside the validated 0..1, and the two that are not numbers at all, are the
        // files BrushValidate never saw. Neither may throw — roundToInt THROWS on a NaN — and
        // neither may loop. The ranges are decision 2 worked through by hand, from count 3:
        //   jitter  4 → n = round(3 - 12c) → 1, 2 or 3            → 256..768
        //   jitter -1 → n = round(3 +  3c) → 3, 4, 5 or 6         → 768..1536
        //   NaN      → not a number, so one un-scattered dab      → exactly 256
        //   +Inf     → not a number, so one un-scattered dab      → exactly 256
        val odd = listOf(
            Triple(4f, 256, 768),
            Triple(-1f, 768, 1536),
            Triple(Float.NaN, 256, 256),
            Triple(Float.POSITIVE_INFINITY, 256, 256),
        )
        for ((jitter, lo, hi) in odd) {
            val leaves = Scatter.expand(
                dabs, ScatterSpec(amount = Param(0f), count = 3, countJitter = jitter), SplitMix(8L),
            ) { inputs }
            assertTrue(leaves.size in lo..hi, "countJitter $jitter gave ${leaves.size}, want $lo..$hi")
        }
    }

    // ---- is it actually random -------------------------------------------------------------------

    /**
     * The question this whole class has to answer: is the scatter RANDOM, or is it quantised into
     * bands that read as a printed halftone? Uniformity is asserted, not distinctness — SplitMix's
     * float view is 24 bits, so 4096 draws collide about half a time by the birthday bound, and a
     * test demanding 4096 different numbers would fail one day on a generator that is right.
     *
     * Every tolerance below sits five to twenty sigma out for the counts, so none of them can flake;
     * a banded or quantised distribution misses by orders of magnitude, not by percent.
     */
    @Test
    fun theOffsetsFillTheirBoxEvenlyAndAreNotBanded() {
        val n = 4096
        val spec = ScatterSpec(amount = Param(1f), count = 1, bothAxes = true)
        val p = pairs(7777L, n, spec)
        assertEquals(n, p.size)

        // The two axes are drawn one pair at a time, so `across` and `along` are the odd and the
        // even draws of the same stream. Binning them SEPARATELY is what says each is a uniform in
        // its own right — the failure that matters is an axis of its own, not the pair.
        val across = p.map { it.first }
        val along = p.map { it.second }
        for ((name, values) in listOf("across" to across, "along" to along)) {
            val bins = IntArray(16)
            for (v in values) bins[bin(v, 16)]++
            // 4096 draws over 16 bins is 256 each, with a sigma of √240, about 15.5 — so 150..400 is
            // seven sigma out at either end and cannot flake. It catches every banding that leaves
            // the range at all, because any band of 15 or fewer doubles one bin and empties another.
            assertTrue(bins.all { it in 150..400 }, "$name is banded or has a hole in it: ${bins.toList()}")
            assertTrue(bins.max() < 2 * bins.min(), "$name is banded: ${bins.toList()}")
            // The mean of 4096 uniforms has a sigma of 0.009, so 0.05 is over five of them: it
            // cannot flake, and it does catch a bias of half a percent.
            assertTrue(abs(values.average()) < 0.05, "$name is off centre at ${values.average()}")
            // And it reaches both ends of the range rather than living in a comfortable middle.
            assertTrue(values.min() < -0.98f && values.max() > 0.98f, "$name never reached the ends")
            // Not quantised: a 24-bit projection of 4096 draws has about half a collision expected,
            // so 4000 distinct means a source far finer than any banding a person could see.
            assertTrue(values.toSet().size > 4000, "$name has only ${values.toSet().size} values")
        }

        // The square, which is what a stroke actually shows: 64 cells at 64 each, with a sigma of
        // √63, about 7.9. A band of them — four clusters, or a doubled grid — either empties a cell
        // or triples another, and no draw of 4096 uniforms does either.
        val cells = IntArray(64)
        for ((a, l) in p) cells[bin(a, 8) * 8 + bin(l, 8)]++
        assertTrue(cells.all { it in 1..160 }, "the square is banded: ${cells.toList()}")

        // A different seed is a different scatter, and the same SHAPE of scatter — the test that
        // says "random", not "one particular pattern that happens to reproduce".
        val other = pairs(31337L, n, spec)
        assertNotEquals(across, other.map { it.first })
        val otherBins = IntArray(16)
        for (v in other) otherBins[bin(v.first, 16)]++
        assertTrue(otherBins.all { it in 150..400 }, "the other seed is not uniform: ${otherBins.toList()}")
        assertTrue(otherBins.max() < 2 * otherBins.min(), "the other seed is banded: ${otherBins.toList()}")
    }

    // ---- two dabs, and one dab -------------------------------------------------------------------

    @Test
    fun twoDabsAtTheSamePointAreScatteredIndependentlyAndKeepTheirOrder() {
        val spec = ScatterSpec(amount = Param(0.3f), count = 3, bothAxes = true)
        // Same point, same everything — the case a cache keyed by position would collapse to one
        // copy, and the two triples sharing a scatter is exactly what that would look like.
        val together = Scatter.expand(
            listOf(Dab(50f, 50f, 1f), Dab(50f, 50f, 1f)), spec, SplitMix(6L),
        ) { inputs }
        assertEquals(6, together.size)
        assertNotEquals(together.take(3), together.drop(3), "two dabs at one point shared a scatter")

        // Order is the stroke's: a dab's copies are all in front of the next dab's. amount 0.3 of a
        // diameter 2 is a 0.6 px box, and even its corner is 0.85, so 1 px is a safe separator.
        val apart = Scatter.expand(
            listOf(Dab(0f, 0f, 1f), Dab(100f, 0f, 1f)), spec, SplitMix(6L),
        ) { inputs }
        assertTrue(apart.take(3).all { abs(it.x) < 1f }, "${apart.take(3).map { it.x }}")
        assertTrue(apart.drop(3).all { abs(it.x - 100f) < 1f }, "${apart.drop(3).map { it.x }}")

        // One dab is a stroke of one, and it scatters like any other. Across only, so the whole
        // group is inside the 0.6 px box and the bound is the tight one.
        val single = Scatter.expand(
            listOf(Dab(7f, 9f, 1f)), spec.copy(count = 6, bothAxes = false), SplitMix(6L),
        ) { inputs }
        assertEquals(6, single.size)
        for (c in single) {
            val d = sqrt((c.x - 7f) * (c.x - 7f) + (c.y - 9f) * (c.y - 9f))
            assertTrue(d <= 0.6f, "a single-dab stroke scattered $d of a 0.6 px box")
        }

        // And the pen is asked once per DAB, not once per copy: n copies are one dab moved, and a
        // caller that rebuilt the inputs per copy would pay for it and could get a different answer.
        var asked = 0
        Scatter.expand(run(6), spec, SplitMix(6L)) { asked++; inputs }
        assertEquals(6, asked)
    }

    @Test
    fun aDabWithNoRadiusIsMovedNotDividedBy() {
        // radius 0 is not a dab the placer can draw, but Scatter is handed whatever list it is
        // given, and a zero scale must be a zero offset rather than a NaN in the batch.
        val out = Scatter.expand(
            listOf(Dab(3f, 4f, 0f)), ScatterSpec(amount = Param(1f), count = 2), SplitMix(1L),
        ) { inputs }
        assertEquals(2, out.size)
        for (c in out) {
            assertTrue(c.x.isFinite() && c.y.isFinite(), "$c")
            assertEquals(3f, c.x)
            assertEquals(4f, c.y)
        }
    }
}
