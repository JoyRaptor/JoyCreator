package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.FillPen
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Editing a finished ink line (JB-5.03a).
 *
 * ## Where the numbers in here come from
 *
 * The hard half of this spec is the one sentence a reviewer is waiting for: *"pick a pencil stroke
 * and the fill pen and you get the solid shape."* The expected values below are **not** what the
 * code printed. Every one is a closed form worked out here, from a model that shares no line of code
 * with the implementation:
 *
 *  - a closed loop of `n` samples on a circle of radius `r`, filled non-zero, encloses
 *    `½·n·r²·sin(2π/n)` — `n` triangles from the centre, each with two sides of length `r` and an
 *    included angle `2π/n` — and tends to `π·r²`, the area of a disc, as `n` grows;
 *  - an OPEN arc of `n` samples spanning `θ`, closed by the straight chord from its last point back
 *    to its first, encloses `½·r²·((n−1)·sin(θ/(n−1)) − sin θ)` — the same fan, with the closing
 *    triangle counted the short way round, whose signed area is `½·r²·sin(−θ)` — and tends to
 *    `½·r²·(θ − sin θ)`, the circular segment;
 *  - both areas are then measured a THIRD way, by integrating the outline's vertical slices and
 *    integrating the ideal circle's own slice lengths `2√(r² − (x − cₓ)²)` on the same grid.
 *
 * A test that only checks a shape against itself proves nothing, and a "solid shape" that is really
 * a ring of the pencil's own dabs passes both a point count and a bounding box. So the two facts
 * that separate the solid shape from a smear are asserted by name: the filled region **contains the
 * middle of the loop** (a ring of dabs does not), and **every point of the outline lies on the drawn
 * centreline** (a ring's points lie at `r ± half the pencil's width`).
 */
class StrokeEditTest {

    // ---- fixtures -----------------------------------------------------------------------------

    /** A straight line of [n] samples one document px apart, with real pressure, tilt and time. */
    private fun straightLine(n: Int) = List(n) { i ->
        PenSample(
            x = i.toFloat(),
            y = 0f,
            timeMs = 1000.0 + i * 8.0,
            pressure = 0.4f + 0.01f * (i % 5),
            tilt = 12.5f + i * 0.01f,
            azimuth = 200f,
        )
    }

    private fun pencil(samples: List<PenSample>, id: String = "line-1", smoothing: Float = 0f) =
        StrokeRecord(
            id = id,
            brushId = "pencil",
            seed = 0x5EED_1234_ABCD_0001L,
            smoothing = smoothing,
            screenPerDoc = 1f,
            samples = samples,
            colorArgb = 0xFF203040.toInt(),
            widthScale = 1f,
        )

    /**
     * A pen walking a circle or an arc, reporting one sample at each of [angles] (in radians, about
     * `(cx, cy)` at radius [r]). The angles are given by the caller rather than derived here, so the
     * vertex count of the resulting polygon is exactly what the closed forms in the class KDoc
     * assume. [pressureFrom]..[pressureTo] is the pencil's taper ramp, which a fill must ignore.
     */
    private fun arc(
        cx: Double,
        cy: Double,
        r: Double,
        angles: List<Double>,
        pressureFrom: Float = 1f,
        pressureTo: Float = 1f,
    ): List<PenSample> = angles.mapIndexed { i, a ->
        PenSample(
            x = (cx + r * cos(a)).toFloat(),
            y = (cy + r * sin(a)).toFloat(),
            timeMs = 2000.0 + i * 6.0,
            pressure = pressureFrom + (pressureTo - pressureFrom) * i / (angles.size - 1),
            tilt = 30f,
        )
    }

    /** The fill shape of a record, exactly as the fill pen (JB-1.08a) draws it. */
    private fun filledBy(r: StrokeRecord): List<Pt> =
        FillPen.outline(r.samples, r.smoothing, r.screenPerDoc)

    // ---- geometry written here, so nothing is checked against itself ---------------------------

    private fun shoelace(p: List<Pt>): Double {
        var a = 0.0
        for (i in p.indices) {
            val q = p[i]
            val s = p[(i + 1) % p.size]
            a += q.x * s.y - s.x * q.y
        }
        return abs(a) / 2.0
    }

    /**
     * The area of a simple closed polygon by integrating its vertical slices: the midpoint rule on
     * alternating inside/outside intervals. A different algorithm from the shoelace sum, and one that
     * would notice a shape the shoelace and a bounding box both call "closed".
     */
    private fun areaBySlices(p: List<Pt>, step: Double): Double {
        val x0 = p.minOf { it.x }
        val x1 = p.maxOf { it.x }
        var total = 0.0
        var x = x0 + step / 2
        while (x < x1) {
            val crossings = ArrayList<Double>()
            for (i in p.indices) {
                val a = p[i]
                val b = p[(i + 1) % p.size]
                if ((a.x > x) != (b.x > x)) {
                    crossings.add(a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x))
                }
            }
            crossings.sort()
            var i = 0
            while (i + 1 < crossings.size) {
                total += (crossings[i + 1] - crossings[i]) * step
                i += 2
            }
            x += step
        }
        return total
    }

    /** The same integral for the IDEAL disc — a model that never saw the polygon. */
    private fun discAreaBySlices(cx: Double, r: Double, step: Double): Double {
        var total = 0.0
        var x = cx - r + step / 2
        while (x < cx + r) {
            val half = r * r - (x - cx) * (x - cx)
            if (half > 0) total += 2 * sqrt(half) * step
            x += step
        }
        return total
    }

    /** The furthest the outline strays from the circle it was traced on. */
    private fun offCircle(p: List<Pt>, cx: Double, cy: Double, r: Double): Double =
        p.maxOf { abs(hypot(it.x - cx, it.y - cy) - r) }

    private fun contains(p: List<Pt>, at: Pt): Boolean {
        var inside = false
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            if ((a.y > at.y) != (b.y > at.y)) {
                val x = a.x + (at.y - a.y) / (b.y - a.y) * (b.x - a.x)
                if (at.x < x) inside = !inside
            }
        }
        return inside
    }

    private fun distanceToSegment(q: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(q.x - a.x, q.y - a.y)
        val t = (((q.x - a.x) * dx + (q.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(q.x - (a.x + t * dx), q.y - (a.y + t * dy))
    }

    private fun assertSame(what: String, expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, was $actual (±$tolerance)")
    }

    // ---- 1. a drag moves the grab and falls off along the line --------------------------------

    @Test
    fun aDragMovesTheGrabAndFallsOffAlongTheLine() {
        val r = pencil(straightLine(100))
        val moved = StrokeEdit.reshape(r, grabX = 50f, grabY = 0f, dx = 10f, dy = 0f, radius = 20f)

        // The grabbed sample moves the whole offset…
        assertEquals(60f, moved.samples[50].x, "the grabbed sample moves the whole offset")
        // …a sample exactly at the radius does not move at all (the falloff reaches 0 there)…
        assertEquals(30f, moved.samples[30].x, "20 px of arc is the radius")
        assertEquals(70f, moved.samples[70].x)
        // …and one a pixel further out is just as still: the falloff is ARC LENGTH, not index.
        assertEquals(29f, moved.samples[29].x)
        assertEquals(71f, moved.samples[71].x)

        // The weights are the closed form w = (1 − (s/R)²)² — 1 at the grab, 0 at the radius — and
        // they fall off monotonically away from it, in both directions.
        val right = (0..20).map { moved.samples[50 + it].x - (50 + it) }
        val left = (0..20).map { moved.samples[50 - it].x - (50 - it) }
        // The two sides agree to float precision, not bit for bit: each arc length is a difference of
        // two accumulated sums, and the two differences are not the same pair of floats.
        for (i in right.indices) {
            assertTrue(abs(right[i] - left[i]) < 1e-4f, "the falloff is the same on both sides, at $i: $right vs $left")
        }
        for (i in 1..20) {
            val u = i / 20f
            val k = 1f - u * u
            assertEquals(k * k * 10f, right[i], 1e-3f, "sample $i away: (1 − (s/R)²)² × the offset")
        }
        for (i in 1 until right.size) {
            assertTrue(right[i] < right[i - 1], "the weight must fall off monotonically: $right")
        }

        // Nothing but the positions moved.
        for (i in r.samples.indices) {
            assertEquals(r.samples[i].pressure, moved.samples[i].pressure, "pressure $i")
            assertEquals(r.samples[i].tilt, moved.samples[i].tilt, "tilt $i")
            assertEquals(r.samples[i].azimuth, moved.samples[i].azimuth, "azimuth $i")
            assertEquals(r.samples[i].timeMs, moved.samples[i].timeMs, "time $i")
            assertEquals(r.samples[i].tool, moved.samples[i].tool, "tool $i")
        }
        assertEquals(r.id, moved.id)
        assertEquals(r.brushId, moved.brushId)
        assertEquals(r.seed, moved.seed)
        assertEquals(r.smoothing, moved.smoothing)
        assertEquals(r.screenPerDoc, moved.screenPerDoc)
        assertEquals(r.colorArgb, moved.colorArgb)
        assertEquals(r.widthScale, moved.widthScale)
    }

    // ---- 2. the ends of the line are part of the line -----------------------------------------

    @Test
    fun aGrabAtAnEndMovesTheEnd() {
        val r = pencil(straightLine(60))
        val atStart = StrokeEdit.reshape(r, 0f, 0f, dx = -4f, dy = 6f, radius = 10f)
        assertEquals(-4f, atStart.samples[0].x, "the first sample moves the whole offset")
        assertEquals(6f, atStart.samples[0].y)
        assertTrue(abs(atStart.samples[1].x - r.samples[1].x) < 4f, "the next one follows, by less")
        assertEquals(10f, atStart.samples[10].x, "but nothing past the radius moves")

        val atEnd = StrokeEdit.reshape(r, 59f, 0f, dx = 4f, dy = 0f, radius = 10f)
        assertEquals(63f, atEnd.samples[59].x, "the last sample moves the whole offset")

        // A radius of zero is a nudge: one sample, and the line stays a line.
        val nudge = StrokeEdit.reshape(r, 30f, 0f, dx = 3f, dy = 0f, radius = 0f)
        assertEquals(33f, nudge.samples[30].x)
        for (i in r.samples.indices) {
            if (i == 30) continue
            assertEquals(r.samples[i], nudge.samples[i], "only the grabbed sample moves, and $i did not")
        }
        // A radius that is not a usable reach has the same one-sample answer.
        for (bad in listOf(Float.NaN, -1f, Float.POSITIVE_INFINITY)) {
            assertEquals(nudge.samples, StrokeEdit.reshape(r, 30f, 0f, 3f, 0f, bad).samples, "radius $bad")
        }
    }

    // ---- 3. numbers that are not numbers change nothing ---------------------------------------

    @Test
    fun aNumberThatIsNotANumberChangesNothing() {
        val r = pencil(straightLine(40))
        val broken = listOf<List<Float>>(
            listOf(Float.NaN, 0f, 1f, 0f),
            listOf(0f, Float.NaN, 1f, 0f),
            listOf(0f, 0f, Float.NaN, 0f),
            listOf(0f, 0f, 0f, Float.POSITIVE_INFINITY),
            listOf(Float.NEGATIVE_INFINITY, 0f, 1f, 0f),
            listOf(0f, 0f, Float.NEGATIVE_INFINITY, 1f),
        )
        for ((x, y, dx, dy) in broken) {
            assertEquals(r, StrokeEdit.reshape(r, x, y, dx, dy, 10f), "grab ($x, $y) drag ($dx, $dy)")
        }
        // A stroke with no samples has nothing to drag, and must not be an error.
        val empty = r.copy(samples = emptyList())
        assertEquals(empty, StrokeEdit.reshape(empty, 0f, 0f, 5f, 5f, 10f))
    }

    // ---- 4. re-weight is multiplicative and clamped --------------------------------------------

    @Test
    fun reweightIsMultiplicativeAndClamped() {
        val r = pencil(straightLine(10))
        val twice = StrokeEdit.reweight(r, 2f)
        assertEquals(2f, twice.widthScale, 1e-6f)
        assertEquals(1f, StrokeEdit.reweight(twice, 0.5f).widthScale, 1e-6f, "twice then half is once")
        assertEquals(20f, StrokeEdit.reweight(r, 1000f).widthScale, "clamped to the widest")
        assertEquals(0.05f, StrokeEdit.reweight(r, 0.0001f).widthScale, "clamped to the narrowest")
        assertEquals(0.05f, StrokeEdit.reweight(r, -1f).widthScale, "a negative factor is a very thin line")
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(r, StrokeEdit.reweight(r, bad), "a factor of $bad is not a factor")
        }
        // A record whose own weight is not a number is read as the neutral weight rather than left
        // poisoned: otherwise one bad field would make every later re-weight of that line a no-op.
        assertEquals(2f, StrokeEdit.reweight(r.copy(widthScale = Float.NaN), 2f).widthScale)
        assertEquals(20f, StrokeEdit.reweight(r.copy(widthScale = 1e30f), 1e30f).widthScale)
        // The bounds are the codec's bounds: one number, one place (R19).
        assertEquals(0.05f, StrokeEdit.MIN_WIDTH_SCALE)
        assertEquals(20f, StrokeEdit.MAX_WIDTH_SCALE)
    }

    // ---- 5. every edit is the same line, edited -----------------------------------------------

    @Test
    fun everyEditKeepsTheIdAndTheSeed() {
        val r = pencil(straightLine(30), id = "stroke-42", smoothing = 0.3f)
        val edits = listOf(
            StrokeEdit.reshape(r, 10f, 0f, 2f, 1f, 8f),
            StrokeEdit.reweight(r, 3f),
            StrokeEdit.rebrush(r, ENGINE_FILL),
            StrokeEdit.recolor(r, 0xFF00FF00.toInt()),
        )
        for (e in edits) {
            assertEquals("stroke-42", e.id, "an edit is the same line, so it keeps its id")
            assertEquals(r.seed, e.seed, "the seed is what the BrushDabber drew with (R13), and it stays")
        }
        // Re-brush changes the pen and NOTHING else: same samples, same colour, same weight.
        val reBrushed = edits[2]
        assertEquals(ENGINE_FILL, reBrushed.brushId)
        assertEquals(r.samples, reBrushed.samples)
        assertEquals(r.colorArgb, reBrushed.colorArgb)
        assertEquals(r.widthScale, reBrushed.widthScale)
        assertEquals(r.smoothing, reBrushed.smoothing)
        assertEquals(r.screenPerDoc, reBrushed.screenPerDoc)
        // Recolour changes the colour and nothing else.
        val reColoured = edits[3]
        assertEquals(0xFF00FF00.toInt(), reColoured.colorArgb)
        assertEquals(r.brushId, reColoured.brushId)
        assertEquals(r.samples, reColoured.samples)
        assertEquals(r.widthScale, reColoured.widthScale)
    }

    // ---- 6. THE HARD ONE: a pencil re-brushed to the fill pen is the solid shape it traced ----

    @Test
    fun aClosedPencilLoopReBrushedToTheFillPenBecomesTheSolidDisc() {
        val cx = 600.0
        val cy = 600.0
        val r = 120.0
        val n = 240
        // Drawn with a pencil: the pressure ramps hard from 0.08 to 1.0 (a taper), the samples are
        // 3.1 px apart (a spacing), and the stroke carries the seed its dabs were scattered with.
        val loop = pencil(
            arc(cx, cy, r, List(n) { 2.0 * PI * it / n }, pressureFrom = 0.08f, pressureTo = 1f),
        )

        val filled = StrokeEdit.rebrush(loop, ENGINE_FILL)
        assertEquals(ENGINE_FILL, filled.brushId)
        assertEquals(loop.seed, filled.seed, "the pencil's scatter is not the fill's business")
        val outline = filledBy(filled)
        assertTrue(outline.size > 600, "the shape is traced at screen resolution, got ${outline.size} points")

        // (a) The closed form: n vertices on a circle, filled non-zero, is n triangles from the
        //     centre — two sides of r, included angle 2π/n — so ½·n·r²·sin(2π/n).
        val inscribed = 0.5 * n * r * r * sin(2.0 * PI / n)
        val area = shoelace(outline)
        assertSame("area of the inscribed n-gon", inscribed, area, inscribed * 0.002)

        // (b) The model nobody implemented: a filled disc is πr², and an inscribed polygon can only
        //     be smaller. For n = 240 the shortfall is ½n·r²(1 − sin(2π/n)/(2π/n)) / πr² = 1.1·10⁻⁴,
        //     far inside a 0.2 % bound. A ring of the pencil's 8 px dabs would come out at
        //     2πr·8 = 6032, a fifth of this, and would not contain the middle of the loop at all.
        assertSame("area of a disc", PI * r * r, area, PI * r * r * 0.002)

        // (c) The same integral measured a third way, against the ideal disc's own slice lengths
        //     2√(r² − (x − cₓ)²) on the same grid. No shoelace, no polygon, no shared code.
        assertSame("slices vs shoelace", area, areaBySlices(outline, 0.5), area * 0.002)
        assertSame(
            "slices vs the ideal disc",
            discAreaBySlices(cx, r, 0.5),
            areaBySlices(outline, 0.5),
            PI * r * r * 0.003,
        )

        // (d) The two facts a smear cannot fake. A ring of dabs is an annulus: its middle is NOT
        //     filled, and its own points sit at r ± half the pencil's width, not on r.
        assertTrue(contains(outline, Pt(cx, cy)), "a solid shape contains the middle of the loop")
        assertTrue(contains(outline, Pt(663.0, 597.0)), "…and the inside of it")
        assertTrue(!contains(outline, Pt(850.0, 610.0)), "…and stops at the line that was drawn")
        assertTrue(offCircle(outline, cx, cy, r) < 0.2, "every outline point is ON the drawn line")
        assertTrue(outline.maxOf { hypot(it.x - cx, it.y - cy) } < r + 0.2, "and the fill never grows past it")

        // (e) Symmetry, which needs no area model at all: the shape was a circle, so the shape must
        //     be a circle — every outline point at the same distance from the middle.
        val radii = outline.map { hypot(it.x - cx, it.y - cy) }
        assertSame("the widest radius", radii.max(), radii.min(), 0.2)
    }

    @Test
    fun anOpenPencilStrokeReBrushedToTheFillPenIsClosedByOneStraightEdge() {
        val cx = 600.0
        val cy = 600.0
        val r = 120.0
        val n = 121
        val theta = 1.5 * PI
        // A "C": drawn as a pencil would draw it, ending 169 px from where it started.
        val open = pencil(arc(cx, cy, r, List(n) { theta * it / (n - 1) }, 1f, 0.2f))
        val outline = filledBy(StrokeEdit.rebrush(open, ENGINE_FILL))

        // (a) The polygon: a fan of (n−1) triangles from the middle, plus the CLOSING triangle
        //     counted the short way round, whose signed area is ½r²·sin(−θ):
        //     ½r²·((n−1)·sin(θ/(n−1)) − sin θ).
        val polygon = 0.5 * r * r * ((n - 1) * sin(theta / (n - 1)) - sin(theta))
        val area = shoelace(outline)
        assertSame("area of the chord-closed arc", polygon, area, polygon * 0.001)

        // (b) The continuum: the region between a θ arc and its chord is a circular segment of area
        //     ½r²(θ − sin θ). The polygon inscribes it, so it is the smaller number, by
        //     (n−1)·sin(θ/(n−1)) − θ = −2.1·10⁻⁴ in the bracket — 0.02 % of the whole, here.
        val segment = 0.5 * r * r * (theta - sin(theta))
        assertSame("area of the circular segment", segment, area, segment * 0.005)
        assertSame("slices vs shoelace", area, areaBySlices(outline, 0.5), area * 0.002)

        // The line that joins the ends is ONE straight edge, and the length a chord of that arc has
        // is 2r·sin(θ/2) = 2·120·sin(135°) = 169.7 px. Nothing of the outline lies along it, so it
        // is an edge and not a chain of resampled points.
        val first = outline.first()
        val last = outline.last()
        assertSame("chord length", 2.0 * r * sin(theta / 2), hypot(last.x - first.x, last.y - first.y), 0.2)
        val onChord = outline.count { it != first && it != last && distanceToSegment(it, last, first) < 1e-6 }
        assertEquals(0, onChord, "the closing edge is one segment, but $onChord outline points lie on it")
        assertTrue(first != last, "the first point is not repeated at the end")

        // The chord really cuts: a point inside the disc but on the far side of the chord — the
        // 90° segment between angle 270° and 360° — is not filled. (690, 540) is 108 px from the
        // middle and 30 px the wrong side of the chord, which runs from (600, 480) to (720, 600).
        assertTrue(contains(outline, Pt(560.0, 560.0)), "the middle of the C is filled")
        assertTrue(!contains(outline, Pt(690.0, 540.0)), "the segment the chord cuts off is not filled")
        assertTrue(offCircle(outline, cx, cy, r) < 0.2, "and the outline itself is the arc that was drawn")
    }

    @Test
    fun thePencilsTaperAndSpacingAreNotPartOfTheFilledShape() {
        // Two recordings of the SAME loop by two different pencils: one coarse and hard-tapered, one
        // five times finer and flat. If a re-brush smeared the pencil's dabs — or resampled the
        // recording at the new brush's spacing, or kept the taper as a width profile — these two
        // shapes would come out measurably different, and the coarse one would be a visible polygon.
        val cx = 600.0
        val cy = 600.0
        val r = 120.0
        val coarse = pencil(arc(cx, cy, r, List(180) { 2.0 * PI * it / 180 }, 0.05f, 1f), id = "coarse")
        val fine = pencil(arc(cx, cy, r, List(900) { 2.0 * PI * it / 900 }), id = "fine")
        assertTrue(coarse.samples.size * 4 < fine.samples.size, "the two pencils really do report differently")

        val a = filledBy(StrokeEdit.rebrush(coarse, ENGINE_FILL))
        val b = filledBy(StrokeEdit.rebrush(fine, ENGINE_FILL))
        val areaA = shoelace(a)
        val areaB = shoelace(b)

        assertSame("the two shapes are the same shape", areaA, areaB, PI * r * r * 0.002)
        assertSame("and it is the disc", PI * r * r, areaA, PI * r * r * 0.003)
        for ((what, outline) in listOf("coarse" to a, "fine" to b)) {
            assertTrue(offCircle(outline, cx, cy, r) < 0.2, "$what: the outline is the drawn line")
            assertTrue(contains(outline, Pt(cx, cy)), "$what: the middle of the loop is filled")
        }
        // The two recordings' pressure profiles are nothing alike, and their shapes are the same:
        // a pressure channel is a WIDTH, and a width is not part of a closed outline.
        assertTrue(coarse.samples.first().pressure != fine.samples.first().pressure)
    }

    @Test
    fun aFilledShapeReBrushedToAPencilIsItsOutline() {
        val r = 120.0
        val loop = pencil(arc(600.0, 600.0, r, List(240) { 2.0 * PI * it / 240 }))
        val filled = StrokeEdit.rebrush(loop, ENGINE_FILL)
        val backToAPencil = StrokeEdit.rebrush(filled, "pencil")

        // The other direction of R20: the same points are a centreline again, so the boundary the
        // fill had is what gets drawn — the outline stroke. Nothing was resampled to get here, which
        // is the whole reason both directions of "swappable" cost one field.
        assertEquals("pencil", backToAPencil.brushId)
        assertEquals(filled.samples, backToAPencil.samples)
        assertEquals(filled.seed, backToAPencil.seed)
        assertEquals(filledBy(filled), filledBy(backToAPencil), "the boundary is the same boundary")
        // And back to the fill pen it is the same solid shape, record and all.
        assertEquals(filled, StrokeEdit.rebrush(backToAPencil, ENGINE_FILL))
    }

    // ---- 7. R20: which brushes may draw an ink line -------------------------------------------

    @Test
    fun onlyStampAndFillCanDrawAnInkLine() {
        assertTrue(StrokeEdit.drawsInkLines("stamp"), "an ordinary pen draws an ink line")
        assertTrue(StrokeEdit.drawsInkLines(ENGINE_FILL), "the fill pen is a BRUSH (R21) and draws an ink shape")
        for (engine in listOf("smudge", "wet", "", "Stamp", "STAMP", "fill ")) {
            assertTrue(
                !StrokeEdit.drawsInkLines(engine),
                "\"$engine\" reads the pixels underneath, and an ink layer has none",
            )
        }
        assertEquals(setOf("stamp", ENGINE_FILL), StrokeEdit.INK_ENGINES)
    }

    // ---- 8. the record is still the contract JB-0.01 wrote ------------------------------------

    @Test
    fun aSixArgumentRecordStillCompilesAndHasTheDefaults() {
        val samples = straightLine(3)
        val old = StrokeRecord("s", "ink", 7L, 0.5f, 1.5f, samples)
        assertEquals(0xFF000000.toInt(), old.colorArgb, "a stroke drawn before v2 is black")
        assertEquals(1f, old.widthScale, "…and at the brush's own size")
        assertEquals(old, old.copy(), "and copying it changes nothing")
        assertEquals(old, StrokeRecord("s", "ink", 7L, 0.5f, 1.5f, samples, old.colorArgb, old.widthScale))
        // Predicted points are still refused, through every constructor.
        assertFailsWith<IllegalArgumentException> {
            StrokeRecord("s", "ink", 7L, 0.5f, 1.5f, listOf(samples.first().copy(predicted = true)))
        }
    }
}
