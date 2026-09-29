package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.fill.FillOptions
import cc.joycreator.joybrush.core.fill.FloodFill
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * JB-2.05a — the pure maths of WHICH pixels a selection covers and how much.
 *
 * EVERY EXPECTED VALUE HERE IS DERIVED, not chosen, and the derivations are in the comments. The
 * three that carry the most weight:
 *
 *  - Pixel (x, y) covers [x, x+1) x [y, y+1) and is sampled at x + (i + 0.5) / 4 for i in 0..3, so
 *    a pixel's sixteen samples sit at 0.125, 0.375, 0.625 and 0.875 of the way across it. An
 *    INTEGER-CORNERED RECTANGLE therefore has every one of a pixel's samples strictly inside or
 *    strictly outside, and its coverage is exactly 255 or exactly 0 — never in between. That is
 *    what makes the pixel counts in [theBooleanOpsCountPixelsExactly] exact.
 *  - `a * b / 255` and `a * (255 - b) / 255`, rounded to nearest, are exact for 0 and 255: 255*255
 *    is 255, 255*0 is 0, and both end products are integers, so the round is a no-op. Every
 *    assertion about the ops uses 0/255 squares and can therefore be counted on the fingers of a
 *    hand.
 *  - The scanline rule is the usual half-open one (`y0 <= sy` XOR `y1 <= sy`, and a span runs from
 *    one crossing to the next, the left included and the right excluded). That is what decides the
 *    six samples inside pixel (19, 20) of the triangle test, and it is derived there in full.
 */
class SelectionMaskTest {

    // ---- helpers ---------------------------------------------------------------------------------

    /** Pixels at FULL coverage inside [r] — the 0/255 shapes make this the same as "pixels in". */
    private fun fullCount(m: SelectionMask, r: RectPx): Int {
        var n = 0
        for (y in r.y until r.y + r.h) {
            for (x in r.x until r.x + r.w) {
                if (m.coverage(x, y) == 255) n++
            }
        }
        return n
    }

    /** Total coverage inside [r], in 255ths of a pixel, so / 255 is the area in pixels. */
    private fun coverageSum(m: SelectionMask, r: RectPx): Long {
        var s = 0L
        for (y in r.y until r.y + r.h) {
            for (x in r.x until r.x + r.w) s += m.coverage(x, y)
        }
        return s
    }

    /** A closed polygon of [steps] points on a circle, in increasing-angle order. */
    private fun circle(cx: Double, cy: Double, r: Double, steps: Int): List<Pt> {
        val out = ArrayList<Pt>(steps)
        for (i in 0 until steps) {
            val a = 2.0 * PI * i / steps
            out.add(Pt(cx + r * cos(a), cy + r * sin(a)))
        }
        return out
    }

    /**
     * A figure-8 in ONE point list: the circle of radius 40 about (-30,0) and the circle of radius
     * 40 about (30,0) each traced all the way round, both in increasing-angle order, so the lobes
     * OVERLAP and the overlap is wound twice.
     *
     * The two circles cross at (0, +/-sqrt(700)), and the list is threaded through the upper one,
     * so the junction appears as a vertex three times and the auto-closing edge is zero length. The
     * junction is written as the same expression every time, so those three vertices are the same
     * three doubles and no hairline edge is invented.
     */
    private fun figureEight(steps: Int): List<Pt> {
        val r = 40.0
        // acos(0.75) is the angle of the shared point on the LEFT circle: -30 + 40*cos = 0 exactly.
        val t1 = acos(0.75)
        val t2 = PI - t1
        val out = ArrayList<Pt>(2 * steps + 1)
        out.add(Pt(0.0, 40.0 * sin(t1)))
        for (i in 1 until steps) {
            val a = t1 + 2.0 * PI * i / steps
            out.add(Pt(-30.0 + r * cos(a), r * sin(a)))
        }
        out.add(Pt(0.0, 40.0 * sin(t1)))
        for (i in 1 until steps) {
            val a = t2 + 2.0 * PI * i / steps
            out.add(Pt(30.0 + r * cos(a), r * sin(a)))
        }
        out.add(Pt(0.0, 40.0 * sin(t1)))
        return out
    }

    // ---- 1: the integer rectangle ----------------------------------------------------------------

