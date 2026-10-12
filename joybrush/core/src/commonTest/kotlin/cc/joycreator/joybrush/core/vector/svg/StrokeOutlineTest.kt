package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The outline against the thing it claims to be the edge of: the production raster (`InkRaster`,
 * hard tip, alpha ≥ 0.5) of the same dabs, at 4 samples per doc px. The outline is filled by a
 * test-side NONZERO scanline. IoU is (both) / (either); 0.99 is the bar.
 *
 * The 1x raster's 50 % contour sits ~0.25 SCREEN px inside the tip's true edge (the shader's AA band
 * is asymmetric), so at scale 4 the reference is ~0.06 doc px thinner than the outline on every edge;
 * the IoU numbers include that.
 */
class StrokeOutlineTest {

    /** [sweepOnly]: a tip whose sweep is knowingly wider than its discrete dabs (see [spikyTipsExportTheirSweep]). */
    private class Case(val name: String, val dabs: List<Dab>, val tip: TipShape = TipShape(), val sweepOnly: Boolean = false)

    private val round = TipShape(hardness = 1f)

    private fun sCurve(n: Int = 2000) = OutlineRaster.curve(n) { t -> Pt(40 + 300 * t, 150 + 70 * sin(2 * PI * t)) }

    private fun follow(s: Double, tx: Double, ty: Double) = atan2(ty, tx)

    private val cases: List<Case> by lazy {
        listOf(
            // Width follows "pressure": 8..18 px radius, dr/ds up to 0.2.
            Case("straight-pressure", OutlineRaster.dabsAlong(listOf(Pt(30.0, 100.0), Pt(330.0, 100.0)), 0.05,
                { s -> 8 + 10 * (0.5 + 0.5 * sin(s / 25)) }), round),
            Case("s-curve", OutlineRaster.dabsAlong(sCurve(), 0.05, { 12.0 }), round),
            // Up, a semicircle of radius 4 (centre-line), back down: the turn is far tighter than the 14 px half-width.
            Case("hairpin", OutlineRaster.dabsAlong(hairpin(), 0.05, { 14.0 }), round),
            // 5 → 30 px radius in 20 px of travel (dr/ds = 1.25: each dab swallows the one before), then 30 → 5 in 40.
            Case("fast-width", OutlineRaster.dabsAlong(listOf(Pt(40.0, 100.0), Pt(320.0, 100.0)), 0.05, { s ->
                when {
                    s < 40 -> 5.0
                    s < 60 -> 5 + 25 * (s - 40) / 20
                    s < 100 -> 30.0
                    s < 140 -> 30 - 25 * (s - 100) / 40
                    else -> 5.0
                }
            }), round),
            // aspect 0.6: 0.4·r along the tip's x axis, which turns with the travel direction.
            Case("oval-follow", OutlineRaster.dabsAlong(sCurve(), 0.05, { 14.0 }, ::follow), round.copy(aspect = 0.6f)),
            Case("single-dab", listOf(Dab(60f, 60f, 20f)), round),
            Case("single-oval", listOf(Dab(60f, 60f, 20f, angle = 0.7f)), round.copy(aspect = -0.5f)),
            // Exact path, n ≠ 2: a rounded square at a fixed calligraphy angle.
            Case("square-fixed", OutlineRaster.dabsAlong(sCurve(), 0.05, { 12.0 }, { _, _, _ -> 0.5 }),
                round.copy(corner = 6f, aspect = -0.3f)),
            // Sampled path: a four-point star (corner < 1 is not convex) and a teardrop taper.
            Case("star-tip", OutlineRaster.dabsAlong(sCurve(), 0.05, { 14.0 }, { _, _, _ -> 0.3 }), round.copy(corner = 0.7f),
                sweepOnly = true),
            Case("taper-follow", OutlineRaster.dabsAlong(sCurve(), 0.05, { 14.0 }, ::follow), round.copy(taper = 0.5f)),
        )
    }

    private fun hairpin(): List<Pt> {
        val out = ArrayList<Pt>()
        for (i in 0..150) out.add(Pt(100.0, 250.0 - i))
        for (i in 1..60) { val a = PI - PI * i / 60; out.add(Pt(104 + 4 * cos(a), 100 - 4 * sin(a))) }
        for (i in 1..150) out.add(Pt(108.0, 100.0 + i))
        return out
    }

