package cc.joycreator.joybrush.core.brush

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JB-1.06 — the smudge maths, and above all the PATENT RULE (blueprint §5, R8): ONE carried colour that mixes toward
 * the canvas and toward the chosen colour in one step; never a reservoir, never a threshold.
 *
 * Every expected number is derived in the test that uses it (R9). The closed form is
 *     c' = (1 - load)(1 - pickup) c + (1 - load) pickup canvas + load L
 * which is `lerp(lerp(c, canvas, pickup), L, load)` multiplied out — written here independently of the two nested
 * `mix` calls in [SmudgeCarried.afterDab], so a wrong nesting fails.
 */
class SmudgeTest {

    private fun carried(
        pickup: Float, load: Float, strength: Float = 1f,
        loadRgb: FloatArray = floatArrayOf(0.9f, 0.1f, 0.4f),
        startRgb: FloatArray = loadRgb,
    ) = SmudgeCarried(loadRgb[0], loadRgb[1], loadRgb[2], strength, pickup, load, startRgb[0], startRgb[1], startRgb[2], 1f)

    // ---- Test 1: the patent rule as two convergence properties -------------------------------------------

    @Test
    fun withNoLoadTheCarriedColourWalksMonotonicallyToTheCanvasAndNeverPassesIt() {
        // pickup 0.1, load 0: c_k = canvas + (c_0 - canvas) * 0.9^k, so every channel closes on the canvas by a
        // constant factor per dab. A reservoir ("deposit only when there is enough") would be a JUMP, which this
        // monotonic sequence cannot contain.
        val canvas = floatArrayOf(0.2f, 0.4f, 0.6f)
        var c = carried(pickup = 0.1f, load = 0f, loadRgb = floatArrayOf(1f, 1f, 1f), startRgb = floatArrayOf(1f, 1f, 1f))
        var gap = floatArrayOf(0.8f, 0.6f, 0.4f) // c_0 - canvas
        repeat(200) {
            val next = c.afterDab(canvas[0], canvas[1], canvas[2], 1f)
            val nextGap = floatArrayOf(next.carriedR - canvas[0], next.carriedG - canvas[1], next.carriedB - canvas[2])
            for (i in 0..2) {
                assertTrue(nextGap[i] >= 0f, "channel $i passed the canvas: ${nextGap[i]}")
                assertTrue(nextGap[i] <= gap[i] + 1e-7f, "channel $i moved AWAY from the canvas")
                assertEquals(gap[i] * 0.9f, nextGap[i], 1e-6f, "a constant fraction closes per dab (0.9)")
            }
            c = next; gap = nextGap
        }
        for (i in 0..2) assertTrue(gap[i] < 1e-6f, "200 dabs at 0.9^k leave ${gap[i]}")
    }

    @Test
    fun withNoPickupTheCarriedColourWalksMonotonicallyToTheChosenColourAndStrengthNeverChangesIt() {
        val load = floatArrayOf(0.8f, 0.5f, 0.2f)
        for (strength in listOf(0f, 0.3f, 1f)) {
            var c = carried(pickup = 0f, load = 0.1f, strength = strength, loadRgb = load, startRgb = floatArrayOf(0f, 0f, 0f))
            var gap = floatArrayOf(0.8f, 0.5f, 0.2f) // L - c_0
            repeat(200) {
                val next = c.afterDab(0.3f, 0.3f, 0.3f, 1f) // the canvas is irrelevant when pickup = 0
                val nextGap = floatArrayOf(load[0] - next.carriedR, load[1] - next.carriedG, load[2] - next.carriedB)
                for (i in 0..2) {
                    assertTrue(nextGap[i] >= 0f && nextGap[i] <= gap[i] + 1e-7f, "strength $strength channel $i")
                    assertEquals(gap[i] * 0.9f, nextGap[i], 1e-6f)
                }
                c = next; gap = nextGap
            }
            assertTrue(gap.all { it < 1e-6f })
        }
    }

    // ---- Test 2: one store, one step ---------------------------------------------------------------------