    /**
     * Square (10,10)-(20,20). All 100 pixels are exactly 255 and the ring around them is exactly 0,
     * because a sample of pixel (9, 10) is at x in 9.125..9.875 and every one of those is short of
     * the left edge at x = 10, while every sample of pixel (10, 10) is past it. There is no pixel
     * that straddles the edge at all — that is the whole point of sampling at (i + 0.5) / 4.
     */
    @Test
    fun anIntegerRectangleIsFullInsideAndEmptyAround() {
        val m = SelectionMask.rect(RectPx(10, 10, 10, 10))
        assertFalse(m.isEmpty, "a square is not empty")
        assertEquals(RectPx(10, 10, 10, 10), m.bounds(), "the tight box of coverage > 0")
        for (y in 9..20) {
            for (x in 9..20) {
                val inside = x in 10..19 && y in 10..19
                assertEquals(if (inside) 255 else 0, m.coverage(x, y), "pixel $x,$y")
            }
        }
        assertEquals(100, fullCount(m, RectPx(9, 9, 12, 12)))
        assertEquals(25_500L, coverageSum(m, RectPx(0, 0, 40, 40)), "100 pixels x 255 and nothing else")
        assertEquals(1, m.tiles.size, "one tile holds all of it")
        assertTrue(m.tiles.containsKey(Tiles.key(0, 0)), "tile (0,0) is 0..255 in both axes")
    }

    // ---- 2: negative coordinates and a seam ------------------------------------------------------

    /**
     * The same square at (-300, -5), which is the case the whole "canvas is unbounded" decision is
     * about. floorDiv(-300, 256) is -2 and floorDiv(-5, 256) is -1, so this sits in tile (-2, -1)
     * AND tile (-2, 0) — y = 0 is a tile seam, and the square straddles it, which is the point.
     * A `/` that truncated toward zero instead of flooring would put the x range in tile (-1, ...)
     * and address the wrong pixels.
     */
    @Test
    fun aRectangleAtNegativeCoordinatesCrossesASeam() {
        val m = SelectionMask.rect(RectPx(-300, -5, 10, 10))
        assertEquals(RectPx(-300, -5, 10, 10), m.bounds(), "negative bounds, tight")
        for (y in -6..5) {
            for (x in -301..-290) {
                val inside = x in -300..-291 && y in -5..4
                assertEquals(if (inside) 255 else 0, m.coverage(x, y), "pixel $x,$y")
            }
        }
        assertEquals(100, fullCount(m, RectPx(-305, -10, 20, 20)))
        assertEquals(25_500L, coverageSum(m, RectPx(-310, -20, 30, 30)))
        assertEquals(2, m.tiles.size, "y = 0 is a seam, so two tiles")
        assertTrue(m.tiles.containsKey(Tiles.key(-2, -1)), "y -5..-1 is tile -1")
        assertTrue(m.tiles.containsKey(Tiles.key(-2, 0)), "y 0..4 is tile 0")
        assertEquals(0, m.coverage(-301, 0), "one pixel left of it")
        assertEquals(255, m.coverage(-300, 0), "on the seam itself, and inside")
    }

    // ---- 3: a soft edge ---------------------------------------------------------------------------

