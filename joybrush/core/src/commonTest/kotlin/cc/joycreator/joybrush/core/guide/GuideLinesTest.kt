package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JB-2.12a spec test 9: the lines an overlay gets, the thinning, the clipping and the cap. */
class GuideLinesTest {

    private fun view(left: Float, top: Float, right: Float, bottom: Float) =
        floatArrayOf(left, top, right, bottom)

    /** The x of every vertical segment, ascending. `+ 0.0` turns a -0.0 into a 0.0. */
    private fun verticals(segs: List<FloatArray>): List<Double> =
        segs.filter { it[0] == it[2] }.map { it[0].toDouble() + 0.0 }.sorted()

    /** The y of every horizontal segment, ascending. */
    private fun horizontals(segs: List<FloatArray>): List<Double> =
        segs.filter { it[1] == it[3] }.map { it[1].toDouble() + 0.0 }.sorted()

    private fun assertLines(got: List<Double>, want: List<Double>, what: String) {
        assertEquals(want.size, got.size, "$what: $got")
        for (i in want.indices) {
            assertTrue(abs(got[i] - want[i]) < 1e-3, "$what: line ${i} is at ${got[i]}, wanted ${want[i]}")
        }
    }

    // ── thinning ──────────────────────────────────────────────────────────────

    @Test
    fun aGridZoomedOutIsThinnedUntilItsLinesAreEightScreenPxApart() {
        val segs = GuideLines.visible(Guide.Grid(10.0), view(0f, 0f, 100f, 100f), 0.2f)
        // 10 doc px at 0.2 is 2 screen px. Doubling: 4 px at the 2nd line, 8 px at the 4th — the
        // first step that is far enough apart, so the drawn grid is every 40 doc px.
        assertEquals(6, segs.size, "$segs")
        assertLines(verticals(segs), listOf(0.0, 40.0, 80.0), "vertical")
        assertLines(horizontals(segs), listOf(0.0, 40.0, 80.0), "horizontal")
        // 40 doc px at 0.2 is 8 screen px — the thinning stopped the moment the gap reached 8.
        val gapScreen = 40.0 * 0.2f
        assertTrue(gapScreen >= 7.99, "the drawn lines are $gapScreen screen px apart")
        // Nothing is outside the view, and the view is the whole 100×100.
        for (s in segs) {
            assertTrue(s[0] >= 0f && s[0] <= 100f && s[2] >= 0f && s[2] <= 100f, "$s")
            assertTrue(s[1] >= 0f && s[1] <= 100f && s[3] >= 0f && s[3] <= 100f, "$s")
        }
    }

    @Test
    fun aGridZoomedInIsNotThinned() {
        val segs = GuideLines.visible(Guide.Grid(10.0), view(0f, 0f, 100f, 100f), 2f)
        // 10 doc px at 2 is 20 screen px, already past 8: every line of the grid is drawn.
        assertEquals(22, segs.size, "$segs")
        assertLines(verticals(segs), (0..10).map { it * 10.0 }, "vertical")
        assertLines(horizontals(segs), (0..10).map { it * 10.0 }, "horizontal")
    }

    // ── clipping ──────────────────────────────────────────────────────────────

    @Test
    fun everySegmentStaysInsideTheView() {
        val v = view(100f, 50f, 300f, 250f)
        val guides = listOf(
            "grid" to (Guide.Grid(10.0) as Guide),
            "iso" to Guide.Isometric(20.0),
            "persp" to Guide.Perspective(listOf(Pt(150.0, 400.0), Pt(400.0, 120.0))),
            "ruler" to Guide.Ruler(Pt(0.0, 0.0), Pt(1.0, 1.0)),
        )
        for ((name, g) in guides) {
            val segs = GuideLines.visible(g, v, 1f)
            assertTrue(segs.isNotEmpty(), "$name drew nothing")
            for (s in segs) {
                for (e in 0..1) {
                    val x = s[e * 2].toDouble()
                    val y = s[e * 2 + 1].toDouble()
                    assertTrue(x >= 100.0 - 1e-3 && x <= 300.0 + 1e-3, "$name x=$x in $s")
                    assertTrue(y >= 50.0 - 1e-3 && y <= 250.0 + 1e-3, "$name y=$y in $s")
                }
            }
        }
    }

    @Test
    fun aRulerIsDrawnRightAcrossTheViewAndNotJustItsOwnSegment() {
        // The ruler is the 1 px segment (0,0)–(1,1); the line it lies on crosses the view at
        // (100, 100) and (200, 200).
        val segs = GuideLines.visible(Guide.Ruler(Pt(0.0, 0.0), Pt(1.0, 1.0)), view(100f, 0f, 200f, 400f), 1f)
        assertEquals(1, segs.size, "$segs")
        val s = segs[0]
        assertTrue(abs(s[0] - 100f) < 1e-3f, "x0=${s[0]}")
        assertTrue(abs(s[1] - 100f) < 1e-3f, "y0=${s[1]}")
        assertTrue(abs(s[2] - 200f) < 1e-3f, "x1=${s[2]}")
        assertTrue(abs(s[3] - 200f) < 1e-3f, "y1=${s[3]}")
    }