    @Test
    fun everyDabMovesTheCarriedColourByExactlyTheClosedFormAndNothingElse() {
        val pickup = 0.3f
        val load = 0.2f
        val l = floatArrayOf(0.9f, 0.1f, 0.4f)
        val canvas = floatArrayOf(0.3f, 0.6f, 0.1f)
        var c = carried(pickup, load, loadRgb = l, startRgb = floatArrayOf(0.2f, 0.5f, 0.8f))
        // Independent recurrence: (1-l)(1-p) c + (1-l) p canvas + l L, per channel.
        val expected = floatArrayOf(0.2f, 0.5f, 0.8f)
        repeat(500) { dab ->
            for (i in 0..2) expected[i] = (1 - load) * (1 - pickup) * expected[i] + (1 - load) * pickup * canvas[i] + load * l[i]
            c = c.afterDab(canvas[0], canvas[1], canvas[2], 1f)
            assertEquals(expected[0], c.carriedR, 1e-5f, "dab ${dab + 1} R")
            assertEquals(expected[1], c.carriedG, 1e-5f, "dab ${dab + 1} G")
            assertEquals(expected[2], c.carriedB, 1e-5f, "dab ${dab + 1} B")
        }
    }

    // ---- Test 3: range, purity ---------------------------------------------------------------------------

    @Test
    fun theCarriedColourNeverLeavesZeroToOneAndAStepDoesNotChangeItsInput() {
        val steps = listOf(0f, 0.25f, 0.5f, 1f)
        for (pickup in steps) for (load in steps) for (a in steps) for (canvasV in steps) {
            val c0 = SmudgeCarried(0.2f, 0.9f, 0.5f, 1f, pickup, load, 1f, 0f, 0.5f, 1f)
            val before = c0.copy()
            val c1 = c0.afterDab(canvasV, 1f - canvasV, canvasV * 0.5f, a)
            assertEquals(before, c0, "afterDab changed its own input")
            for (v in listOf(c1.carriedR, c1.carriedG, c1.carriedB, c1.carriedA)) assertTrue(v in 0f..1f, "left 0..1: $v")
        }
    }

    // ---- Test 4: an empty canvas is left alone -----------------------------------------------------------

    @Test
    fun aPixelWithNothingOnTheCanvasChangesNothingAndTheCarriedColourStaysTheSameObject() {
        val c = carried(pickup = 0.7f, load = 0.4f)
        assertSame(c, c.afterDab(0.5f, 0.5f, 0.5f, 0f), "canvasA = 0 must return this instance")
        val out = Smudge.dab(c, 0f, 0f, 0f, 0f, 1f)
        assertEquals(listOf(0f, 0f, 0f, 0f), out.toList())

        // A stroke over a transparent stretch then a painted one carries, into the painted part, exactly what it
        // carried before the transparent part.
        var s = c
        repeat(50) { s = s.afterDab(0f, 0f, 0f, 0f) }
        assertEquals(c, s)
        val onPaint = s.afterDab(0.2f, 0.2f, 0.2f, 1f)
        assertEquals(c.afterDab(0.2f, 0.2f, 0.2f, 1f), onPaint)
    }

    // ---- Test 5: a smudge is a smear, not paint -----------------------------------------------------------

    @Test
    fun coverageScalesTheMixNotTheOutputOpacity() {
        // Canvas opaque black, carried opaque white. out = lerp(canvas, carried, strength x coverage).
        val white = SmudgeCarried(1f, 1f, 1f, 0.5f, 0f, 0f) // starts as the chosen colour: white, opaque
        val half = Smudge.dab(white, 0f, 0f, 0f, 1f, 1f)     // t = 0.5 x 1 = 0.5
        assertEquals(listOf(0.5f, 0.5f, 0.5f, 1f), half.toList())
        val eighth = Smudge.dab(white, 0f, 0f, 0f, 1f, 0.25f) // t = 0.5 x 0.25 = 0.125 (NOT a quarter: the spec's arithmetic slip, R47)
        assertEquals(listOf(0.125f, 0.125f, 0.125f, 1f), eighth.toList())
        val strength1 = SmudgeCarried(1f, 1f, 1f, 1f, 0f, 0f)
        assertEquals(0.25f, Smudge.dab(strength1, 0f, 0f, 0f, 1f, 0.25f)[0], 1e-7f, "strength 1 x coverage 0.25 = a quarter")
        // The output alpha stays the canvas's alpha here because the carried colour is opaque too.
        assertEquals(1f, half[3])
    }

    // ---- Test 6: determinism -----------------------------------------------------------------------------

    @Test
    fun theSameCanvasSequenceGivesBitIdenticalResultsAndADifferentOneDoesNot() {
        fun run(canvases: List<FloatArray>): SmudgeCarried {
            var s = carried(0.35f, 0.1f)
            for (cv in canvases) s = s.afterDab(cv[0], cv[1], cv[2], cv[3])
            return s
        }
        val a = List(40) { i -> floatArrayOf((i % 7) / 7f, (i % 5) / 5f, (i % 3) / 3f, 1f) }
        val b = a.map { floatArrayOf(1f - it[0], it[1], it[2], it[3]) }
        assertEquals(run(a), run(a))
        assertNotEquals(run(a), run(b), "the maths must actually read the canvas")
    }

