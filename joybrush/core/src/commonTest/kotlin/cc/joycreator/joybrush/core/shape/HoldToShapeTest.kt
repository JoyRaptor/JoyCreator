package cc.joycreator.joybrush.core.shape

import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.tool.SizeOpacityDrag
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JB-2.11 spec tests 1-15, plus the one property this row exists for: a shaped stroke must cost ONE
 * undo, not two.
 *
 * `commonTest` and not `jvmTest`, and that is not a preference: nothing here opens a file, and the
 * fixture helper `StrokeMaker` is `internal` in `commonTest`, which the `jvmTest` compilation cannot
 * see. `./gradlew -p joybrush :core:jvmTest` runs this suite.
 *
 * ## The fixtures, and why their numbers are what they are
 *
 * - [movingStroke]: 40 samples at `(i*3, i*2)`, 16 ms apart. One step is `hypot(3,2) = 3.606` px, so
 *   the distance from the anchor passes `STILL_DP = 6` on the SECOND step (`3.606` is not > 6,
 *   `7.211` is). The step size is load-bearing: a pen moving more slowly would be legitimately
 *   still, the hold would fire, and every assertion about a moving pen would be about the wrong
 *   thing.
 * - [settledLine]: 17 samples at `(i*2, 0)`, 10 ms apart, and deliberately of a length that leaves
 *   the ANCHOR ON THE LAST SAMPLE, so a test can say exactly when the hold completes. At
 *   `density = 1f` the anchor moves on the fourth step (`2, 4, 6` are not > 6, `8` is), so 17
 *   samples re-anchor on indices 4, 8, 12, 16 and stop there. At `density = 2f` the tolerance is
 *   12 px, the anchor moves on the seventh step (`2..12` are not > 12, `14` is), and 17 samples
 *   re-anchor on indices 7 and 14.
 * - [circle]: `StrokeMaker.ellipse(300, 300, 120, 120, 0°, 1 turn, seed 7)`, which is hand-drawn -
 *   ~1.5 px resample, ±1.5 px jitter, a 2 px wobble, pressure 0.3 -> 0.9 -> 0.4, tilt rising
 *   0.2 -> 0.7. `2π × 120 ≈ 754` px of path is about 503 samples, well past the recogniser's
 *   `MIN_POINTS = 5` and `MIN_LENGTH = 12`. The roughening is what makes the feel tests worth
 *   running.
 * - [longStroke]: n samples 1.5 px apart along +x, 4 ms apart, so a 1.5 px step re-anchors every
 *   fifth sample (`1.5, 3.0, 4.5, 6.0` are not > 6, `7.5` is) and the still clock is never more
 *   than 16 ms behind. That is what makes "the hold cannot complete before the end" a derivation
 *   rather than a hope.
 */
class HoldToShapeTest {

    // ── 1. a pen that keeps moving is not a hold ────────────────────────────────────────────────

    @Test
    fun aMovingPenNeverShapes() {
        val s = movingStroke()
        val h = HoldToShape(density = 1f)
        for (p in s) {
            h.add(p)
            assertSame(HoldToShape.State.Idle, h.state, "shaped at t=${p.timeMs} while the pen was moving")
        }
        // Never held: the original samples, the very same instances.
        val out = h.lift()
        assertEquals(s.size, out.size)
        for (i in s.indices) assertSame(s[i], out[i], "sample $i came back as a copy")
    }

    @Test
    fun everyMovePastTheToleranceRestartsTheStillClock() {
        val s = movingStroke()
        val h = HoldToShape(density = 1f)
        for (p in s) h.add(p)
        // The still clock runs from the last RE-ANCHOR, which is the last even sample: index 38,
        // t = 608.0 (see the class fixture note). The hold therefore completes at 608.0 + HOLD_MS
        // and not a millisecond earlier, which is a stronger claim than "it is still Idle at the
        // end": a clock that restarted anywhere else would fire at a different time.
        val fires = 38 * 16.0 + HoldToShape.HOLD_MS
        h.tick(fires - 1.0)
        assertSame(HoldToShape.State.Idle, h.state, "one millisecond early is not yet a hold")
        h.tick(fires)
        assertIs<HoldToShape.State.Shaping>(h.state, "the hold did not complete at 608 + HOLD_MS")
    }