    @Test
    fun aVanishingPointOffTheViewStillThrowsRaysIntoIt() {
        val segs = GuideLines.visible(
            Guide.Perspective(listOf(Pt(150.0, 400.0), Pt(400.0, 120.0))),
            view(100f, 50f, 300f, 250f), 1f,
        )
        // 24 rays per point, but only the ones that reach the view survive, plus the horizon.
        assertTrue(segs.isNotEmpty())
        assertTrue(segs.size <= 24 * 2 + 1, "${segs.size}")
    }

    // ── the ellipse tracer ─────────────────────────────────────────────────────

    @Test
    fun anEllipseTracerIsOneHundredAndTwentyEightPiecesOnTheCurve() {
        val e = Guide.EllipseTracer(Pt(200.0, 300.0), 120.0, 60.0, 0.3)
        val segs = GuideLines.visible(e, view(0f, 0f, 400f, 400f), 1f)
        assertEquals(128, segs.size)
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        for (seg in segs) {
            for (end in 0..1) {
                val dx = seg[end * 2].toDouble() - e.center.x
                val dy = seg[end * 2 + 1].toDouble() - e.center.y
                val u = c * dx + s * dy
                val v = -s * dx + c * dy
                val r = (u * u) / (e.rx * e.rx) + (v * v) / (e.ry * e.ry)
                // Floats hold the coordinates, so the residual can only be a few parts in 1e6.
                assertTrue(abs(r - 1.0) < 1e-5, "point (${seg[end * 2]}, ${seg[end * 2 + 1]}) has r=$r")
            }
        }
        // Closed: the last piece ends where the first one began.
        assertTrue(abs(segs[127][2] - segs[0][0]) < 1e-3f)
        assertTrue(abs(segs[127][3] - segs[0][1]) < 1e-3f)
    }

    // ── the cap ───────────────────────────────────────────────────────────────

    @Test
    fun aMilliPixelGridOverAHugeViewStopsAtTwoThousandSegments() {
        // 0.001 doc px at 1:1: the thinned spacing is 0.001 × 8192 = 8.192 screen px, so the 100000
        // px view alone would want 12208 lines in each direction. The cap is reached by stopping.
        val segs = GuideLines.visible(Guide.Grid(0.001), view(0f, 0f, 100000f, 100000f), 1f)
        assertEquals(2000, segs.size)
        assertTrue(segs.size <= GuideLines.MAX_SEGMENTS)
    }

    // ── rubbish in, nothing out ───────────────────────────────────────────────

    @Test
    fun rubbishInGivesNothingOutAndNoException() {
        val g = Guide.Grid(10.0)
        assertTrue(GuideLines.visible(g, floatArrayOf(0f, 0f), 1f).isEmpty())
        assertTrue(GuideLines.visible(g, view(0f, 0f, 100f, 100f), 0f).isEmpty())
        assertTrue(GuideLines.visible(g, view(0f, 0f, 100f, 100f), Float.NaN).isEmpty())
        assertTrue(GuideLines.visible(g, view(Float.NaN, 0f, 100f, 100f), 1f).isEmpty())
        assertTrue(GuideLines.visible(g, view(100f, 0f, 0f, 100f), 1f).isEmpty())
        assertTrue(GuideLines.visible(Guide.Grid(-5.0), view(0f, 0f, 100f, 100f), 1f).isEmpty())
        assertTrue(GuideLines.visible(Guide.Grid(Double.NaN), view(0f, 0f, 100f, 100f), 1f).isEmpty())
        assertTrue(GuideLines.visible(Guide.Ruler(Pt(5.0, 5.0), Pt(5.0, 5.0)), view(0f, 0f, 10f, 10f), 1f).isEmpty())
        assertTrue(GuideLines.visible(Guide.Perspective(emptyList()), view(0f, 0f, 10f, 10f), 1f).isEmpty())
        assertTrue(GuideLines.visible(Guide.EllipseTracer(Pt(5.0, 5.0), 0.0, 3.0, 0.0), view(0f, 0f, 10f, 10f), 1f).isEmpty())
    }

    @Test
    fun anIsometricGridHasThreeDirections() {
        val v = view(0f, 0f, 100f, 100f)
        val iso = GuideLines.visible(Guide.Isometric(10.0), v, 1f)
        val grid = GuideLines.visible(Guide.Grid(10.0), v, 1f)
        // 10 doc px at 1:1 is 10 screen px, past 8, so nothing is thinned. The vertical family is
        // axis aligned: 11 lines. Each slanted family steps along its own normal, and the normal's
        // extent across a 100×100 view is 10·100·(1 + cos30) = 186.6, so 14 lines reach the rect —
        // but the first and the last of those only touch one corner, and a single point is not a
        // segment, so 13 each: 13 + 11 + 13.
        assertEquals(37, iso.size, "$iso")
        assertEquals(22, grid.size)
        // The 90° family is the one both grids share, and it lands in the same place.
        assertLines(
            iso.filter { it[0] == it[2] }.map { it[0].toDouble() + 0.0 }.sorted(),
            (0..10).map { it * 10.0 },
            "isometric vertical",
        )
    }

    @Test
    fun theCapIsAlsoHonouredByAGridThatFitsTheViewButAsksForThousandsOfLines() {
        // 3 screen px between lines: thinned by 4 to 12, so a 30000 px view still wants 2500 lines.
        val segs = GuideLines.visible(Guide.Grid(3.0), view(0f, 0f, 30000f, 30000f), 1f)
        assertTrue(segs.size <= 2000, "${segs.size}")
        assertTrue(segs.size >= 1)
    }
}