    private fun gridFor(dabs: List<Dab>): OutlineRaster.Grid {
        val pad = dabs.maxOf { it.radius } * 1.5 + 4
        val x0 = floor(dabs.minOf { it.x } - pad).toInt()
        val y0 = floor(dabs.minOf { it.y } - pad).toInt()
        val x1 = ceil(dabs.maxOf { it.x } + pad).toInt()
        val y1 = ceil(dabs.maxOf { it.y } + pad).toInt()
        return OutlineRaster.Grid(x0, y0, (x1 - x0) * SCALE, (y1 - y0) * SCALE, SCALE.toFloat())
    }

    /**
     * The IoU bar for every case. Would fail if: the tip geometry drifted from TipMath (oval/square/
     * taper cases lose area along whole sides); the side points were taken straight across travel
     * instead of on the bitangent (straight-pressure and fast-width cut inside by up to 15 % of r);
     * a contained dab were not dropped (fast-width would fold its sides through the big dab); caps
     * were swept the wrong way (each end would lose or invert a half-tip); or the Bézier fit
     * overshot (every case). "missed" is the share of raster pixels the outline leaves out, "extra"
     * the share of outline pixels the raster does not draw.
     */
    @Test fun outlineMatchesTheDabRasterOnEveryCase() {
        val report = StringBuilder()
        var worst = 1.0
        for (c in cases) {
            val m = measure(c)
            report.append("IoU ${c.name}: fitted=${fmt(m.iouFit)} dense=${fmt(m.iouDense)} missed=${fmt(m.missed)} " +
                "extra=${fmt(m.extra)} dabs=${c.dabs.size} segments=${m.segments}${if (c.sweepOnly) " (sweep-only)" else ""}\n")
            if (!c.sweepOnly) worst = minOf(worst, m.iouFit)
        }
        println(report)
        assertTrue(worst >= 0.99, "IoU below 0.99:\n$report")
    }

    /**
     * A spiky tip (corner < 1) exports its SWEEP, which is wider than its discrete dabs: at spacing
     * 0.05 the last ~2 px of neighbouring spike tips do not touch, so the raster's side edge is a
     * sawtooth ~2 px inside the line the spike tips trace, and IoU is ~0.89. Only tuft brushes (not
     * ink lines) ship such tips today. What must still hold is that the outline LOSES nothing the
     * raster draws. Would fail if the caps or the concave arcs between support vertices cut into the
     * stroke.
     */
    @Test fun spikyTipsExportTheirSweep() {
        val m = measure(cases.first { it.name == "star-tip" })
        assertTrue(m.missed <= 0.005, "star tip: ${fmt(m.missed)} of the raster is outside the outline")
    }

    private class Measured(val iouFit: Double, val iouDense: Double, val missed: Double, val extra: Double, val segments: Int)

    private fun measure(c: Case): Measured {
        val g = gridFor(c.dabs)
        val ref = OutlineRaster.dabAlpha(c.dabs, c.tip, g).let { a -> BooleanArray(a.size) { a[it] >= 0.5f } }
        val contours = StrokeOutline.contours(c.dabs, c.tip)
        val fitted = OutlineRaster.fill(OutlineRaster.flatten(contours), g)
        val dense = OutlineRaster.fill(StrokeOutline.rings(c.dabs, c.tip), g)
        var refN = 0
        var fitN = 0
        var missed = 0
        var extra = 0
        for (i in ref.indices) {
            if (ref[i]) refN++
            if (fitted[i]) fitN++
            if (ref[i] && !fitted[i]) missed++
            if (fitted[i] && !ref[i]) extra++
        }
        return Measured(OutlineRaster.iou(ref, fitted), OutlineRaster.iou(ref, dense), missed.toDouble() / refN,
            extra.toDouble() / fitN, contours.sumOf { it.segments.size })
    }