    // ---- Test 7: running back over the path undoes the smear ---------------------------------------------

    @Test
    fun withFullPickupAndNoLoadTheCarriedColourIsWhateverWasUnderTheLastDab() {
        // pickup 1 / load 0: c' = canvas. So A, then B, then A again leaves the carried colour exactly as the
        // first A left it: a brush run back over its own path carries what it met.
        val a = floatArrayOf(0.1f, 0.7f, 0.3f)
        val b = floatArrayOf(0.9f, 0.2f, 0.5f)
        val start = carried(1f, 0f)
        val afterA = start.afterDab(a[0], a[1], a[2], 1f)
        val afterB = afterA.afterDab(b[0], b[1], b[2], 1f)
        val afterA2 = afterB.afterDab(a[0], a[1], a[2], 1f)
        assertEquals(afterA.carriedR, afterA2.carriedR, 1e-6f)
        assertEquals(afterA.carriedG, afterA2.carriedG, 1e-6f)
        assertEquals(afterA.carriedB, afterA2.carriedB, 1e-6f)
    }

    // ---- Test 8: bad numbers are refused in words --------------------------------------------------------

    @Test
    fun aNumberThatIsNotAFractionIsRefusedAtConstructionNamingTheFieldAndTheValue() {
        val cases = listOf<Pair<String, () -> Any>>(
            "strength" to { SmudgeCarried(0f, 0f, 0f, Float.NaN, 0.5f, 0.5f) },
            "pickup" to { SmudgeCarried(0f, 0f, 0f, 1f, Float.POSITIVE_INFINITY, 0.5f) },
            "load" to { SmudgeCarried(0f, 0f, 0f, 1f, 0.5f, -1f) },
            "loadR" to { SmudgeCarried(Float.NaN, 0f, 0f, 1f, 0.5f, 0.5f) },
            "strength" to { SmudgeCarried(0f, 0f, 0f, 1.5f, 0.5f, 0.5f) },
            "carriedA" to { SmudgeCarried(0f, 0f, 0f, 1f, 0.5f, 0.5f, 0f, 0f, 0f, 2f) },
        )
        for ((field, make) in cases) {
            val e = assertFailsWith<IllegalArgumentException>("field $field") { make() }
            assertTrue(e.message!!.contains(field), "the message should name $field: ${e.message}")
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { SmudgeCarried(0f, 0f, 0f, 1f, 0.5f, -1f) }.message!!.contains("-1"))
    }

    // ---- Test 11: premultiplied-safe sweep ---------------------------------------------------------------

    @Test
    fun noCanvasColourAndNoCarriedColourEverProducesANaNOrLeavesZeroToOne() {
        val canvases = listOf(
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(1f, 1f, 1f, 1f), floatArrayOf(1f, 0f, 0f, 0.5f),
            floatArrayOf(0.4f, 0.4f, 0.4f, 0f), // colour with no alpha: the case an unpremultiplied lerp gets wrong
        )
        val carrieds = listOf(
            SmudgeCarried(0f, 0f, 0f, 1f, 0.5f, 0.5f, 0f, 0f, 0f, 0f),
            SmudgeCarried(0.2f, 0.4f, 0.6f, 1f, 0.5f, 0.5f, 0.2f, 0.4f, 0.6f, 1f),
        )
        val out = FloatArray(4)
        for (cv in canvases) for (c in carrieds) for (cov in listOf(0f, 0.3f, 1f)) {
            Smudge.dab(c, cv[0], cv[1], cv[2], cv[3], cov, out)
            for (v in out) assertTrue(v in 0f..1f && !v.isNaN(), "out of range: ${out.toList()}")
            val next = c.afterDab(cv[0], cv[1], cv[2], cv[3])
            for (v in listOf(next.carriedR, next.carriedG, next.carriedB, next.carriedA)) assertTrue(v in 0f..1f && !v.isNaN())
        }
    }

    @Test
    fun theCallerSuppliedOutputArrayIsTheOneWrittenAndAShortOneIsRefused() {
        val c = carried(0.5f, 0.1f)
        val out = FloatArray(4)
        Smudge.dab(c, 0f, 0f, 0f, 1f, 1f, out)
        assertTrue(abs(out[0] - c.carriedR) < 1e-6f, "strength 1 x coverage 1 replaces the canvas with the carried colour")
        assertFailsWith<IllegalArgumentException> { Smudge.dab(c, 0f, 0f, 0f, 1f, 1f, FloatArray(3)) }
    }
}
