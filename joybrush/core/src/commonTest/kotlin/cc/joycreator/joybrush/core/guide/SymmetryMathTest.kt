package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The symmetry guide's pure half: where the copies are, and what each copy does to the pen.
 *
 * Every number in here is hand-derived, not read back out of the implementation: a mirror of a
 * point is a point, a radial copy is a point at a known angle about a known middle, and the
 * channels are the ones the pen actually has. A test that computed its expectation with the
 * implementation's own formula would agree with a wrong implementation.
 */
class SymmetryMathTest {

    /** A sample with every channel filled in, so "only some channels moved" is a real check. */
    private fun ps(x: Float, y: Float) =
        PenSample(x, y, 100.0, 0.5f, 0.4f, 0.25f, 0.75f, Tool.STYLUS, false)

    /** An angle folded into -PI..PI, so two angles can be compared without caring how they went round. */
    private fun wrap(a: Double): Double {
        var r = (a + PI) % (2 * PI)
        if (r < 0) r += 2 * PI
        return r - PI
    }

    /** A difference of two LINE angles folded into -PI/2..PI/2: a line's angle is only known modulo PI. */
    private fun wrapPi(a: Double): Double {
        var r = a % PI
        if (r > PI / 2) r -= PI
        if (r < -PI / 2) r += PI
        return r
    }

    /** The one copy that mirrors about the line at [axis], or null when there is not one. */
    private fun copyWithAxis(cs: List<SymmetryCopy>, axis: Double): SymmetryCopy? =
        cs.singleOrNull { c ->
            c.mirrored && c.mirrorAxis != null && abs(wrapPi(c.mirrorAxis - axis)) < 1e-9
        }

    /**
     * The property EVERY copy must have, whatever it is:
     *
     * - it says it is a mirror exactly when its map reverses the handedness, `det(L) < 0`;
     * - and when it is a mirror, the axis it names is the axis its map really reflects about.
     *
     * The axis of a reflection with linear part L is `0.5·atan2(xy, xx)`: a reflection about the
     * line at angle φ has L = [[cos2φ, sin2φ], [sin2φ, −cos2φ]], and `atan2(sin2φ, cos2φ) = 2φ`.
     * A line's angle is only known modulo PI, so the two are compared modulo PI.
     */
    private fun assertEachCopyKnowsItself(cs: List<SymmetryCopy>, what: String) {
        for (c in cs) {
            val det = c.xx * c.yy - c.xy * c.yx
            assertEquals(
                c.mirrored, det < 0.0,
                "$what: the copy says it is ${if (c.mirrored) "a mirror" else "a turn"} but det = $det: $c",
            )
            if (c.mirrored) {
                val axis = assertNotNull(c.mirrorAxis, "$what: a mirror with no axis: $c")
                val want = 0.5 * atan2(c.xy, c.xx)
                assertTrue(
                    abs(wrapPi(axis - want)) < 1e-9,
                    "$what: the copy mirrors about $axis, but its own map reflects about $want: $c",
                )
            } else {
                assertNull(c.mirrorAxis, "$what: a turn that names an axis: $c")
            }
        }
    }

    // ── the mirror ─────────────────────────────────────────────────────────────