    // ── 2. the boundary ────────────────────────────────────────────────────────────────────────

    @Test
    fun theHoldFiresAtHoldMsAndNotOneMillisecondBefore() {
        val s = settledLine()
        val h = HoldToShape(density = 1f)
        for (p in s) h.add(p)
        // 17 samples at 2 px leave the anchor on index 16, so stillSince is the last sample's time.
        val anchorTime = 16 * 10.0
        h.tick(anchorTime + HoldToShape.HOLD_MS - 1.0)
        assertSame(HoldToShape.State.Idle, h.state, "one millisecond early is not yet a hold")
        h.tick(anchorTime + HoldToShape.HOLD_MS)
        val shaping = assertIs<HoldToShape.State.Shaping>(h.state, "at exactly HOLD_MS it is a shape")
        assertIs<Shape.Line>(shaping.shape)
    }

    // ── 3 + 4. what comes back ─────────────────────────────────────────────────────────────────

    @Test
    fun aHeldAndRecognisedStrokeLiftsAsTheSameNumberOfSamples() {
        val s = circle()
        val h = HoldToShape(density = 1f)
        for (p in s) h.add(p)
        h.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        val shape = assertIs<Shape.Ellipse>(assertIs<HoldToShape.State.Shaping>(h.state).shape)
        val out = h.lift()

        assertEquals(s.size, out.size, "ShapePerfecter returns one sample per input sample")
        // Every output is ON the fitted circle, not merely near it: the perfecter walks a 720-gon
        // inscribed in it, so the worst case is rx*(1 - cos(PI/720)) = 120*0.0000383 = 0.0046 px.
        for (i in out.indices) {
            val r = hypot(out[i].x - shape.center.x, out[i].y - shape.center.y)
            assertTrue(abs(r - shape.rx) < 0.05, "sample $i is ${r - shape.rx} px off the circle")
        }
        // …and the input was NOT already on it. The wobble puts 2/3 of the samples more than 1 px
        // from any fitted circle (|2*sin| > 1 for two thirds of every 40-sample period), so a
        // "did anything move?" check cannot pass by a rounding accident.
        val moved = s.indices.count { hypot(out[it].x - s[it].x, out[it].y - s[it].y) > 1f }
        assertTrue(moved * 3 > s.size, "only $moved of ${s.size} samples moved: the stroke was not perfected")
    }

    @Test
    fun everyFeelChannelSurvivesTheLift() {
        val s = circle()
        val h = HoldToShape(density = 1f)
        for (p in s) h.add(p)
        h.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        val out = h.lift()
        assertEquals(s.size, out.size)
        for (i in s.indices) {
            assertSameChannel(s[i].pressure, out[i].pressure, "pressure at $i")
            assertSameChannel(s[i].tilt, out[i].tilt, "tilt at $i")
            assertSameChannel(s[i].azimuth, out[i].azimuth, "azimuth at $i")
            assertSameChannel(s[i].barrel, out[i].barrel, "barrel at $i")
            assertEquals(s[i].timeMs, out[i].timeMs, "time at $i")
            assertEquals(s[i].tool, out[i].tool, "tool at $i")
        }
    }

    // ── 5. a stroke that is not a shape ─────────────────────────────────────────────────────────

    @Test
    fun aStrokeThatIsNotAShapeWaitsAndThenGivesUp() {
        val s = StrokeMaker.scribble(3)
        // Asserted here so a future ShapeRecognizer that starts recognising scribbles turns THIS red
        // rather than quietly changing what the rest of the test means.
        assertNull(ShapeRecognizer.recognize(s), "the scribble became a shape; this fixture is wrong")

        val h = HoldToShape(density = 1f)
        for (p in s) h.add(p)
        h.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        val waiting = assertIs<HoldToShape.State.Waiting>(h.state)
        assertTrue(waiting.stalledMs >= HoldToShape.HOLD_MS, "stalled only ${waiting.stalledMs} ms")

        // 20 px and not 7: the last add left the pen within STILL_DP of the anchor, so a 20 px move
        // is at least 20 - 6 = 14 px from the anchor whatever the anchor was. A 7 px move can land
        // 1 px from it, which is not a move at all.
        val away = PenSample(x = s.last().x + 20f, y = s.last().y, timeMs = s.last().timeMs + 10.0)
        h.add(away)
        assertIs<HoldToShape.State.Abandoned>(h.state, "a 20 px move is past a 6 px tolerance")

        val out = h.lift()
        assertEquals(s.size + 1, out.size, "abandonment must not lose or duplicate samples")
        for (i in s.indices) assertSame(s[i], out[i], "sample $i was not handed back unchanged")
        assertSame(away, out.last())
    }