    /**
     * Triangle (0,0), (40,0), (0,40) — the hypotenuse is the line x + y = 40.
     *
     * DERIVATION of pixel (19, 20), whose sixteen samples are at (19 + (i + 0.5)/4, 20 + (j + 0.5)/4).
     * The sum of those two coordinates is 39 + (i + j + 1) / 4, and a sample is inside when that is
     * LESS THAN 40, i.e. when i + j + 1 < 4, i.e. when i + j <= 2. The pairs with i + j <= 2 are
     * (0,0), (1,0), (0,1), (2,0), (1,1), (0,2): six samples. So the coverage is round(6 * 255 / 16)
     * = round(95.625) = 96.
     *
     * The scanline gets there independently: at sub-row y = 20 + (j + 0.5) / 4 the hypotenuse is at
     * x = 40 - y, so the spans are (0, 39.875), (0, 39.625), (0, 39.375) and (0, 19.125) — that is
     * 3, 2, 1 and 0 samples of column 19, 6 in total, because the right end of a span is EXCLUDED
     * and 19.875 is exactly where the fourth sample of column 19 sits.
     *
     * The spec asks for a range rather than a number, and 96 is its floor: the assertion is inclusive
     * at both ends so the test states the property ("near the middle of the scale") and still fails
     * on a rasteriser that is off by a whole factor.
     */
    @Test
    fun anEdgePixelIsAntialiasedAndTheInsideIsFull() {
        val m = SelectionMask.polygon(listOf(Pt(0.0, 0.0), Pt(40.0, 0.0), Pt(0.0, 40.0)))
        val onEdge = m.coverage(19, 20)
        assertTrue(onEdge in 96..160, "pixel (19,20) on the hypotenuse was $onEdge")
        assertEquals(255, m.coverage(2, 2), "far inside")
        assertEquals(255, m.coverage(10, 10), "inside")
        assertEquals(255, m.coverage(30, 5), "inside, near the hypotenuse but 3.25 px clear of it")
        assertEquals(0, m.coverage(20, 25), "outside, past the hypotenuse by more than a sample")
        assertEquals(0, m.coverage(-1, 5), "left of the vertical leg")
        assertEquals(0, m.coverage(5, 40), "below the horizontal leg")
        // The bounding box: the shape reaches x = 40 and y = 40, but the last pixel with coverage
        // is (39, 0) on the right (12 of 16 samples) and (0, 39) at the bottom (3 of 16), so the
        // tight box is exactly the shape's own 0..40 square.
        assertEquals(RectPx(0, 0, 40, 40), m.bounds())
    }

    // ---- 4: the fill rule -------------------------------------------------------------------------

    /**
     * NON-ZERO, not even-odd — the spec's headline decision, and the only one a lasso user can
     * feel. The figure-8's overlap is wound twice; even-odd would make it a hole.
     */
    @Test
    fun aSelfCrossingLassoFillsBothLobes() {
        val m = SelectionMask.polygon(figureEight(64))
        assertFalse(m.isEmpty)
        // 25 px from the left centre, 85 from the right one: inside the left lobe only.
        assertEquals(255, m.coverage(-55, 0), "left lobe")
        assertEquals(255, m.coverage(55, 0), "right lobe")
        // 30 px from BOTH centres, so winding is 2. Even-odd gives 0 here and only here.
        assertEquals(255, m.coverage(0, 0), "the overlap is wound twice and is still filled")
        assertEquals(255, m.coverage(-30, -20), "inside the left lobe again")
    }

    /**
     * The same circle drawn twice in one path. The interior is wound twice, so it is 255 — with
     * even-odd it would be 0, and a lasso that goes round twice would erase what it drew.
     */
    @Test
    fun aDoubleLoopIsSolidRatherThanAHole() {
        val once = circle(100.0, 100.0, 50.0, 64)
        val m = SelectionMask.polygon(once + once)
        assertEquals(255, m.coverage(100, 100), "the middle")
        assertEquals(255, m.coverage(70, 100), "30 px out")
        assertEquals(255, m.coverage(130, 100), "30 px the other way")
        assertEquals(255, m.coverage(100, 70))
        assertEquals(0, m.coverage(45, 45), "45 px out on the diagonal is outside a radius of 50")
    }

    // ---- 5: the seam -----------------------------------------------------------------------------

    /**
     * A diamond centred ON x = 256, i.e. straddling the seam between tile column 0 and tile column 1
     * (and straddling y = 256 as well, which is free). Shifting every vertex by exactly 256 maps
     * tile column n onto column n - 1 with every local x unchanged, so the shifted mask must be the
     * same picture one tile to the left — pixel for pixel, byte for byte.
     *
     * The vertices are at 45 degrees with INTEGER intercepts, and that is deliberate rather than
     * tidy: a sample can only be disputed if a crossing lands exactly on one, and for a line with an
     * integer intercept the equation "4x + 2i + 2 = 4*intercept + 4y + 2j + 1" has an even left and
     * an odd right, so no such sample exists. Without that, the comparison would be a test of
     * floating-point luck rather than of the seam.
     */
    @Test
    fun aLassoOverATileSeamHasNoSeam() {
        val diamond = listOf(Pt(256.0, 100.0), Pt(400.0, 256.0), Pt(256.0, 412.0), Pt(112.0, 256.0))
        val m = SelectionMask.polygon(diamond)
        val shifted = SelectionMask.polygon(diamond.map { Pt(it.x - 256.0, it.y) })
        for (y in 90..420) {
            for (x in 100..412) {
                assertEquals(m.coverage(x, y), shifted.coverage(x - 256, y), "pixel $x,$y")
            }
        }
        // The seam itself, at full strength on both sides of it.
        assertEquals(255, m.coverage(255, 256), "pixel just left of the seam")
        assertEquals(255, m.coverage(256, 256), "pixel just right of it")
        assertEquals(255, m.coverage(256, 255), "and just above it")
    }