    /**
     * No holes in a tight turn. Every pixel at least 2 px (8 samples) inside the dab raster must be
     * inside the nonzero fill. Would fail if the outline's inner fold had the opposite winding to the
     * body (it would cancel to 0 there), or if the fill rule were even-odd (the fold overlaps the body,
     * winding 2 → "outside").
     */
    @Test fun hairpinHasNoHolesUnderNonzero() {
        val c = cases.first { it.name == "hairpin" }
        val g = gridFor(c.dabs)
        val alpha = OutlineRaster.dabAlpha(c.dabs, c.tip, g)
        val inside = OutlineRaster.fill(OutlineRaster.flatten(StrokeOutline.contours(c.dabs, c.tip)), g)
        val m = 2 * SCALE
        val w = g.width
        // Erode the raster by a (2m+1)² square, separably: a pixel is "deep" when every pixel within m is covered.
        val hor = BooleanArray(alpha.size)
        for (y in 0 until g.height) {
            var run = 0
            for (x in 0 until w) {
                run = if (alpha[y * w + x] >= 0.5f) run + 1 else 0
                if (run >= 2 * m + 1) hor[y * w + x - m] = true
            }
        }
        var holes = 0
        var deep = 0
        for (x in 0 until w) {
            var run = 0
            for (y in 0 until g.height) {
                run = if (hor[y * w + x]) run + 1 else 0
                if (run >= 2 * m + 1) {
                    deep++
                    if (!inside[(y - m) * w + x]) holes++
                }
            }
        }
        assertTrue(deep > 1000, "the hairpin has a body to test ($deep)")
        assertEquals(0, holes, "pixels deep inside the stroke that the nonzero outline leaves empty")
    }

    /**
     * The fitted curve stays within RDP 0.1 + fit 0.15 (+ 0.02 for flattening and rounding) of the dense
     * outline, both ways. Would fail if the fit accepted a segment by its end points only, or if a
     * missed corner forced a smooth tangent through a cusp.
     */
    @Test fun fittedCurveStaysWithinTheErrorBudget() {
        val budget = StrokeOutline.SIMPLIFY_PX + StrokeOutline.FIT_PX + 0.02
        for (c in cases) {
            val rings = StrokeOutline.rings(c.dabs, c.tip)
            val flat = OutlineRaster.flatten(StrokeOutline.contours(c.dabs, c.tip), steps = 16)
            assertEquals(rings.size, flat.size, c.name)
            for (k in rings.indices) {
                var out = 0.0
                for (p in flat[k]) out = max(out, OutlineRaster.distanceToRing(p, rings[k]))
                var back = 0.0
                for (p in rings[k]) back = max(back, OutlineRaster.distanceToRing(p, flat[k]))
                assertTrue(out <= budget && back <= budget, "${c.name}: curve→dense $out, dense→curve $back, budget $budget")
            }
        }
    }

    /**
     * One closed piece for a connected run, however it turns (R8: never one shape per dab). Would fail
     * if the hairpin or the swallowed dabs split the run.
     */
    @Test fun aConnectedRunIsOneContour() {
        for (c in cases) assertEquals(1, StrokeOutline.contours(c.dabs, c.tip).size, c.name)
    }

    /**
     * Dabs that do not touch (spacing 1.5 × diameter) are separate pieces — the dots of a dotted
     * line — not bridged by a tangent across the gap. Would fail if `apart` were skipped: one long
     * capsule instead of six dots.
     */
    @Test fun dotsThatDoNotTouchAreSeparatePieces() {
        val dots = (0 until 6).map { Dab(20f + it * 30f, 20f, 10f) }
        assertEquals(6, StrokeOutline.contours(dots, round).size)
        val touching = (0 until 6).map { Dab(20f + it * 15f, 20f, 10f) }
        assertEquals(1, StrokeOutline.contours(touching, round).size)
    }

    /** A dashed stroke's runs are each a contour of the same outline list. */
    @Test fun runsBecomeContoursOfOneOutline() {
        val a = OutlineRaster.dabsAlong(listOf(Pt(10.0, 10.0), Pt(60.0, 10.0)), 0.05, { 4.0 })
        val b = OutlineRaster.dabsAlong(listOf(Pt(80.0, 10.0), Pt(130.0, 10.0)), 0.05, { 4.0 })
        assertEquals(2, StrokeOutline.strokeContours(listOf(a, b), round).size)
    }

    /**
     * Unusable dabs are skipped, not traced: a NaN position or a zero radius contributes no point.
     * Would fail with NaN coordinates in the ring.
     */
    @Test fun brokenDabsAreSkipped() {
        val dabs = listOf(Dab(10f, 10f, 5f), Dab(Float.NaN, 10f, 5f), Dab(12f, 10f, 0f), Dab(13f, 10f, 5f))
        val rings = StrokeOutline.rings(dabs, round)
        assertEquals(1, rings.size)
        assertTrue(rings[0].all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(StrokeOutline.rings(listOf(Dab(1f, 1f, -2f)), round).isEmpty())
    }

    private fun fmt(v: Double): String = (floor(v * 100000 + 0.5) / 100000).toString()

    private companion object {
        const val SCALE = 4
    }
}