    // ── 6. the recogniser runs once, ever ───────────────────────────────────────────────────────

    @Test
    fun theRecogniserRunsExactlyOnceAndSeesTheStrokeAsItStoodAtTheHold() {
        var calls = 0
        var captured: List<PenSample> = emptyList()
        var seenZoom = 0f
        val s = longStroke(5000)
        val h = HoldToShape(
            density = 1f,
            screenPerDoc = 2.5f,
            recognize = { pts, zoom ->
                calls++
                captured = pts
                seenZoom = zoom
                Shape.Line(Pt(0.0, 0.0), Pt(10.0, 0.0))
            },
        )
        // Fed one at a time with a tick between every two. Each tick is 4 ms past a sample whose
        // still clock is at most 16 ms old, so the elapsed time never reaches HOLD_MS: the hold
        // cannot complete early, which is why the captured count below is exactly 5000.
        var i = 0
        while (i < s.size - 1) {
            h.add(s[i])
            h.add(s[i + 1])
            h.tick(s[i + 1].timeMs)
            assertEquals(0, calls, "the recogniser ran while the pen was still moving")
            i += 2
        }
        h.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        assertEquals(1, calls, "the recogniser did not run exactly once at the hold")
        assertEquals(s.size, captured.size, "the recogniser saw a different number of samples than the stroke had")
        for (k in s.indices) assertSame(s[k], captured[k], "sample $k was replaced on the way in")
        assertEquals(2.5f, seenZoom, "the zoom was not passed to the recogniser")

        // The argument is a snapshot. A recogniser handed the live buffer would see it grow.
        val still = s.last()
        for (k in 1..200) {
            h.add(PenSample(still.x, still.y, still.timeMs + k * 4.0))
            h.tick(still.timeMs + k * 4.0)
        }
        assertEquals(1, calls, "the recogniser ran a second time")
        assertEquals(s.size, captured.size, "the captured list kept growing: it is the live buffer")
    }

    @Test
    fun aStrokeThatIsNotAShapeNeverGetsASecondLook() {
        var calls = 0
        val s = longStroke(5000)
        val h = HoldToShape(density = 1f, recognize = { _, _ -> calls++; null })
        var i = 0
        while (i < s.size - 1) {
            h.add(s[i])
            h.add(s[i + 1])
            h.tick(s[i + 1].timeMs)
            i += 2
        }
        h.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        assertEquals(1, calls)
        val waiting = assertIs<HoldToShape.State.Waiting>(h.state)

        // 200 stationary samples: a sample that does not move the pen does not restart the still
        // clock (Decision 2 re-anchors only a movement PAST the tolerance), so this is Waiting the
        // whole way and the hold gets no second look however long it lasts.
        val still = s.last()
        for (k in 1..200) h.add(PenSample(still.x, still.y, still.timeMs + k * 4.0))
        val later = still.timeMs + 200 * 4.0
        h.tick(later + HoldToShape.HOLD_MS)
        val after = assertIs<HoldToShape.State.Waiting>(h.state)
        assertTrue(after.stalledMs > waiting.stalledMs, "the still clock stopped while the pen was still")
        assertEquals(1, calls, "a stroke that was not a shape got a second look when it grew")
        assertEquals(5000 + 200, h.lift().size, "a Waiting stroke hands back the whole original stroke")
    }