    // ---- 6: the boolean ops ----------------------------------------------------------------------

    /**
     * a = x 0..9, y 0..9 (100 px). b = x 5..14, y 5..14 (100 px). They overlap in a 5 x 5 corner
     * (25 px), so the union is 175, the intersection 25 and the difference 75 — arithmetic, not
     * sampling, because every coverage involved is exactly 0 or 255.
     */
    @Test
    fun theBooleanOpsCountPixelsExactly() {
        val a = SelectionMask.rect(RectPx(0, 0, 10, 10))
        val b = SelectionMask.rect(RectPx(5, 5, 10, 10))
        val box = RectPx(0, 0, 20, 20)

        assertEquals(175, fullCount(a.add(b), box), "100 + 100 - 25")
        assertEquals(25, fullCount(a.intersect(b), box), "the 5 x 5 corner")
        assertEquals(75, fullCount(a.subtract(b), box), "a less its overlap")

        assertEquals(0, a.subtract(b).coverage(7, 7), "255 * (255 - 255) / 255 is 0")
        assertEquals(255, a.subtract(b).coverage(7, 2), "inside a, outside b")
        assertEquals(0, a.subtract(b).coverage(12, 7), "inside b only: 0 * ... is 0")
        assertEquals(255, a.intersect(b).coverage(7, 7), "255 * 255 / 255 is 255")
        assertEquals(0, a.intersect(b).coverage(2, 2), "outside b")
        assertEquals(255, a.add(b).coverage(12, 7), "b's corner keeps its own 255")

        assertTrue(a.subtract(a).isEmpty, "a minus itself")
        assertNull(a.subtract(a).bounds(), "and it has no box")
        assertTrue(a.intersect(SelectionMask.EMPTY).isEmpty)
        assertTrue(SelectionMask.EMPTY.intersect(a).isEmpty, "the other way round too")
        assertTrue(a.subtract(SelectionMask.EMPTY) === a, "nothing to take away")

        val same = a.add(SelectionMask.EMPTY)
        assertFalse(same.isEmpty)
        for (y in -2 until 22) {
            for (x in -2 until 22) {
                assertEquals(a.coverage(x, y), same.coverage(x, y), "pixel $x,$y")
            }
        }
        assertEquals(a.bounds(), same.bounds())
    }

    /**
     * `invert` is 255 - a INSIDE the box and 0 outside it, so it is the one op that can turn an
     * empty pixel into a full one.
     */
    @Test
    fun invertComplementsWithinItsBox() {
        val a = SelectionMask.rect(RectPx(0, 0, 10, 10))
        val box = RectPx(0, 0, 20, 20)
        val inv = a.invert(box)
        assertEquals(300, fullCount(inv, box), "400 - the 100 a had")
        assertEquals(0, inv.coverage(0, 0), "a had it, so inverting takes it away")
        assertEquals(255, inv.coverage(12, 12), "a did not have it, so inverting fills it")
        assertEquals(255, inv.coverage(19, 0))
        assertEquals(box, inv.bounds(), "the whole box, corner to corner")
        assertEquals(0, inv.coverage(20, 0), "outside the box stays outside")
        assertEquals(0, inv.coverage(-1, 5), "outside the box stays outside")
        assertEquals(0, inv.coverage(0, 20))

        // The same rule on a SOFT pixel, using the number the triangle test derived: 255 - 96 = 159.
        val triangle = SelectionMask.polygon(listOf(Pt(0.0, 0.0), Pt(40.0, 0.0), Pt(0.0, 40.0)))
        val soft = triangle.invert(RectPx(0, 0, 40, 40))
        assertEquals(159, soft.coverage(19, 20), "255 - 96")
        assertEquals(0, soft.coverage(2, 2), "255 - 255")
        assertEquals(0, soft.coverage(45, 20), "outside the box, whatever the triangle says")
    }