    @Test
    fun aMirrorAcrossAVerticalAHorizontalAndAFortyFiveDegreeLine() {
        // A vertical line at x = 50: the mirror of (60, 70) is (40, 70).
        val vertical = Guide.Symmetry(Pt(50.0, 50.0), Pt(50.0, 150.0), count = 2)
        val vc = SymmetryMath.copies(vertical)
        assertEquals(1, vc.size, "$vc")
        val v = SymmetryMath.apply(ps(60f, 70f), vc[0], true)
        assertEquals(40f, v.x, 1e-3f, "x=${v.x}")
        assertEquals(70f, v.y, 1e-3f, "y=${v.y}")

        // A horizontal line at y = 50: the mirror of (60, 70) is (60, 30).
        val horizontal = Guide.Symmetry(Pt(50.0, 50.0), Pt(150.0, 50.0), count = 2)
        val hc = SymmetryMath.copies(horizontal)
        assertEquals(1, hc.size, "$hc")
        val h = SymmetryMath.apply(ps(60f, 70f), hc[0], true)
        assertEquals(60f, h.x, 1e-3f, "x=${h.x}")
        assertEquals(30f, h.y, 1e-3f, "y=${h.y}")

        // A 45° line through the origin: (60, 70) goes to (70, 60).
        val diagonal = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 100.0), count = 2)
        val dc = SymmetryMath.copies(diagonal)
        assertEquals(1, dc.size, "$dc")
        val d = SymmetryMath.apply(ps(60f, 70f), dc[0], true)
        assertEquals(70f, d.x, 1e-3f, "x=${d.x}")
        assertEquals(60f, d.y, 1e-3f, "y=${d.y}")
    }

    @Test
    fun theMirrorIsAnInvolution() {
        // A line that is neither horizontal nor vertical, so a wrong sign in the map shows up.
        val g = Guide.Symmetry(Pt(50.0, 50.0), Pt(150.0, 90.0), count = 2)
        val c = SymmetryMath.copies(g)[0]
        val p = ps(13f, 77f)
        val once = SymmetryMath.apply(p, c, true)
        val twice = SymmetryMath.apply(once, c, true)
        assertTrue(abs(twice.x - p.x) < 1e-3f, "x came back as ${twice.x}, not ${p.x}")
        assertTrue(abs(twice.y - p.y) < 1e-3f, "y came back as ${twice.y}, not ${p.y}")
    }

    // ── the radial copies ──────────────────────────────────────────────────────

    @Test
    fun radialCopiesLandOnTheExpectedPointsAndAnglesAboutTheMiddle() {
        for (n in listOf(3, 4, 8)) {
            // The line is (0,0)–(100,0), so the middle is (50, 0) and the point below is
            // 100 px to its right — straight along the line, at angle 0.
            val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = n)
            val cs = SymmetryMath.copies(g)
            assertEquals(n - 1, cs.size, "count $n has ${cs.size} copies")
            val p = ps(150f, 0f)
            val angles = ArrayList<Double>()
            for (c in cs) {
                val q = SymmetryMath.apply(p, c, true)
                val dx = (q.x - 50f).toDouble()
                val dy = q.y.toDouble()
                val r = hypot(dx, dy)
                assertTrue(abs(r - 100.0) < 1e-2, "count $n: the copy is $r px from the middle, not 100")
                angles.add(atan2(dy, dx))
            }
            // One copy at every 2PI/N but the original's own angle.
            for (k in 1 until n) {
                val want = 2.0 * PI * k / n
                assertTrue(angles.any { abs(wrap(it - want)) < 1e-6 }, "count $n has no copy at $want rad")
            }
            // The original is not one of the copies.
            assertTrue(angles.none { abs(wrap(it)) < 1e-9 }, "count $n repeats the original")
        }
    }

    @Test
    fun theThreeRadialCopiesLandOnTheExpectedPoints() {
        val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 3)
        val cs = SymmetryMath.copies(g)
        assertEquals(2, cs.size)
        // 120° about (50, 0) from (150, 0): (50 - 50, 100·sin120°) = (0, 86.6025).
        val first = SymmetryMath.apply(ps(150f, 0f), cs[0], true)
        assertTrue(abs(first.x - 0f) < 1e-2f, "the 120° copy is at x=${first.x}")
        assertTrue(abs(first.y - 86.60254f) < 1e-2f, "the 120° copy is at y=${first.y}")
        // 240°: (0, -86.6025).
        val second = SymmetryMath.apply(ps(150f, 0f), cs[1], true)
        assertTrue(abs(second.x - 0f) < 1e-2f, "the 240° copy is at x=${second.x}")
        assertTrue(abs(second.y + 86.60254f) < 1e-2f, "the 240° copy is at y=${second.y}")
    }

    @Test
    fun kaleidoAddsAMirroredCopyOfEveryRadialCopy() {
        val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 3, kaleido = true)
        val cs = SymmetryMath.copies(g)
        // Two radial copies plus three mirrored ones, beside the original lane: six lanes in all.
        assertEquals(5, cs.size, "$cs")
        assertEquals(3, cs.count { it.mirrored }, "three of the copies are mirrors")
        assertEquals(2, cs.count { !it.mirrored }, "two of the copies are plain turns")
    }

    // ── what every copy knows about itself ─────────────────────────────────────

    @Test
    fun everyCopyKnowsWhetherItIsAMirrorAndWhichLineItMirrorsAbout() {
        // A plain mirror: the guide's own line, and nothing else.
        val plain = SymmetryMath.copies(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 2))
        assertEquals(1, plain.size, "$plain")
        assertEachCopyKnowsItself(plain, "a plain mirror")
        assertEquals(0.0, plain[0].mirrorAxis!!, 1e-12, "the plain mirror is about the guide's own line")

        // A radial set: every copy is a turn, so no copy names an axis.
        val radial = SymmetryMath.copies(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 5))
        assertEquals(4, radial.size, "$radial")
        assertEachCopyKnowsItself(radial, "a radial set")

        // A kaleidoscope set: the mirrored copies have their OWN axes, and each one is the axis
        // its own map really reflects about — the guide's line turned by HALF the turn.
        val kaleido = SymmetryMath.copies(
            Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 5, kaleido = true),
        )
        assertEquals(9, kaleido.size, "$kaleido")
        assertEachCopyKnowsItself(kaleido, "a kaleidoscope set")
        val axes = kaleido.filter { it.mirrored }.map { it.mirrorAxis!! }
        assertEquals(5, axes.size, "five of the copies are mirrors")
        for (k in 0 until 5) {
            val want = PI * k / 5.0
            assertTrue(
                axes.any { abs(wrapPi(it - want)) < 1e-9 },
                "no copy mirrors about $want: $axes",
            )
        }
    }

    @Test
    fun aKaleidoCopyMirrorsAboutItsOwnAxisAndNotTheGuidesLine() {
        // The guide is the line (0,0)–(100,0): the middle is (50, 0) and the line is the x-axis.
        val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 3, kaleido = true)
        val cs = SymmetryMath.copies(g)
        // The copy that mirrors about the line at PI/3 is the 120° turn composed with the mirror
        // about the x-axis. The point (150, 0) is ON the x-axis, so the mirror leaves it alone and
        // the turn about (50, 0) takes the 100 px it stands from the middle round to
        // (100·cos120°, 100·sin120°) = (−50, 86.60254): the point lands on (0, 86.60254).
        val at60 = assertNotNull(copyWithAxis(cs, PI / 3.0), "no copy mirrors about PI/3: $cs")
        val first = SymmetryMath.apply(ps(150f, 0f), at60, true)
        assertTrue(abs(first.x - 0f) < 1e-3f, "the copy at PI/3 is at x=${first.x}")
        assertTrue(abs(first.y - 86.60254f) < 1e-3f, "the copy at PI/3 is at y=${first.y}")
        // The copy that mirrors about 2PI/3 is the 240° turn composed with the same mirror: the
        // point lands on (0, −86.60254).
        val at120 = assertNotNull(copyWithAxis(cs, 2.0 * PI / 3.0), "no copy mirrors about 2PI/3: $cs")
        val second = SymmetryMath.apply(ps(150f, 0f), at120, true)
        assertTrue(abs(second.x - 0f) < 1e-3f, "the copy at 2PI/3 is at x=${second.x}")
        assertTrue(abs(second.y + 86.60254f) < 1e-3f, "the copy at 2PI/3 is at y=${second.y}")
        // The two are different maps: a turn composed with a mirror is not the same mirror twice.
        assertTrue(
            abs(at60.tx - at120.tx) > 1e-6 || abs(at60.ty - at120.ty) > 1e-6,
            "the two kaleidoscope copies are the same map",
        )
    }

    @Test
    fun aKaleidoCopyTurnsTheLeanAndTheTwistAboutItsOwnAxis() {
        // The same guide: the line (0,0)–(100,0), three lanes a side with the mirror on top.
        val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 3, kaleido = true)
        val cs = SymmetryMath.copies(g)
        val at60 = assertNotNull(copyWithAxis(cs, PI / 3.0), "no copy mirrors about PI/3: $cs")
        // The copy mirrors about the line at PI/3, so a lean of 0.25 rad is reflected about that
        // line: 2·(PI/3) − 0.25.
        val s = ps(150f, 0f).copy(azimuth = 0.25f, barrel = 0.75f)
        val out = SymmetryMath.apply(s, at60, true)
        assertTrue(
            abs(wrap(out.azimuth.toDouble() - (2.0 * PI / 3.0 - 0.25))) < 1e-4,
            "the lean went to ${out.azimuth}",
        )
        // The barrel is the tip's direction (the tracker hands it back as it stands), so it is
        // reflected the same way: 2·(PI/3) − 0.75.
        assertTrue(
            abs(wrap(out.barrel.toDouble() - (2.0 * PI / 3.0 - 0.75))) < 1e-4,
            "the twist went to ${out.barrel}",
        )
    }

    // ── rubbish in ─────────────────────────────────────────────────────────────

    @Test
    fun countOneHasNoCopiesCountTwoHasOneMirrorAndRubbishHasNone() {
        assertTrue(SymmetryMath.copies(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 1)).isEmpty())
        val two = SymmetryMath.copies(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 2))
        assertEquals(1, two.size, "$two")
        assertTrue(two[0].mirrored, "the one copy of a count of 2 is a mirror")
        assertEquals(0.0, two[0].rotation, "a plain mirror does not turn")
        assertNotNull(two[0].mirrorAxis, "a mirror knows its own line")

        // A line with no direction has no copies at all.
        assertTrue(
            SymmetryMath.copies(Guide.Symmetry(Pt(50.0, 50.0), Pt(50.0, 50.0), count = 4)).isEmpty(),
            "a dot is not a line",
        )
        // And neither has a point that is not a number.
        assertTrue(
            SymmetryMath.copies(Guide.Symmetry(Pt(Double.NaN, 0.0), Pt(100.0, 0.0), count = 4)).isEmpty(),
            "NaN is not a point",
        )
        assertTrue(
            SymmetryMath.copies(
                Guide.Symmetry(Pt(0.0, 0.0), Pt(Double.POSITIVE_INFINITY, 0.0), count = 4),
            ).isEmpty(),
            "infinity is not a point",
        )
        // A count of zero is off, not a crash.
        assertTrue(SymmetryMath.copies(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 0)).isEmpty())
        // A count of one is off as well, mirror on top or not.
        assertTrue(
            SymmetryMath.copies(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 1, kaleido = true)).isEmpty(),
            "one lane is the original alone",
        )
    }

    @Test
    fun aCountOfTwoWithTheMirrorOnIsStillOneMirrorAndTwoLanes() {
        // The mirror on top changes NOTHING at a count of two: there is no radial copy to mirror,
        // and mirroring the mirror would land back on the original. So the guide makes TWO lanes,
        // not four, and the UI must say two.
        val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 2, kaleido = true)
        val cs = SymmetryMath.copies(g)
        assertEquals(1, cs.size, "$cs")
        assertTrue(cs[0].mirrored, "the one copy is the mirror")
        assertEquals(0.0, cs[0].rotation, "the mirror does not turn")
        assertEquals(0.0, cs[0].mirrorAxis!!, 1e-12, "the mirror is about the guide's own line")
        // And the plain mirror of the same guide is the very same map: the mirror on top adds
        // nothing at all.
        val plain = SymmetryMath.copies(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 2))
        assertEquals(plain[0], cs[0], "the mirror on top changed the mirror")
    }

    @Test
    fun aCountOverTheCapIsCappedAndNotObeyed() {
        val huge = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 1_000_000_000)
        val cs = SymmetryMath.copies(huge)
        // The cap is the whole rule: 30 lanes around, the original among them, so 29 copies.
        assertEquals(SymmetryMath.MAX_COUNT - 1, cs.size, "a billion copies made ${cs.size} lanes")
        // With the mirror on top, the lanes are 60 in all: 29 turns and 30 mirrors, the original
        // among the turns.
        val kaleido = SymmetryMath.copies(huge.copy(kaleido = true))
        assertEquals(2 * SymmetryMath.MAX_COUNT - 1, kaleido.size, "a billion copies made ${kaleido.size} lanes")
        // A count just over the cap is capped the same way.
        assertEquals(
            SymmetryMath.MAX_COUNT - 1,
            SymmetryMath.copies(huge.copy(count = SymmetryMath.MAX_COUNT + 1)).size,
            "a count of ${SymmetryMath.MAX_COUNT + 1} was not capped",
        )
        // The capped copies are still good copies.
        assertEachCopyKnowsItself(cs, "a capped radial set")
        assertEachCopyKnowsItself(kaleido, "a capped kaleidoscope set")
    }

    @Test
    fun theMiddleAndTheLineAngleAreNothingWhenTheLineIsNothing() {
        val good = Guide.Symmetry(Pt(10.0, 20.0), Pt(110.0, 40.0), count = 2)
        assertEquals(Pt(60.0, 30.0), SymmetryMath.centre(good))
        assertNotNull(SymmetryMath.lineAngle(good))
        val dot = Guide.Symmetry(Pt(10.0, 20.0), Pt(10.0, 20.0), count = 2)
        assertNull(SymmetryMath.centre(dot))
        assertNull(SymmetryMath.lineAngle(dot))
        val nan = Guide.Symmetry(Pt(Double.NaN, 20.0), Pt(110.0, 40.0), count = 2)
        assertNull(SymmetryMath.centre(nan))
        assertNull(SymmetryMath.lineAngle(nan))
    }

    // ── the channels ───────────────────────────────────────────────────────────

    @Test
    fun applyKeepsEveryOtherChannelAndLeavesABrokenSampleAlone() {
        val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 2)
        val c = SymmetryMath.copies(g)[0]
        val s = ps(30f, 40f)
        val out = SymmetryMath.apply(s, c, true)
        assertEquals(s.pressure, out.pressure, "the pressure")
        assertEquals(s.timeMs, out.timeMs, "the time")
        assertEquals(s.tilt, out.tilt, "the tilt's magnitude")
        assertEquals(s.tool, out.tool, "the tool")
        assertEquals(s.predicted, out.predicted, "the predicted flag")
        // The position did move: the mirror is about y = 0.
        assertEquals(30f, out.x, 1e-3f, "x=${out.x}")
        assertEquals(-40f, out.y, 1e-3f, "y=${out.y}")

        // A channel that is not there stays not there.
        val bare = PenSample(30f, 40f, 100.0)
        val bareOut = SymmetryMath.apply(bare, c, true)
        assertTrue(bareOut.azimuth.isNaN(), "the lean stayed absent")
        assertTrue(bareOut.barrel.isNaN(), "the twist stayed absent")
        assertTrue(bareOut.tilt.isNaN(), "the tilt stayed absent")

        // A sample that is not placeable comes back untouched.
        val broken = PenSample(Float.NaN, 40f, 100.0, 0.5f)
        assertEquals(broken, SymmetryMath.apply(broken, c, true), "a broken position")
        val brokenTime = PenSample(30f, 40f, Double.NaN, 0.5f)
        assertEquals(brokenTime, SymmetryMath.apply(brokenTime, c, true), "a broken time")
    }

    @Test
    fun withTheSymmetryOffTheLeanAndTheTwistStayButThePositionIsStillMirrored() {
        val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 2)
        val c = SymmetryMath.copies(g)[0]
        val s = ps(30f, 40f)
        val out = SymmetryMath.apply(s, c, false)
        assertEquals(s.azimuth, out.azimuth, "the lean")
        assertEquals(s.barrel, out.barrel, "the twist")
        assertEquals(30f, out.x, 1e-3f, "x=${out.x}")
        assertEquals(-40f, out.y, 1e-3f, "y=${out.y}")
    }

    @Test
    fun theAzimuthIsFlippedByAMirrorAndTurnedByARotation() {
        // A vertical mirror at x = 50 sends a lean of 0° to 180° and a lean of 45° to 135°:
        // the lean lands on the other side of the pen, the way a reflection must.
        val v = SymmetryMath.copies(Guide.Symmetry(Pt(50.0, 0.0), Pt(50.0, 100.0), count = 2))[0]
        val flat = SymmetryMath.apply(ps(60f, 0f).copy(azimuth = 0f), v, true)
        assertTrue(abs(flat.azimuth - PI.toFloat()) < 1e-4f, "the lean went to ${flat.azimuth}")
        val leaning = SymmetryMath.apply(ps(60f, 0f).copy(azimuth = (PI / 4).toFloat()), v, true)
        assertTrue(abs(leaning.azimuth - (3 * PI / 4).toFloat()) < 1e-4f, "the lean went to ${leaning.azimuth}")

        // A 90° turn adds 90° to the lean.
        val r = SymmetryMath.copies(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 4))[0]
        val turned = SymmetryMath.apply(ps(60f, 0f).copy(azimuth = 0.25f), r, true)
        assertTrue(abs(turned.azimuth - (0.25 + PI / 2).toFloat()) < 1e-4f, "the lean went to ${turned.azimuth}")
    }

    @Test
    fun anImpossibleLeanComesBackAsItWas() {
        val c = SymmetryMath.copies(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 2))[0]
        val s = ps(30f, 40f).copy(
            azimuth = Float.POSITIVE_INFINITY,
            barrel = Float.NEGATIVE_INFINITY,
        )
        val out = SymmetryMath.apply(s, c, true)
        // An infinite angle is not a direction: `cos(∞)` is NaN, and a NaN handed to the brush as a
        // lean would poison the stroke. The reading comes back as it was.
        assertTrue(out.azimuth == Float.POSITIVE_INFINITY, "the lean went to ${out.azimuth}")
        assertTrue(out.barrel == Float.NEGATIVE_INFINITY, "the twist went to ${out.barrel}")
        assertTrue(!out.azimuth.isNaN(), "the lean became NaN")
        assertTrue(!out.barrel.isNaN(), "the twist became NaN")
        // The position is still copied: only the broken channels are left alone.
        assertTrue(abs(out.y + 40f) < 1e-3f, "y=${out.y}")
    }

    @Test
    fun theTwistIsTurnedByARotationAndReflectedByAMirror() {
        val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 2)
        val c = SymmetryMath.copies(g)[0]
        val s = ps(30f, 40f).copy(barrel = 0.75f)
        val mirrored = SymmetryMath.apply(s, c, true)
        // The mirror is about the x-axis, so the tip's direction is reflected about it: −0.75.
        assertTrue(abs(mirrored.barrel + 0.75f) < 1e-5f, "the twist went to ${mirrored.barrel}")
        val r = SymmetryMath.copies(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 4))[0]
        val turned = SymmetryMath.apply(s, r, true)
        assertTrue(abs(turned.barrel - (0.75f + (PI / 2).toFloat())) < 1e-5f, "the twist went to ${turned.barrel}")
    }

    @Test
    fun theTwistIsTheTipsDirectionAndNotATwistAboutThePensOwnAxis() {
        // A guide whose line is NOT the x-axis, so a rule that merely NEGATES the barrel (a twist
        // about the pen's own axis) gives a different answer from the rule that REFLECTS it.
        // The line is the y-axis: the middle is (0, 50) and the line is at PI/2.
        val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(0.0, 100.0), count = 3, kaleido = true)
        val cs = SymmetryMath.copies(g)
        // The copy that mirrors about the line at 5PI/6 = PI/2 + PI/3 is the 120° turn composed
        // with the mirror about the y-axis.
        val at150 = assertNotNull(copyWithAxis(cs, 5.0 * PI / 6.0), "no copy mirrors about 5PI/6: $cs")
        val s = ps(30f, 0f).copy(azimuth = 0.25f, barrel = 0.75f)
        val out = SymmetryMath.apply(s, at150, true)
        // The lean is reflected about 5PI/6: 2·(5PI/6) − 0.25.
        assertTrue(
            abs(wrap(out.azimuth.toDouble() - (5.0 * PI / 3.0 - 0.25))) < 1e-4,
            "the lean went to ${out.azimuth}",
        )
        // And the barrel, which IS the tip's direction, is reflected the same way: 2·(5PI/6) − 0.75.
        assertTrue(
            abs(wrap(out.barrel.toDouble() - (5.0 * PI / 3.0 - 0.75))) < 1e-4,
            "the twist went to ${out.barrel}",
        )
        // A rule that NEGATED the barrel would have given −0.75 + 2PI/3, which is not this.
        assertTrue(
            abs(out.barrel - (-0.75f + (2.0 * PI / 3.0).toFloat())) > 1e-3f,
            "the twist was negated, not reflected: ${out.barrel}",
        )
    }

    // ── the copies that are not the identity ───────────────────────────────────

    @Test
    fun noCopyIsTheIdentityAndNoTwoCopiesAreTheSameMap() {
        // The ORACLE, and it does not repeat the implementation's own test: the identity map is
        // the one map that moves NO point at all, so a copy that leaves every probe where it was
        // IS the identity. A copy that moves one of them is not.
        val probes = listOf(Pt(123.456, -789.012), Pt(-17.5, 233.25))
        for (count in listOf(2, 3, 4, 5, 6, 7, 8)) {
            for (kaleido in listOf(false, true)) {
                val g = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = count, kaleido = kaleido)
                val cs = SymmetryMath.copies(g)
                assertTrue(cs.isNotEmpty(), "count $count kaleido $kaleido made no copies")
                for (c in cs) {
                    val moved = probes.any { p ->
                        val qx = c.xx * p.x + c.xy * p.y + c.tx
                        val qy = c.yx * p.x + c.yy * p.y + c.ty
                        abs(qx - p.x) > 1e-6 || abs(qy - p.y) > 1e-6
                    }
                    assertTrue(moved, "count $count kaleido $kaleido: the copy is the identity: $c")
                    // A copy is either a turn or a flip, never a squash: |det| is 1.
                    val det = c.xx * c.yy - c.xy * c.yx
                    assertTrue(
                        abs(abs(det) - 1.0) < 1e-9,
                        "count $count kaleido $kaleido: the copy squashes the page: det = $det: $c",
                    )
                }
                // And no two copies are the same map: two copies that are the same map draw the
                // same stroke twice.
                val keys = cs.map { listOf(it.xx, it.xy, it.yx, it.yy, it.tx, it.ty) }
                assertEquals(keys.size, keys.toSet().size, "count $count kaleido $kaleido: two copies are the same map")
            }
        }
    }

    // ── the angle the overlay will want ────────────────────────────────────────

    @Test
    fun snapAngleSnapsInsideThreeDegreesAndRefusesOutside() {
        assertEquals(0.0, SymmetryMath.snapAngle(0.0))
        assertEquals(PI / 4.0, SymmetryMath.snapAngle(PI / 4.0))
        assertEquals(PI / 2.0, SymmetryMath.snapAngle(PI / 2.0))
        assertEquals(3.0 * PI / 4.0, SymmetryMath.snapAngle(3.0 * PI / 4.0))
        // Inside three degrees it snaps to the nearest multiple of 45°.
        assertEquals(0.0, SymmetryMath.snapAngle(0.05))
        assertEquals(PI / 4.0, SymmetryMath.snapAngle(PI / 4.0 + 0.05))
        // Outside, it refuses.
        assertNull(SymmetryMath.snapAngle(0.06))
        assertNull(SymmetryMath.snapAngle(PI / 4.0 + 0.06))
        // The boundary is exact: three degrees snaps, a hair more does not. The three degrees are
        // written out here and not taken from the constant, so a rounded constant cannot pass.
        val threeDegrees = 3.0 * PI / 180.0
        assertEquals(threeDegrees, SymmetryMath.SNAP_TOLERANCE, "the tolerance is not three degrees")
        assertEquals(0.0, SymmetryMath.snapAngle(threeDegrees))
        assertNull(SymmetryMath.snapAngle(threeDegrees + 1e-9))
        // A rubbish angle is not snapped either.
        assertNull(SymmetryMath.snapAngle(Double.NaN))
        assertNull(SymmetryMath.snapAngle(Double.POSITIVE_INFINITY))
    }

    @Test
    fun aLineHasNoDirectionSoTheAngleIsFoldedOntoTheLine() {
        // A line at 180° IS the line at 0°, so the answer for it is 0 and not PI.
        assertEquals(0.0, SymmetryMath.snapAngle(PI))
        // And a line a hair below the x-axis is the line at 0°, not the one at 180°.
        assertEquals(0.0, SymmetryMath.snapAngle(-0.05))
        assertNull(SymmetryMath.snapAngle(-0.06))
        // A line at −57° is more than three degrees from every multiple of 45°: no snap.
        assertNull(SymmetryMath.snapAngle(-1.0))
        // A line just off the x-axis on the other side is the same line as one just off it here.
        assertEquals(0.0, SymmetryMath.snapAngle(PI - 0.05))
    }
}