    // ── 7. density ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theStillToleranceIsDpAndTheHoldIsMilliseconds() {
        val s = settledLine()
        val tol = HoldToShape.STILL_DP * 2f
        // At density 2 the tolerance is 12 screen px and 17 samples at 2 px leave the anchor on
        // index 14 (see the fixture note), at t = 140.
        val anchorX = s[14].x
        val anchorTime = s[14].timeMs

        val inside = HoldToShape(density = 2f)
        for (p in s) inside.add(p)
        inside.add(PenSample(x = anchorX + tol - 1f, y = 0f, timeMs = 200.0))
        inside.tick(anchorTime + HoldToShape.HOLD_MS)
        assertIs<HoldToShape.State.Shaping>(
            inside.state,
            "a ${tol - 1f} px move is inside a $tol px tolerance and must not restart the clock",
        )

        val outside = HoldToShape(density = 2f)
        for (p in s) outside.add(p)
        outside.add(PenSample(x = anchorX + tol + 1f, y = 0f, timeMs = 200.0))
        outside.tick(anchorTime + HoldToShape.HOLD_MS)
        assertSame(
            HoldToShape.State.Idle,
            outside.state,
            "a ${tol + 1f} px move is past a $tol px tolerance and must restart the clock",
        )
        outside.tick(200.0 + HoldToShape.HOLD_MS)
        assertIs<HoldToShape.State.Shaping>(outside.state, "the hold then fires from the new anchor")
    }

    // ── 8. zoom invariance ─────────────────────────────────────────────────────────────────────

    @Test
    fun theSameCircleIsTheSameShapeAtEveryZoom() {
        val s = circle()
        var first: Shape.Ellipse? = null
        for (k in listOf(0.25f, 1f, 4f)) {
            val h = HoldToShape(density = 1f, screenPerDoc = k)
            for (p in s) h.add(p)
            h.tick(s.last().timeMs + HoldToShape.HOLD_MS)
            val e = assertIs<Shape.Ellipse>(
                assertIs<HoldToShape.State.Shaping>(h.state).shape,
                "zoom $k did not recognise the circle",
            )
            val ref = first
            if (ref == null) {
                first = e
            } else {
                // The tolerance is not sloppiness: `recognize` multiplies every point by k and
                // divides the answer by k, and that round trip is not bit-exact. 1e-3 document px
                // on values near 300 is 3e-6 relative - seven orders above the round trip's error
                // and far below any difference that would mean the zoom leaked into the shape.
                assertEquals(ref.center.x, e.center.x, 1e-3, "centre x at zoom $k")
                assertEquals(ref.center.y, e.center.y, 1e-3, "centre y at zoom $k")
                assertEquals(ref.rx, e.rx, 1e-3, "rx at zoom $k")
                assertEquals(ref.ry, e.ry, 1e-3, "ry at zoom $k")
            }
        }
        // A circle has no rotation to find, and the recogniser answers 0.0 exactly for one.
        assertEquals(0.0, first!!.rotation, "a hand-drawn circle came back rotated")
    }

    // ── 9 + 10. the resize ────────────────────────────────────────────────────────────────────

    @Test
    fun resizeIsHorizontalTravelThroughTheSwatchConstant() {
        val s = circle()
        val h = heldCircle(s, density = 1f)
        val shape = assertIs<Shape.Ellipse>(assertIs<HoldToShape.State.Shaping>(h.state).shape)
        val perDoubling = HoldToShape.SIZE_PER_DOUBLING_DP

        // 2^(160/160) = 2, 2^(320/160) = 4, 2^(-160/160) = 1/2: compared as whole Shapes, so the
        // scale factor is pinned exactly and not to a tolerance.
        h.resize(perDoubling)
        assertEquals(scale(shape, 2.0), (h.state as HoldToShape.State.Shaping).shape, "160 px is not a doubling")
        h.resize(2f * perDoubling)
        assertEquals(scale(shape, 4.0), (h.state as HoldToShape.State.Shaping).shape, "320 px is not two doublings")
        h.resize(-perDoubling)
        assertEquals(scale(shape, 0.5), (h.state as HoldToShape.State.Shaping).shape, "-160 px is not a halving")

        // 2^(10000/160) = 2^62.5, so the clamp is load-bearing and not decorative.
        h.resize(10_000f)
        assertEquals(scale(shape, HoldToShape.MAX_SCALE.toDouble()), (h.state as HoldToShape.State.Shaping).shape)
        h.resize(-10_000f)
        assertEquals(scale(shape, HoldToShape.MIN_SCALE.toDouble()), (h.state as HoldToShape.State.Shaping).shape)

        // A number that is not a number is ignored outright: the outline comes back unchanged, and
        // it comes back - never empty, never a crash.
        h.resize(perDoubling)
        val kept = (h.state as HoldToShape.State.Shaping).outline
        assertTrue(kept.isNotEmpty())
        assertSame(kept, h.resize(Float.NaN), "NaN resize must leave the outline exactly as it was")
        assertSame(kept, h.resize(Float.POSITIVE_INFINITY), "an infinite resize must leave the outline as it was")
        assertSame(kept, h.resize(Float.NEGATIVE_INFINITY))

        // And the feel still survives after all of that.
        val out = h.lift()
        assertEquals(s.size, out.size)
        for (i in s.indices) {
            assertSameChannel(s[i].pressure, out[i].pressure, "pressure at $i after a resize")
            assertSameChannel(s[i].tilt, out[i].tilt, "tilt at $i after a resize")
            assertSameChannel(s[i].azimuth, out[i].azimuth, "azimuth at $i after a resize")
            assertSameChannel(s[i].barrel, out[i].barrel, "barrel at $i after a resize")
            assertEquals(s[i].timeMs, out[i].timeMs, "time at $i after a resize")
        }
    }

    @Test
    fun theResizeConstantIsTheBrushSwatchsOwn() {
        assertEquals(
            SizeOpacityDrag.SIZE_PER_DOUBLING_DP,
            HoldToShape.SIZE_PER_DOUBLING_DP,
            "the brush swatch and a held shape have drifted apart",
        )
        val s = circle()
        val h = heldCircle(s, density = 3f)
        val shape = assertIs<Shape.Ellipse>(assertIs<HoldToShape.State.Shaping>(h.state).shape)
        // 160 dp at density 3 is 480 screen px, which is the same doubling.
        h.resize(HoldToShape.SIZE_PER_DOUBLING_DP * 3f)
        assertEquals(scale(shape, 2.0), (h.state as HoldToShape.State.Shaping).shape, "480 screen px is not one doubling at density 3")
    }

    // ── 11. resize needs a shape ───────────────────────────────────────────────────────────────

    @Test
    fun resizeOnlyMeansSomethingWhileShaping() {
        val line = settledLine()
        val idle = HoldToShape(density = 1f)
        for (p in line) idle.add(p)
        assertTrue(idle.resize(1000f).isEmpty(), "an empty list means there is no outline to draw")
        assertSame(HoldToShape.State.Idle, idle.state, "a resize from Idle must not move the state")
        assertEquals(line.size, idle.lift().size, "a resize from Idle must not disturb the stroke")

        val scribble = StrokeMaker.scribble(3)
        val abandoned = HoldToShape(density = 1f)
        for (p in scribble) abandoned.add(p)
        abandoned.tick(scribble.last().timeMs + HoldToShape.HOLD_MS)
        assertIs<HoldToShape.State.Waiting>(abandoned.state)
        assertTrue(abandoned.resize(1000f).isEmpty(), "a Waiting stroke has no outline to scale")
        assertIs<HoldToShape.State.Waiting>(abandoned.state, "a resize from Waiting must not move the state")
        abandoned.add(PenSample(scribble.last().x + 20f, scribble.last().y, scribble.last().timeMs + 10.0))
        assertIs<HoldToShape.State.Abandoned>(abandoned.state)
        assertTrue(abandoned.resize(1000f).isEmpty(), "an abandoned stroke has no outline to scale")
        assertIs<HoldToShape.State.Abandoned>(abandoned.state, "a resize from Abandoned must not move the state")
        assertEquals(scribble.size + 1, abandoned.lift().size, "an abandoned stroke is still the original")
    }

    // ── 12. horizontal resizes, vertical abandons ──────────────────────────────────────────────

    @Test
    fun horizontalResizesAndVerticalDominanceAbandons() {
        val s = settledLine()
        val hold = s.last()
        val tol = HoldToShape.STILL_DP
        // One test, because the TIE is the case a builder gets wrong and it is the one
        // SizeOpacityDrag already had to settle: abs(dx) >= abs(dy) gives the drag the win.
        for (c in listOf(
            ResizeCase(20f, 5f, false),   // clearly horizontal, 20.6 px away
            ResizeCase(20f, 20f, false),  // the tie, and a drag in intent: 28.3 px away
            ResizeCase(2f, 5f, false),    // vertical-dominant but only 5.4 px, inside the tolerance
            ResizeCase(5f, 20f, true),    // vertical-dominant and 20.6 px away
            ResizeCase(0f, 40f, true),    // straight down, 40 px away
        )) {
            val h = HoldToShape(density = 1f)
            for (p in s) h.add(p)
            h.tick(hold.timeMs + HoldToShape.HOLD_MS)
            val shape = assertIs<Shape.Line>(assertIs<HoldToShape.State.Shaping>(h.state).shape)
            h.add(PenSample(x = hold.x + c.dx, y = hold.y + c.dy, timeMs = hold.timeMs + 10.0))
            val why = "movement (${c.dx}, ${c.dy}), ${hypot(c.dx, c.dy)} px from the hold, tolerance $tol"
            if (c.abandons) {
                assertIs<HoldToShape.State.Abandoned>(h.state, why)
                assertEquals(s.size + 1, h.lift().size, "$why: and the lift is the original stroke")
            } else {
                val scaling = assertIs<HoldToShape.State.Shaping>(h.state, why)
                val factor = 2.0.pow(c.dx.toDouble() / HoldToShape.SIZE_PER_DOUBLING_DP)
                assertEquals(scale(shape, factor), scaling.shape, "$why: resized by the wrong amount")
            }
        }
    }

    // ── 13. the fill pen is off ────────────────────────────────────────────────────────────────

    @Test
    fun theFillPenIsOff() {
        val s = circle()
        val off = HoldToShape(density = 1f, engine = ENGINE_FILL)
        assertFalse(off.enabled, "the fill pen is on")
        for (p in s) {
            off.add(p)
            assertSame(HoldToShape.State.Idle, off.state, "the fill pen shaped a stroke")
        }
        off.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        assertSame(HoldToShape.State.Idle, off.state, "the fill pen shaped on a tick")
        assertTrue(off.resize(1000f).isEmpty())
        val out = off.lift()
        assertEquals(s.size, out.size, "the fill pen lost a sample")
        for (i in s.indices) assertSame(s[i], out[i], "the fill pen copied sample $i")

        val on = HoldToShape(density = 1f, engine = "stamp")
        assertTrue(on.enabled)
        for (p in s) on.add(p)
        on.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        assertIs<HoldToShape.State.Shaping>(on.state, "the same stroke with a stamp pen is not a shape")
    }

    // ── 14. garbage in ─────────────────────────────────────────────────────────────────────────

    @Test
    fun anUnplaceableSampleIsDroppedAndCounted() {
        val s = circle()
        var calls = 0
        var captured: List<PenSample> = emptyList()
        val h = HoldToShape(
            density = 1f,
            recognize = { pts, _ ->
                calls++
                captured = pts
                Shape.Line(Pt(0.0, 0.0), Pt(1.0, 0.0))
            },
        )
        h.add(PenSample(x = Float.NaN, y = 0f, timeMs = 0.0))
        h.add(PenSample(x = 0f, y = 0f, timeMs = Double.NaN))
        assertEquals(2, h.droppedSamples, "two unplaceable samples were not both counted")
        for (p in s) h.add(p)
        h.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        assertEquals(1, calls)
        // "Dropped" proven rather than assumed: the recogniser's own argument holds neither one.
        assertEquals(s.size, captured.size, "a dropped sample reached the recogniser")
        assertTrue(captured.all { it.isPlaceable }, "a dropped sample reached the recogniser")
        assertEquals(s.size, h.lift().size, "a dropped sample cost the stroke a good sample")
    }

    // ── 15. guard values ───────────────────────────────────────────────────────────────────────

    @Test
    fun guardValuesCannotPoisonTheClass() {
        val s = settledLine()
        val tol = HoldToShape.STILL_DP
        // A tolerance of 0 would restart the clock on every sample and the stroke would never
        // become a shape: the failure is silent, which is exactly what the guard is for.
        for (d in listOf(0f, -1f, Float.NaN)) {
            val h = HoldToShape(density = d)
            for (p in s) h.add(p)
            h.add(PenSample(x = s[16].x + tol - 1f, y = 0f, timeMs = 200.0))
            h.tick(160.0 + HoldToShape.HOLD_MS)
            assertIs<HoldToShape.State.Shaping>(h.state, "density $d was not read as 1")
        }
        // A zero zoom must not stop a stroke from ever being a shape, either.
        val zoomed = HoldToShape(density = 1f, screenPerDoc = 0f)
        for (p in s) zoomed.add(p)
        zoomed.tick(160.0 + HoldToShape.HOLD_MS)
        assertIs<HoldToShape.State.Shaping>(zoomed.state, "a zero zoom was not read as 1")
    }

    // ── the property this row exists for ───────────────────────────────────────────────────────

    @Test
    fun aShapedStrokeCostsOneUndoStepAndNoMore() {
        val s = circle()
        val h = HoldToShape(density = 1f)
        val board = Layer()
        val before = board.tile
        for (p in s) {
            h.add(p) // R39: drawn as usual, and it stays in the engine's uncommitted buffer.
            board.draw(listOf(p))
        }
        h.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        val shown = assertIs<HoldToShape.State.Shaping>(h.state).outline

        // What the person was shown is what gets committed, by reference and not by a copy.
        val feed = h.lift()
        assertSame(shown, feed, "the committed list is not the one that was on screen")

        board.cancelStroke() // the rough stroke's buffer is thrown away
        assertTrue(board.buffer.isEmpty(), "the rough stroke was left in the buffer to be committed again")
        board.draw(feed)     // and the SAME path runs again on the lifted samples
        board.endStroke()    // ONE commit

        assertEquals(1, board.cancelStrokeCalls)
        assertEquals(1, board.endStrokeCalls, "the gesture committed more than one stroke")
        assertEquals(1, board.undo.undoDepth, "a shaped stroke must cost exactly one undo")

        // The board holds the SHAPED stroke, and one undo puts the board back exactly as it was.
        val rough = Layer()
        rough.draw(s)
        rough.endStroke()
        assertNotEquals(rough.tile, board.tile, "the board holds the rough stroke, not the shape")
        assertEquals(1, board.undo.undoDepth)
        board.undo()
        assertEquals(before, board.tile, "one undo did not leave the board as it was")
        assertEquals(0, board.undo.undoDepth, "there was a second undo step hiding somewhere")
        assertTrue(board.undo.canRedo)
    }

    @Test
    fun aRoughStrokeCostsOneUndoStepToo() {
        // The control for the test above: the same gesture done the way R39 forbids - the rough
        // stroke committed when the hold completes AND the shaped one on the lift. Two steps, and
        // one undo is not enough, which is what "undo the shape and find the rough stroke
        // underneath" looks like from the inside.
        val s = circle()
        val h = HoldToShape(density = 1f)
        val board = Layer()
        for (p in s) {
            h.add(p)
            board.draw(listOf(p))
        }
        board.endStroke() // <-- the extra step
        h.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        assertIs<HoldToShape.State.Shaping>(h.state)
        board.draw(h.lift())
        board.endStroke()

        assertEquals(2, board.undo.undoDepth, "the control did not produce two undo steps")
        board.undo()
        assertTrue(board.tile != null, "one undo was enough, so the control proves nothing")
        assertEquals(1, board.undo.undoDepth)
        board.undo()
        assertEquals(null, board.tile, "two undos did not clear the board")
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    /** 40 samples 3.6 px apart, so the anchor moves on every second one. See the class note. */
    private fun movingStroke(): List<PenSample> =
        List(40) { PenSample(x = it * 3f, y = it * 2f, timeMs = it * 16.0) }

    /** 17 samples 2 px apart, ending on the anchor at densities 1 and 2. See the class note. */
    private fun settledLine(): List<PenSample> =
        List(17) { PenSample(x = it * 2f, y = 0f, timeMs = it * 10.0) }

    private fun circle(): List<PenSample> =
        StrokeMaker.ellipse(cx = 300.0, cy = 300.0, rx = 120.0, ry = 120.0, rotationDeg = 0.0, turns = 1.0, seed = 7)

    /** n samples 1.5 px apart along +x, 4 ms apart, so the still clock never falls more than 16 ms behind. */
    private fun longStroke(n: Int): List<PenSample> =
        List(n) { PenSample(x = it * 1.5f, y = 0f, timeMs = it * 4.0) }

    /** Fed a stroke and held past [HoldToShape.HOLD_MS]. `stillSince` is at or before its last sample. */
    private fun heldCircle(s: List<PenSample>, density: Float): HoldToShape {
        val h = HoldToShape(density = density)
        for (p in s) h.add(p)
        h.tick(s.last().timeMs + HoldToShape.HOLD_MS)
        return h
    }

    /**
     * An INDEPENDENT copy of the scale-about-the-centre rule, written the same way the maths is
     * written so the agreement is bit-exact. The test is not allowed to ask the class to compute
     * its own expectation.
     */
    private fun scale(s: Shape, f: Double): Shape {
        fun q(p: Pt) = Pt(p.x * f, p.y * f)
        return when (s) {
            is Shape.Line -> Shape.Line(q(s.a), q(s.b))
            is Shape.Ellipse -> Shape.Ellipse(q(s.center), s.rx * f, s.ry * f, s.rotation)
            is Shape.Arc -> Shape.Arc(q(s.center), s.radius * f, s.startAngle, s.sweep)
            is Shape.Polygon -> Shape.Polygon(s.corners.map(::q))
        }
    }

    /** NaN is a legal value on a channel the pen has no sensor for, and it must stay NaN. */
    private fun assertSameChannel(expected: Float, actual: Float, what: String) {
        if (expected.isNaN() || actual.isNaN()) {
            assertTrue(expected.isNaN() && actual.isNaN(), "$what: $expected vs $actual")
        } else {
            assertEquals(expected, actual, what)
        }
    }

    /** One row of the Decision 10 table: a movement from the hold point, and what it must do. */
    private class ResizeCase(val dx: Float, val dy: Float, val abandons: Boolean)

    /**
     * The R39 caller, modelled in as few words as the protocol has: the rough stroke goes into an
     * UNCOMMITTED buffer while the pen is down, and on the lift that buffer is cancelled and exactly
     * one stroke is committed through the same path. The layer is a single tile, so undoing one step
     * restores the board exactly or not at all.
     */
    private class Layer {
        /** The layer's one tile, or null when nothing has ever been drawn on it. */
        var tile: String? = null
        val undo = UndoLog<String>(1L shl 20, { it.length.toLong() }, { })

        /** The engine's stroke buffer: what is drawn but not committed. */
        var buffer: List<PenSample> = emptyList()
        var endStrokeCalls = 0
        var cancelStrokeCalls = 0

        fun draw(samples: List<PenSample>) {
            buffer = buffer + samples
        }

        /** `GlPaintEngine.cancelStroke()`: discards the uncommitted buffer, and pushes NOTHING. */
        fun cancelStroke() {
            buffer = emptyList()
            cancelStrokeCalls++
        }

        /** `GlPaintEngine.endStroke()`: commits the buffer as one `UndoLog.Step`, and consumes it. */
        fun endStroke() {
            endStrokeCalls++
            val after = buffer.joinToString("|") { "${it.x},${it.y}" }
            val before = tile
            tile = after
            buffer = emptyList()
            undo.push(UndoLog.Step(listOf(UndoLog.TileChange("layer-0", 0L, before, after))))
        }

        /** Undo the newest step, putting its `before` back the way the engine does. */
        fun undo() {
            val step = undo.undo() ?: return
            tile = step.changes.single().before
        }
    }
}