    // ---- 7: a fill result ------------------------------------------------------------------------

    /**
     * A genuine `FloodFill` result: a 7 x 7 reference whose outer ring is transparent and whose
     * 5 x 5 middle is opaque white, tapped in the middle. `grow = 0` because the default grows the
     * region by a pixel and would hand back the whole 7 x 7 — which is the fill's business, not
     * this test's.
     *
     * Placed at (-7, 300) it crosses the x = 0 seam and the y = 256 seam, and every one of the 49
     * bytes must come back at the pixel it came from.
     */
    @Test
    fun aFloodFillResultPlacedAtAnOriginRoundTrips() {
        val w = 7
        val h = 7
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) continue
                val o = (y * w + x) * 4
                rgba[o] = 255.toByte()
                rgba[o + 1] = 255.toByte()
                rgba[o + 2] = 255.toByte()
                rgba[o + 3] = 255.toByte()
            }
        }
        val region = FloodFill.fill(w, h, rgba, 3, 3, FillOptions(grow = 0))
        assertEquals(25, fullCount(SelectionMask.fromMask(w, h, region, 0, 0), RectPx(0, 0, w, h)), "the 5 x 5")

        val m = SelectionMask.fromMask(w, h, region, -7, 300)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val want = region[row * w + col].toInt() and 0xFF
                assertEquals(want, m.coverage(-7 + col, 300 + row), "source pixel $col,$row")
            }
        }
        assertEquals(RectPx(-6, 301, 5, 5), m.bounds(), "the ring was not filled")
        assertEquals(0, m.coverage(-7, 300), "the border came back empty")
        assertEquals(0, m.coverage(-8, 300), "one pixel outside the placed mask")
        assertEquals(0, m.coverage(-7, 299))
        assertEquals(255, m.coverage(-4, 303), "the middle of the placed fill")
    }

    /**
     * A mask is not only 0 and 255 — `fromMask` is the door a soft-edged result comes through, so
     * it must place the bytes it is given rather than rounding them.
     */
    @Test
    fun placedMasksKeepTheirSoftValues() {
        val m = SelectionMask.fromMask(
            3, 2,
            byteArrayOf(255.toByte(), 0, 128.toByte(), 0, 255.toByte(), 64.toByte()),
            10, 20,
        )
        assertEquals(255, m.coverage(10, 20))
        assertEquals(0, m.coverage(11, 20))
        assertEquals(128, m.coverage(12, 20), "128 survives, and reads back unsigned")
        assertEquals(0, m.coverage(10, 21))
        assertEquals(255, m.coverage(11, 21))
        assertEquals(64, m.coverage(12, 21))
        assertEquals(RectPx(10, 20, 3, 2), m.bounds())
        assertTrue(SelectionMask.fromMask(0, 4, ByteArray(0), 0, 0).isEmpty, "nothing in, nothing out")
    }

    // ---- 8: the ellipse --------------------------------------------------------------------------

    /**
     * Area, which is the one thing about an ellipse that is a fact rather than a shape. The
     * 360-gon is INSCRIBED, so its area is (n r^2 / 2) sin(2 pi / n) = 7853.58 against the circle's
     * 7853.98 — 0.005 % low, and a sixteenth of the 1 % the test allows. The 4 x 4 sampling adds
     * rather more than that: at most half a sample on each of the ~314 boundary pixels, which is
     * about 0.3 px of area. Both are three orders of magnitude inside the bound.
     */
    @Test
    fun anEllipseHasTheAreaOfItsCircle() {
        val m = SelectionMask.ellipse(200.0, 200.0, 50.0, 50.0, 0.0)
        assertFalse(m.isEmpty)
        assertEquals(255, m.coverage(200, 200), "the middle")
        val area = coverageSum(m, RectPx(100, 100, 200, 200)).toDouble() / 255.0
        val want = PI * 50.0 * 50.0
        assertTrue(area > want * 0.99 && area < want * 1.01, "area was $area against $want")

        // The box has to HOLD the ellipse: pixels 150..249 in both axes carry coverage (the nearest
        // sample of pixel (249, 200) is 49.88 px from the centre and the nearest of pixel (250, 200)
        // is 50.13), so the tight box cannot be smaller than RectPx(150, 150, 100, 100) — and cannot
        // be larger either, so this is stated as containment rather than as an equality.
        val bounds = assertNotNull(m.bounds(), "an ellipse has a box")
        assertTrue(
            bounds.x <= 150 && bounds.y <= 150 && bounds.x + bounds.w >= 250 && bounds.y + bounds.h >= 250,
            "the box holds the 50 px radius, and is $bounds",
        )
    }

    // ---- 9: rubbish in ---------------------------------------------------------------------------

    /**
     * Nothing here throws. A lasso comes off a gesture, and a gesture can hand over two points, a
     * NaN from a divide that went wrong, or a polygon the size of a postcode.
     */
    @Test
    fun rubbishIsEmptyRatherThanAnException() {
        assertTrue(SelectionMask.polygon(emptyList()).isEmpty, "no points")
        assertTrue(SelectionMask.polygon(listOf(Pt(1.0, 1.0))).isEmpty, "one point")
        assertTrue(SelectionMask.polygon(listOf(Pt(0.0, 0.0), Pt(10.0, 10.0))).isEmpty, "two points, no area")
        assertTrue(
            SelectionMask.polygon(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(Double.NaN, 5.0))).isEmpty,
            "a NaN vertex",
        )
        assertTrue(
            SelectionMask.polygon(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(5.0, Double.POSITIVE_INFINITY))).isEmpty,
            "an infinite vertex",
        )
        assertTrue(
            SelectionMask.polygon(listOf(Pt(0.0, 0.0), Pt(20_000.0, 0.0), Pt(0.0, 10.0))).isEmpty,
            "20000 px wide",
        )
        assertTrue(
            SelectionMask.polygon(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(0.0, 20_000.0))).isEmpty,
            "20000 px tall",
        )
        assertTrue(SelectionMask.rect(RectPx(0, 0, 0, 10)).isEmpty, "a rectangle of no width")
        assertTrue(SelectionMask.rect(RectPx(0, 0, 10, -1)).isEmpty, "a negative height")
        assertTrue(SelectionMask.ellipse(0.0, 0.0, 0.0, 0.0, 0.0).isEmpty, "an ellipse of no radius")

        assertTrue(SelectionMask.EMPTY.isEmpty)
        assertNull(SelectionMask.EMPTY.bounds())
        assertEquals(0, SelectionMask.EMPTY.coverage(0, 0))
        assertEquals(0, SelectionMask.EMPTY.coverage(-9, 12))

        // The cap itself: 16384 is allowed, 16385 is not, and a one-pixel-tall strip is cheap to
        // ask about whichever way the answer goes.
        val atCap = SelectionMask.rect(RectPx(0, 0, 16384, 1))
        assertFalse(atCap.isEmpty, "16384 is the cap, not one past it")
        assertEquals(16_384, fullCount(atCap, RectPx(0, 0, 16385, 1)))
        assertTrue(SelectionMask.rect(RectPx(0, 0, 16385, 1)).isEmpty, "one past the cap")
    }

    // ---- 10: the performance case -----------------------------------------------------------------

    /**
     * 3000 points around a 2048 x 2048 box: 8192 sub-scanlines, each walking 3000 edges and then
     * filling its spans. The result is only checked for being there and for having the middle in
     * it — this test is a clock, and a clock that also asserts the picture is a test that fails for
     * the right reason.
     */
    @Test
    fun threeThousandPointsOverTwoThousandSquareIsQuick() {
        val points = circle(1024.0, 1024.0, 1024.0, 3000)
        val started = TimeSource.Monotonic.markNow()
        val m = SelectionMask.polygon(points)
        val ms = started.elapsedNow().inWholeMilliseconds
        assertFalse(m.isEmpty, "3000 points is a shape")
        assertEquals(255, m.coverage(1024, 1024), "and the middle of it is selected")
        assertTrue(ms < 600, "a 3000-point lasso over 2048 x 2048 took $ms ms")
    }
}
